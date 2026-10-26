package com.muhan.xiaozhangben;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 开机完成 / 应用更新后，读取已保存的提醒设置并重新排期，
 * 这样即使重启过、每日提醒依然会在设定时间触发。
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        String a = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            MainActivity.scheduleFromPrefs(c);
        }
    }
}
