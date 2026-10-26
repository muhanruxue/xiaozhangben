package com.muhan.xiaozhangben;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 应用内更新：查询 GitHub Releases 最新版本 → 下载 APK → 拉起系统安装器。
 *
 * 版本清单来自：https://api.github.com/repos/muhanruxue/xiaozhangben/releases/latest
 * 用 release 的 tag_name（形如 v1.1.0）与当前 versionName 做三段数字比较。
 */
public class UpdateManager {

    private static final String TAG = "XZB-Update";
    static final String REPO_API = "https://api.github.com/repos/muhanruxue/xiaozhangben/releases/latest";
    private static final String RELEASES_PAGE = "https://github.com/muhanruxue/xiaozhangben/releases/latest";
    static final String APK_DIR = "updates";

    /** 检查结果（供 UI 使用） */
    public static class Info {
        public String version = "";     // 形如 1.1.0
        public String title = "";       // release 标题
        public String notes = "";       // 更新说明
        public String apkUrl = "";      // 下载地址
        public long size = 0;           // 字节
        public boolean hasUpdate = false;
    }

    private final Activity act;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private long downloadId = -1;
    private File pendingApk = null;
    private BroadcastReceiver dlReceiver;
    /** 是否已有一笔下载在进行中：防止重复点击再下一份 */
    private volatile boolean downloading = false;
    /** 轮询兜底的 token，非 0 表示正在轮询 */
    private int pollSeq = 0;

    public UpdateManager(Activity act) {
        this.act = act;
    }

    // ===== 版本信息 =====

    /** 当前安装的版本名，形如 1.1.0 */
    public String currentVersion() {
        try {
            return act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "0.0.0";
        }
    }

    /** "版本名|版本码"，给页面展示用 */
    public String currentVersionFull() {
        try {
            android.content.pm.PackageInfo pi =
                act.getPackageManager().getPackageInfo(act.getPackageName(), 0);
            String code = Build.VERSION.SDK_INT >= 28
                ? String.valueOf(pi.getLongVersionCode()) : String.valueOf(pi.versionCode);
            return pi.versionName + "|" + code;
        } catch (Exception e) {
            return "0.0.0|0";
        }
    }

    /** 比较语义化版本：a > b 返回正数 */
    static int compareVersion(String a, String b) {
        int[] x = parse(a), y = parse(b);
        for (int i = 0; i < 3; i++) {
            if (x[i] != y[i]) return x[i] - y[i];
        }
        return 0;
    }

    private static int[] parse(String v) {
        int[] r = new int[3];
        if (v == null) return r;
        String s = v.trim();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        String[] p = s.split("\\.");
        for (int i = 0; i < 3 && i < p.length; i++) {
            try { r[i] = Integer.parseInt(p[i].replaceAll("[^0-9]", "")); } catch (Exception e) { r[i] = 0; }
        }
        return r;
    }

    // ===== 检查更新 =====

    /**
     * @param silent true = 启动时的后台静默检查（无新版不打扰，有新版才弹窗）
     *                false = 用户在设置里手动点的（无论结果都回调页面展示）
     */
    public void check(final boolean silent) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String json = httpGet(REPO_API);
                    final Info info = parseRelease(json);
                    info.hasUpdate = compareVersion(info.version, currentVersion()) > 0;
                    ui.post(new Runnable() {
                        @Override
                        public void run() { onChecked(info, silent); }
                    });
                } catch (final Exception e) {
                    Log.w(TAG, "check failed", e);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!silent) {
                                report("{\"status\":\"error\",\"message\":" +
                                    JSONObject.quote(safeMsg(e)) + "}");
                                Toast.makeText(act, "检查更新失败：" + safeMsg(e), Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
                }
            }
        }).start();
    }

    private static String safeMsg(Exception e) {
        String m = e.getMessage();
        if (m == null || m.length() == 0) m = e.getClass().getSimpleName();
        return m.length() > 60 ? m.substring(0, 60) : m;
    }

    static Info parseRelease(String json) throws Exception {
        JSONObject o = new JSONObject(json);
        Info i = new Info();
        i.version = o.optString("tag_name", "");
        i.title = o.optString("name", "");
        i.notes = o.optString("body", "");
        JSONArray assets = o.optJSONArray("assets");
        if (assets != null && assets.length() > 0) {
            for (int k = 0; k < assets.length(); k++) {
                JSONObject a = assets.getJSONObject(k);
                String n = a.optString("name", "");
                if (n.toLowerCase().endsWith(".apk")) {
                    i.apkUrl = a.optString("browser_download_url", "");
                    i.size = a.optLong("size", 0);
                    break;
                }
            }
        }
        if (i.apkUrl.length() == 0) throw new Exception("release 里没有 apk 附件");
        return i;
    }

    private void onChecked(Info info, boolean silent) {
        if (!info.hasUpdate) {
            if (silent) return; // 静默检查：已是最新就什么都不做
            report("{\"status\":\"latest\",\"version\":" + JSONObject.quote(info.version) + "}");
            Toast.makeText(act, "已经是最新版本", Toast.LENGTH_SHORT).show();
            return;
        }
        // 有新版：回调页面（带上公告正文，页面用富文本卡片展示）+ 弹原生对话框
        String notes = info.notes == null ? "" : info.notes;
        if (notes.length() > 3000) notes = notes.substring(0, 3000);
        report("{\"status\":\"found\",\"version\":" + JSONObject.quote(info.version) +
            ",\"size\":" + info.size + ",\"notes\":" + JSONObject.quote(notes) + "}");
        showUpdateDialog(info);
    }

    /** 把结果回传给页面（页面需实现 window.onUpdateResult(json)） */
    private void report(String json) {
        String js = "(function(){ if (typeof window.onUpdateResult === 'function') window.onUpdateResult(" + json + "); })()";
        try {
            MainActivity act2 = (MainActivity) act;
            act2.evalJs(js);
        } catch (Exception e) {
            Log.w(TAG, "report failed", e);
        }
    }

    private void showUpdateDialog(final Info info) {
        if (act.isFinishing()) return;
        StringBuilder msg = new StringBuilder();
        msg.append("当前版本：").append(currentVersion()).append("\n");
        msg.append("最新版本：").append(info.version);
        if (info.size > 0) msg.append("\n安装包大小：").append(humanSize(info.size));
        String plain = mdToPlain(info.notes);
        if (plain.length() > 0) msg.append("\n\n更新内容\n").append(plain);
        new AlertDialog.Builder(act)
            .setTitle("发现新版本")
            .setMessage(msg.toString())
            .setPositiveButton("下载更新", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) { startDownload(info); }
            })
            .setNeutralButton("去网页看看", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    try { act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_PAGE))); }
                    catch (Exception e) { Toast.makeText(act, "无法打开浏览器", Toast.LENGTH_SHORT).show(); }
                }
            })
            .setNegativeButton("以后再说", null)
            .show();
    }

    /**
     * 把 Release 里的 Markdown 公告转成适合原生对话框的纯文本：
     * 去掉 # 标题符号、** 粗体星号、- 列表符号统一成「·」，压缩多余空行。
     * 否则弹窗里会显示成「## 本次更新 / ### 1. xxx / **安装提示**」这种原始标记。
     */
    static String mdToPlain(String md) {
        if (md == null) return "";
        StringBuilder sb = new StringBuilder();
        String[] lines = md.replace("\r\n", "\n").split("\n");
        for (String line : lines) {
            String t = line.trim();
            if (t.length() == 0) { sb.append("\n"); continue; }
            if (t.matches("^[-*_]{3,}$")) { sb.append("\n"); continue; }
            t = t.replaceAll("^#{1,6}\\s*", "");            // 标题
            t = t.replaceAll("^>\\s?", "");                  // 引用
            t = t.replaceAll("^[-*+]\\s+", "· ");            // 无序列表
            t = t.replaceAll("\\*\\*(.+?)\\*\\*", "$1");     // 粗体
            t = t.replaceAll("`([^`]+)`", "$1");             // 行内代码
            t = t.replaceAll("\\[([^\\]]+)\\]\\([^)]+\\)", "$1"); // 链接取文字
            sb.append(t).append("\n");
        }
        String out = sb.toString().replaceAll("\n{3,}", "\n\n").trim();
        return out;
    }

    static String humanSize(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format("%.0f KB", b / 1024.0);
        return String.format("%.1f MB", b / 1024.0 / 1024.0);
    }

    // ===== 下载 & 安装 =====

    private void startDownload(Info info) {
        if (Build.VERSION.SDK_INT >= 26) {
            PackageManager pm = act.getPackageManager();
            if (!pm.canRequestPackageInstalls()) {
                Toast.makeText(act, "请先允许「安装未知应用」权限，再回来下载", Toast.LENGTH_LONG).show();
                try {
                    act.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + act.getPackageName())));
                } catch (Exception e) {
                    act.startActivity(new Intent(Settings.ACTION_SETTINGS));
                }
                return;
            }
        }
        // 已有一笔在下载：直接复用，避免重复点击又下一份
        if (downloading) {
            Toast.makeText(act, "正在下载中，请稍候…", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            // 下载到 App 私有目录，无需存储权限
            String name = "heimaojizhang-" + info.version + ".apk";
            File dir = new File(act.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), APK_DIR);
            if (!dir.exists() && !dir.mkdirs()) throw new Exception("无法创建下载目录");
            File out = new File(dir, name);
            if (out.exists()) out.delete();
            pendingApk = out;

            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(info.apkUrl));
            req.setTitle("黑猫记账 " + info.version);
            req.setDescription("下载完成后会自动提示安装");
            req.setMimeType("application/vnd.android.package-archive");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalFilesDir(act, Environment.DIRECTORY_DOWNLOADS, APK_DIR + "/" + name);
            req.allowScanningByMediaScanner();

            DownloadManager dm = (DownloadManager) act.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) throw new Exception("下载服务不可用");

            // 先装监听再入队：这样注册失败也不会留下一笔"没人管"的下载
            boolean listen = registerDownloadReceiver();

            downloadId = dm.enqueue(req);
            downloading = true;
            if (!listen) startPolling(downloadId); // 广播注册失败时降级为轮询

            Toast.makeText(act, "已开始下载，完成后会提示安装", Toast.LENGTH_SHORT).show();
            report("{\"status\":\"downloading\"}");
        } catch (Exception e) {
            Log.w(TAG, "download start failed", e);
            downloading = false;
            Toast.makeText(act, "下载失败：" + safeMsg(e), Toast.LENGTH_LONG).show();
            report("{\"status\":\"error\",\"message\":" + JSONObject.quote(safeMsg(e)) + "}");
        }
    }

    /**
     * 注册下载完成广播。
     * Android 14（targetSdk 34）起，registerReceiver 必须显式声明 RECEIVER_EXPORTED /
     * RECEIVER_NOT_EXPORTED，否则抛 SecurityException：
     *   "One of RECEIVER_EXPORTED or RECEIVER_NOT_EXPORTED should be specified..."
     * ACTION_DOWNLOAD_COMPLETE 由系统 DownloadManager 发出，所以用 RECEIVER_EXPORTED。
     *
     * @return 是否注册成功；失败时调用方改用轮询兜底
     */
    private boolean registerDownloadReceiver() {
        if (dlReceiver != null) return true;
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id != downloadId) return;
                installDownloaded(id);
            }
        };
        IntentFilter f = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                act.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
            } else {
                act.registerReceiver(r, f);
            }
            dlReceiver = r;
            return true;
        } catch (Exception e) {
            Log.w(TAG, "registerReceiver failed, fallback to polling", e);
            return false;
        }
    }

    /** 兜底：轮询下载状态（广播注册被系统拒绝时使用） */
    private void startPolling(final long id) {
        final int seq = ++pollSeq;
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (seq != pollSeq || !downloading) return;
                DownloadManager dm = (DownloadManager) act.getSystemService(Context.DOWNLOAD_SERVICE);
                if (dm == null) { finishDownload(); return; }
                Cursor c = null;
                try {
                    c = dm.query(new DownloadManager.Query().setFilterById(id));
                    if (c != null && c.moveToFirst()) {
                        int st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                        if (st == DownloadManager.STATUS_SUCCESSFUL || st == DownloadManager.STATUS_FAILED) {
                            installDownloaded(id);
                            return;
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "poll failed", e);
                } finally {
                    if (c != null) c.close();
                }
                ui.postDelayed(this, 800);
            }
        }, 800);
    }

    /** 收尾：复位下载中标记并停止轮询 */
    private void finishDownload() {
        downloading = false;
        pollSeq++;
        downloadId = -1;
    }

    private void installDownloaded(long id) {
        // 广播与轮询可能同时到达，只处理第一次
        if (!downloading) return;
        try {
            DownloadManager dm = (DownloadManager) act.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) return;
            Cursor c = dm.query(new DownloadManager.Query().setFilterById(id));
            if (c == null) return;
            try {
                if (!c.moveToFirst()) return;
                int st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                if (st != DownloadManager.STATUS_SUCCESSFUL) {
                    Toast.makeText(act, "下载未完成或已失败", Toast.LENGTH_SHORT).show();
                    report("{\"status\":\"error\",\"message\":\"下载失败\"}");
                    return;
                }
                String uriStr = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI));
                File f = pendingApk;
                if (uriStr != null && uriStr.startsWith("file://")) {
                    f = new File(Uri.parse(uriStr).getPath());
                }
                if (f == null || !f.exists()) {
                    Toast.makeText(act, "安装包不存在，请重试", Toast.LENGTH_SHORT).show();
                    return;
                }
                report("{\"status\":\"downloaded\"}");
                installApk(f);
            } finally {
                c.close();
            }
        } catch (Exception e) {
            Log.w(TAG, "install failed", e);
            Toast.makeText(act, "安装失败：" + safeMsg(e), Toast.LENGTH_LONG).show();
        } finally {
            unregister();
            finishDownload();
        }
    }

    private void installApk(File f) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(ApkProvider.uriFor(f), "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "install intent failed", e);
            Toast.makeText(act, "无法打开安装界面，请到下载目录手动安装", Toast.LENGTH_LONG).show();
        }
    }

    private void unregister() {
        if (dlReceiver != null) {
            try { act.unregisterReceiver(dlReceiver); } catch (Exception ignored) {}
            dlReceiver = null;
        }
    }

    /** 退出时清理，避免泄漏 */
    public void release() {
        unregister();
        finishDownload();
        ui.removeCallbacksAndMessages(null);
    }

    // ===== 网络 =====

    static String httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            URL u = new URL(urlStr);
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod("GET");
            // GitHub API 要求带 User-Agent，否则 403
            conn.setRequestProperty("User-Agent", "XiaoZhangBen-Android");
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
            in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
            if (conn != null) conn.disconnect();
        }
    }
}
