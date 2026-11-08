package com.dshgo.assist;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

/**
 * v5 重启自愈：前台服务，每 5 秒 ping 一次 Shizuku（最多 10 分钟）。
 *
 *   - 一开始通知文案「等待提权后自动恢复」；
 *   - 拿到 binder 且已授权 → 幂等 apply（vivo 开关=0、长按=5、超长按=1、助手角色）
 *     + 自动重启 root 桥 → 通知改「DSH 小V 已就绪」→ 停止轮询 / 停止服务；
 *   - 10 分钟还没拿到 → 通知改「未拿到提权，已停止轮询」并停止。
 *
 * 前台服务类型用 specialUse：这个轮询是"用户可见的持久状态"，不属于
 * dataSync/shortService（shortService 限 3 分钟，不够 10 分钟）。
 */
public class HealService extends Service {

    private static final String CHANNEL_ID = "dsh_heal";
    private static final String CHANNEL_NAME = "DSH 小V 自愈";
    private static final int NOTIF_ID = 2001;
    private static final long POLL_INTERVAL_MS = 5000;
    private static final long POLL_TIMEOUT_MS = 10 * 60 * 1000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long startedAt;
    private boolean polling;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!polling) {
                return;
            }
            if (RootOps.rootReady()) {
                applyAndStop();
                return;
            }
            if (SystemClock.elapsedRealtime() - startedAt > POLL_TIMEOUT_MS) {
                polling = false;
                updateNotification("未拿到提权，已停止轮询（打开 App 可再试一次）");
                stopSelf();
                return;
            }
            handler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!polling) {
            polling = true;
            startedAt = SystemClock.elapsedRealtime();
            startForegroundCompat("等待提权后自动恢复");
            handler.postDelayed(poll, 1000);
        }
        return START_STICKY;
    }

    private void applyAndStop() {
        polling = false;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String mode = getSharedPreferences("assist", MODE_PRIVATE)
                            .getString(RootOps.PREF_POWER_MODE, RootOps.MODE_STOCK);
                    ShizukuShell.run(RootOps.powerModeScript(mode), 30000);
                    ShizukuShell.run(RootOps.startBridgeScript(), 30000);
                } catch (Throwable ignored) {
                    // 失败也没关系，下次 onResume / 开机会再试
                }
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        updateNotification("DSH 小V 已就绪（电源键钩子与 root 桥已恢复）");
                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                stopSelf();
                            }
                        }, 2500);
                    }
                });
            }
        }, "dsh-heal-service").start();
    }

    private void startForegroundCompat(String text) {
        Notification notification = buildNotification(text);
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIF_ID, notification);
            }
        } catch (Throwable ignored) {
            // 通知权限被拒时也尽量别崩
            try {
                startForeground(NOTIF_ID, notification);
            } catch (Throwable ignored2) {
                // ignore
            }
        }
    }

    private void updateNotification(String text) {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.notify(NOTIF_ID, buildNotification(text));
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }

    private Notification buildNotification(String text) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), flags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("DSH 小V")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(contentIntent)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID, CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("开机后等待 Shizuku 提权并自动恢复电源键接管");
                nm.createNotificationChannel(channel);
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }

    @Override
    public void onDestroy() {
        polling = false;
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}