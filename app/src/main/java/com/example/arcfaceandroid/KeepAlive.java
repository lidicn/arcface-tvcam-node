package com.example.arcfaceandroid;

import android.content.Context;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/**
 * 进程保活调度器（WorkManager 看门狗）。
 * 注册一个 15 分钟周期任务：若服务被 MIUI 杀掉（且用户没有主动停止），就重新拉起。
 * 周期任务由系统 JobScheduler 托管，比单纯依赖 START_STICKY 更抗 MIUI 的内存杀手。
 * enqueueUniquePeriodicWork + KEEP 保证幂等，不会重复入队。
 */
public final class KeepAlive {
    private static final String WORK_NAME = "arcface_keepalive";

    private KeepAlive() {}

    public static void schedule(Context ctx) {
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
                KeepAliveWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .setRequiresBatteryNotLow(false)
                        .setRequiresCharging(false)
                        .build())
                .build();
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, req);
    }

    public static void cancel(Context ctx) {
        WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME);
    }
}
