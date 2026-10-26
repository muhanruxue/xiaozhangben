package com.muhan.xiaozhangben;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 每日记账提醒的触发者：被 AlarmManager 在设定时间唤醒后，弹出系统通知。
 * 弹完之后自动把提醒排到第二天，从而实现「每天一次」的重复。
 */
public class ReminderReceiver extends BroadcastReceiver {

    static final String CHANNEL_ID = "xzb_remind";

    @Override
    public void onReceive(Context c, Intent intent) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        // 点击通知 -> 打开黑猫记账
        Intent open = new Intent(c, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                c, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            n = new Notification.Builder(c, CHANNEL_ID)
                    .setContentTitle("黑猫记账 · 记账提醒")
                    .setContentText("该记账啦～ 今天还没记录哦")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setDefaults(Notification.DEFAULT_ALL)
                    .build();
        } else {
            n = new Notification.Builder(c)
                    .setContentTitle("黑猫记账 · 记账提醒")
                    .setContentText("该记账啦～ 今天还没记录哦")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setDefaults(Notification.DEFAULT_ALL)
                    .build();
        }
        nm.notify(1, n);

        // 排到第二天，保证每天重复
        MainActivity.scheduleFromPrefs(c);
    }
}
