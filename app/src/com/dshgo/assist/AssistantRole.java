package com.dshgo.assist;

import android.app.Activity;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;

/**
 * 「默认助手角色」自助申请（API 29+ 的 RoleManager）。
 *
 * 本机 vivo 上的 Claude / ChatGPT 就是靠 ASSIST Activity + 自己申请角色成为
 * 系统助手的；不需要 root，也不需要改系统设置。
 */
final class AssistantRole {

    static final String ROLE = "android.app.role.ASSISTANT";

    private AssistantRole() {
    }

    static boolean supported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;   // API 29
    }

    private static RoleManager roleManager(Activity activity) {
        try {
            return (RoleManager) activity.getSystemService(Context.ROLE_SERVICE);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static boolean isAvailable(Activity activity) {
        try {
            RoleManager rm = roleManager(activity);
            return rm != null && rm.isRoleAvailable(ROLE);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** RoleManager.isRoleHeld(ROLE_ASSISTANT)。 */
    static boolean isRoleHeld(Activity activity) {
        try {
            RoleManager rm = roleManager(activity);
            return rm != null && rm.isRoleHeld(ROLE);
        } catch (Throwable ignored) {
            return false;
        }
    }

    static Intent requestIntent(Activity activity) {
        try {
            RoleManager rm = roleManager(activity);
            return rm == null ? null : rm.createRequestRoleIntent(ROLE);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 低于 API 29 / 角色不可用时的退路：默认应用设置页。 */
    static boolean openDefaultAppsSettings(Activity activity) {
        try {
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS));
            return true;
        } catch (Throwable ignored) {
            try {
                activity.startActivity(new Intent(Settings.ACTION_SETTINGS));
                return true;
            } catch (Throwable ignored2) {
                return false;
            }
        }
    }
}