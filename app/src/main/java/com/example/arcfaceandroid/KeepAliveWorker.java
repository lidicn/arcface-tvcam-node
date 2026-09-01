package com.example.arcfaceandroid;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/**
 * WorkManager 周期看门狗：每 15 分钟检查一次服务是否存活。
 * 注意：Android 12+ 限制后台启动前台服务；Mix 3 最高 Android 11，不受影响。
 * 即便高版本受限抛异常，也仅跳过本次，下次周期或用户打开 App 时再恢复。
 */
public class KeepAliveWorker extends Worker {
    public KeepAliveWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        // 用户主动停止时不拉起；被系统/MIUI 杀掉才拉起
        if (FaceServerService.userRequestedRunning && !FaceServerService.isRunning) {
            Intent svc = new Intent(getApplicationContext(), FaceServerService.class);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    getApplicationContext().startForegroundService(svc);
                } else {
                    getApplicationContext().startService(svc);
                }
            } catch (Exception e) {
                // 后台启动前台服务受限时忽略，等下次周期或用户打开 App
            }
        }
        return Result.success();
    }
}
