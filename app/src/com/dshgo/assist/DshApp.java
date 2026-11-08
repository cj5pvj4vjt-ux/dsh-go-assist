package com.dshgo.assist;

import android.app.Application;
import android.content.Context;

/**
 * v6：Application 子类。
 *
 * 只做两件事：启动轨迹打点 + 安装全局崩溃处理器。所有东西都包在 try/catch 里，
 * 保证它自己不会成为崩溃点。
 */
public class DshApp extends Application {

    @Override
    protected void attachBaseContext(Context base) {
        BootTrace.mark("DshApp.attachBaseContext");
        try {
            super.attachBaseContext(base);
        } catch (Throwable t) {
            BootTrace.mark("attachBaseContext FAIL " + t);
            throw t instanceof RuntimeException ? (RuntimeException) t : new RuntimeException(t);
        }
        // 越早装越好：ContentProvider 的 onCreate 在 Application.onCreate 之前，
        // 在 attachBaseContext 里装好，provider 阶段崩了也能留下报告。
        try {
            CrashHandler.install(this);
            BootTrace.mark("CrashHandler installed (attachBaseContext)");
        } catch (Throwable t) {
            BootTrace.mark("CrashHandler early install FAIL " + t);
        }
    }

    @Override
    public void onCreate() {
        BootTrace.mark("DshApp.onCreate enter");
        try {
            super.onCreate();
        } catch (Throwable t) {
            BootTrace.mark("super.onCreate FAIL " + t);
        }
        migratePrefs();
        try {
            CrashHandler.install(this);
            BootTrace.mark("CrashHandler installed");
        } catch (Throwable t) {
            BootTrace.mark("CrashHandler install FAIL " + t);
        }
        BootTrace.mark("DshApp.onCreate exit");
    }

    /**
     * v20：升级迁移。旧版本把 reply_via_dsh 写成过 true（用户看到「整屏弹出 DSH」），
     * 用 prefs_version 强制清一次：缺失或 < 2 → reply_via_dsh=false。
     */
    private void migratePrefs() {
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences("assist", MODE_PRIVATE);
            int version = sp.getInt("prefs_version", 0);
            if (version < 2) {
                sp.edit().putBoolean("reply_via_dsh", false)
                        .putInt("prefs_version", 2).apply();
                BootTrace.mark("prefs migrated v" + version + " -> 2 (reply_via_dsh=false)");
            }
        } catch (Throwable t) {
            BootTrace.mark("migratePrefs FAIL " + t);
        }
    }
}