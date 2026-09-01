package com.example.arcfaceandroid;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

/**
 * 看门狗（AlarmManager 层）：比 WorkManager 15 分钟更频（默认 2 分钟），在进程被 OEM 杀掉后
 * 更快拉起前台服务。用 setRepeating + ELAPSED_REALTIME_WAKEUP（不依赖精确闹钟权限，
 * 对 targetSdk 34 / Android 12+ 友好）。每次触发由 AlarmWatchdogReceiver 重新自臂，保证持续生效。
 */
public final class AlarmWatchdog {
    private static final String TAG = "AlarmWatchdog";
    public static final String ACTION = "com.example.arcfaceandroid.ALARM_WATCHDOG";

    private AlarmWatchdog() {}

    public static void schedule(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent intent = new Intent(ctx, AlarmWatchdogReceiver.class);
            intent.setAction(ACTION);
            PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            am.setRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 5000,
                    Constants.WATCHDOG_ALARM_MS, pi);
            Log.i(TAG, "alarm watchdog scheduled (" + (Constants.WATCHDOG_ALARM_MS / 1000) + "s)");
        } catch (Throwable t) {
            Log.e(TAG, "schedule failed", t);
        }
    }

    public static void cancel(Context ctx) {
        try {
            Intent intent = new Intent(ctx, AlarmWatchdogReceiver.class);
            intent.setAction(ACTION);
            PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(pi);
        } catch (Throwable ignore) {}
    }
}
