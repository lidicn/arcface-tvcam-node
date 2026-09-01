package com.example.arcfaceandroid;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** 开机自启：设备启动后自动拉起人脸识别服务（需在系统设置里允许本应用“自启动/后台运行”）。 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Intent svc = new Intent(context, FaceServerService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }
            // 开机即武装看门狗，保证服务被杀后能自愈
            KeepAlive.schedule(context);
            AlarmWatchdog.schedule(context);
        }
    }
}
