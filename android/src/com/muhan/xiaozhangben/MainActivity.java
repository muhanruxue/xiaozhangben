package com.muhan.xiaozhangben;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.KeyEvent;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Calendar;

/**
 * 黑猫记账 · 安卓外壳
 * - 整个记账应用是一个内置的单文件 HTML，完全离线运行
 * - 数据存 WebView 自己的 LocalStorage（App 私有空间，不联网）
 * - 导出备份通过 saveFile 桥接直接写入手机 Download 目录
 * - 每日记账提醒由原生 AlarmManager 调度系统通知（即使 App 关闭也会提醒）
 */
public class MainActivity extends Activity {

    private static final String TAG = "XiaoZhangBen";
    /** 给页面一个正式的 https 源，LocalStorage 才能正常工作（data:/file: 是透明源） */
    private static final String BASE_URL = "https://xiaozhangben.local/";
    private static final int REQ_FILE = 1001;
    private static final int REQ_NOTIF = 1002;

    // ===== 每日提醒相关 =====
    private static final String PREFS = "xzb_reminder";
    private static final String KEY_EN = "reminder_enabled";
    private static final String KEY_TM = "reminder_time";
    private static final int REMIND_REQ = 0x1234;
    static final String CHANNEL_ID = "xzb_remind";

    private WebView web;
    private UpdateManager updater;
    private ValueCallback<Uri[]> filePathCallback;
    private long lastBackAt = 0;
    /** 页面里的「记一笔」全屏输入页是否打开（由 JS 通过桥接上报） */
    private volatile boolean addPageOpen = false;
    private volatile boolean timeSheetOpen = false;

    // ===== 提醒调度（静态，供 Receiver 复用）=====

    static SharedPreferences reminderPrefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 依据 "HH:MM" 排定下一次提醒；若今天该时刻已过，则顺延到明天 */
    static void scheduleReminder(Context c, String hhmm) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(c, ReminderReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(c, REMIND_REQ, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Calendar cal = Calendar.getInstance();
        int h = 20, m = 0;
        try {
            String[] p = hhmm.split(":");
            h = Integer.parseInt(p[0]);
            m = Integer.parseInt(p[1]);
        } catch (Exception ignored) {}
        cal.set(Calendar.HOUR_OF_DAY, h);
        cal.set(Calendar.MINUTE, m);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_MONTH, 1); // 今天已过 -> 明天
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+：优先精确闹钟，无权限时降级为允许闲置时的精确闹钟
            if (am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
            }
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
        }
    }

    static void cancelReminder(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(c, ReminderReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(c, REMIND_REQ, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.cancel(pi);
    }

    /** 读取已保存的提醒设置并排期（开启才排，关闭则取消） */
    static void scheduleFromPrefs(Context c) {
        SharedPreferences sp = reminderPrefs(c);
        if (sp.getBoolean(KEY_EN, false)) {
            scheduleReminder(c, sp.getString(KEY_TM, "20:00"));
        } else {
            cancelReminder(c);
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "记账提醒", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("每天定时提醒记账");
            nm.createNotificationChannel(ch);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 通知渠道（Android 8+ 必须）
        createChannel();
        // Android 13+ 需要运行时申请通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != getPackageManager().PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            }
        }
        // 进入即按已保存设置排期（保证每天提醒持续生效，即便此前被系统回收）
        scheduleFromPrefs(this);

        web = new WebView(this);
        setContentView(web);
        updater = new UpdateManager(this);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // 关键：记账数据靠 LocalStorage
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            s.setSafeBrowsingEnabled(false);
        }

        // JS 桥接：把导出内容直接写进手机 Download；并接收提醒设置用于系统级排期
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void saveFile(final String filename, final String base64) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        String path = writeToDownloads(filename, base64);
                        if (path != null) {
                            Toast.makeText(MainActivity.this, "已保存到 " + path, Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(MainActivity.this, "保存失败，请检查存储空间", Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
            @JavascriptInterface
            public void setAddPageOpen(boolean open) {
                addPageOpen = open;
            }
            // 自定义时间选择面板的开关状态，用于返回键优先收起面板
            @JavascriptInterface
            public void setTimeSheetOpen(boolean open) {
                timeSheetOpen = open;
            }
            // ===== 应用内更新 =====
            /** 返回 "版本名|版本码"，如 1.1.0|2 */
            @JavascriptInterface
            public String getVersionInfo() {
                return updater != null ? updater.currentVersionFull() : "0.0.0|0";
            }
            /** 设置页「检查更新」按钮：无论是否有新版都把结果回传给页面 */
            @JavascriptInterface
            public void checkUpdate() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (updater != null) updater.check(false);
                    }
                });
            }
            /** 页面更新弹窗里点「安装更新」：下载并唤起系统安装 */
            @JavascriptInterface
            public void downloadUpdate() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (updater != null) updater.downloadUpdate();
                    }
                });
            }
            // 页面侧更新提醒设置（开关 / 时间）时调用，原生据此调度系统通知
            @JavascriptInterface
            public void setReminder(final String json) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            JSONObject o = new JSONObject(json);
                            boolean en = o.optBoolean("enabled", false);
                            String time = o.optString("time", "20:00");
                            reminderPrefs(MainActivity.this).edit()
                                    .putBoolean(KEY_EN, en)
                                    .putString(KEY_TM, time)
                                    .apply();
                            if (en) scheduleReminder(MainActivity.this, time);
                            else cancelReminder(MainActivity.this);
                        } catch (JSONException ignored) {}
                    }
                });
            }
        }, "Android");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                // 应用内没有外链，全部自己消化
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // 启动后延迟一会儿静默检查更新：有新版才弹窗，已是最新则完全不打扰
                view.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (updater != null) updater.check(true);
                    }
                }, 3000);
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            // 页面里的 confirm()（清空数据前的确认）必须实现，否则默认直接返回 false
            @Override
            public boolean onJsConfirm(WebView view, String url, String message, final android.webkit.JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) { result.confirm(); }
                        })
                        .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) { result.cancel(); }
                        })
                        .setOnCancelListener(new DialogInterface.OnCancelListener() {
                            @Override
                            public void onCancel(DialogInterface d) { result.cancel(); }
                        })
                        .show();
                return true;
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message, final android.webkit.JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setPositiveButton("好", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) { result.confirm(); }
                        })
                        .show();
                return true;
            }

            // 导入备份时要能选手机里的 JSON 文件
            @Override
            public boolean onShowFileChooser(WebView wv, ValueCallback<Uri[]> cb, FileChooserParams params) {
                filePathCallback = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(Intent.createChooser(i, "选择备份文件"), REQ_FILE);
                } catch (Exception e) {
                    filePathCallback = null;
                    Toast.makeText(MainActivity.this, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }
        });

        // 兜底：万一走的是普通 http 下载（非 blob），交给系统下载管理器
        web.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String ua, String cd, String mime, long len) {
                Toast.makeText(MainActivity.this, "正在保存…", Toast.LENGTH_SHORT).show();
            }
        });

        String html = readAsset("app.html");
        web.loadDataWithBaseURL(BASE_URL, html, "text/html", "UTF-8", null);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE) {
            if (filePathCallback == null) return;
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                results = new Uri[] { data.getData() };
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    /** 供 UpdateManager 把检查结果回传给页面 */
    void evalJs(String js) {
        if (web != null) {
            try { web.evaluateJavascript(js, null); } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onDestroy() {
        if (updater != null) updater.release();
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && web != null) {
            // 时间面板 / 日历面板开着时，返回键先收起面板
            if (timeSheetOpen) {
                timeSheetOpen = false;
                web.evaluateJavascript(
                    "(function(){ if (typeof closeTopSheet === 'function') closeTopSheet();" +
                    " else if (typeof closeTimeSheet === 'function') closeTimeSheet(false); })()", null);
                return true;
            }
            // 输入页开着时，返回键先收起它，而不是直接退出 App
            if (addPageOpen) {
                addPageOpen = false;
                web.evaluateJavascript("(function(){ if (typeof closeAddPage === 'function') closeAddPage(); })()", null);
                return true;
            }
            if (web.canGoBack()) {
                web.goBack();
                return true;
            }
            long now = System.currentTimeMillis();
            if (now - lastBackAt < 2000) {
                return super.onKeyDown(keyCode, event);
            }
            lastBackAt = now;
            Toast.makeText(this, "再按一次退出黑猫记账", Toast.LENGTH_SHORT).show();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /** 读取 assets 里的单文件应用 */
    private String readAsset(String name) {
        try {
            InputStream in = getAssets().open(name);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return out.toString("UTF-8");
        } catch (Exception e) {
            return "<html><body style='padding:24px;font-family:sans-serif'>应用资源加载失败</body></html>";
        }
    }

    /** 把 base64 内容写入手机 Download 目录，返回可读路径 */
    private String writeToDownloads(String filename, String base64) {
        try {
            byte[] data = Base64.decode(base64, Base64.DEFAULT);
            if (filename == null || filename.length() == 0) filename = "xiaozhangben_backup.txt";

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, filename);
                cv.put(MediaStore.Downloads.MIME_TYPE, guessMime(filename));
                cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                cv.put(MediaStore.Downloads.IS_PENDING, 1);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) return null;
                OutputStream os = getContentResolver().openOutputStream(uri);
                os.write(data);
                os.flush();
                os.close();
                cv.clear();
                cv.put(MediaStore.Downloads.IS_PENDING, 0);
                getContentResolver().update(uri, cv, null, null);
                return "Download/" + filename;
            } else {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists() && !dir.mkdirs()) return null;
                File f = new File(dir, filename);
                FileOutputStream fos = new FileOutputStream(f);
                fos.write(data);
                fos.flush();
                fos.close();
                return f.getAbsolutePath();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static String guessMime(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".csv")) return "text/csv";
        return "application/octet-stream";
    }
}
