package com.example.arcfaceandroid;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * 看门狗广播：到点检查服务存活；被杀则拉起，并重新自臂定时。
 * 与 WorkManager(15min) / BootReceiver 互补，进一步缩短“被杀→复活”间隔。
 */
public class AlarmWatchdogReceiver extends BroadcastReceiver {
    private static final String TAG = "AlarmWatchdogReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        // 重新自臂，保证持续生效
        AlarmWatchdog.schedule(context);
        if (FaceServerService.userRequestedRunning && !FaceServerService.isRunning) {
            Log.w(TAG, "service dead, relaunch");
            Intent svc = new Intent(context, FaceServerService.class);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(svc);
                else context.startService(svc);
            } catch (Throwable ignore) {
                // 后台启动前台服务受限（Android 12+ 偶发）时忽略，等下次周期/用户打开 App
            }
        }
    }
}
