package com.dshgo.assist;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * v5：开机自愈入口。
 *
 * 收到 BOOT_COMPLETED 后启动 HealService（前台服务）去等 Shizuku 提权；
 * 提权到位就幂等 apply + 重启 root 桥，用户重启手机后不需要再点任何东西。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        try {
            Intent service = new Intent(context, HealService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(service);
            } else {
                context.startService(service);
            }
        } catch (Throwable ignored) {
            // 某些 ROM 会限制开机启动前台服务，失败就算了（下次打开 App 还会自愈）
        }
    }
}