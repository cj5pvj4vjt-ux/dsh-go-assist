package com.dshgo.assist;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/**
 * 所有「把用户交回 DSH 小V」的动作集中在这里，每一步都带降级：
 * 显式分享组件 → 包名 ACTION_SEND → 任意 ACTION_SEND → launcher intent → deep link。
 */
final class Dispatcher {

    static final String TARGET_PACKAGE = "io.github.ccch1mneyyy.dsh_app";
    /** dsh-go 接收 ACTION_SEND / text/plain 的组件（Lead 在设备上查到的）。 */
    static final String TARGET_SHARE_ACTIVITY =
            "io.github.ccch1mneyyy.dsh_app.share.ShareActivity";
    static final String DEEP_LINK = "dshgo://open/chat";
    static final String MIME_TEXT = "text/plain";

    private Dispatcher() {
    }

    /** Service 等非 Activity 场景：用 Context 重载（自动加 NEW_TASK）。 */
    static boolean shareText(Context context, String text) {
        return shareTextInternal(context, text);
    }

    /** 把一句话发给 dsh-go。返回是否已成功发出。 */
    static boolean shareText(Activity activity, String text) {
        return shareTextInternal(activity, text);
    }

    private static boolean shareTextInternal(Context context, String text) {
        if (text == null || text.trim().length() == 0) {
            return false;
        }
        String payload = text.trim();

        // 1) 显式组件：dsh-go 的分享 Activity
        try {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType(MIME_TEXT);
            send.putExtra(Intent.EXTRA_TEXT, payload);
            send.setClassName(TARGET_PACKAGE, TARGET_SHARE_ACTIVITY);
            send.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(send);
            return true;
        } catch (Throwable ignored) {
            // 组件不存在/未导出 → 下一步
        }

        // 2) 限定包名的 ACTION_SEND
        try {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType(MIME_TEXT);
            send.putExtra(Intent.EXTRA_TEXT, payload);
            send.setPackage(TARGET_PACKAGE);
            send.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(send);
            return true;
        } catch (Throwable ignored) {
            // 没有匹配的 Activity → 下一步
        }

        // 3) 交给系统解析
        try {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType(MIME_TEXT);
            send.putExtra(Intent.EXTRA_TEXT, payload);
            send.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(send);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Context 版本（Service 用）。 */
    static boolean launchDsh(Context context) {
        return launchDshInternal(context);
    }

    /** 用 launcher intent 打开 dsh-go（.LauncherMistAlt）。 */
    static boolean launchDsh(Activity activity) {
        return launchDshInternal(activity);
    }

    private static boolean launchDshInternal(Context context) {
        try {
            Intent launch = context.getPackageManager()
                    .getLaunchIntentForPackage(TARGET_PACKAGE);
            if (launch == null) {
                return false;
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(launch);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Context 版本（Service 用）。 */
    static boolean openDeepLink(Context context) {
        return openDeepLinkInternal(context);
    }

    /** 最后的退路：dshgo://open/chat deep link。 */
    static boolean openDeepLink(Activity activity) {
        return openDeepLinkInternal(activity);
    }

    private static boolean openDeepLinkInternal(Context context) {
        try {
            Intent deep = new Intent(Intent.ACTION_VIEW, Uri.parse(DEEP_LINK));
            deep.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(deep);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 依次尝试 launcher / deep link，返回是否有动作发出。 */
    static boolean openDsh(Activity activity) {
        return launchDsh(activity) || openDeepLink(activity);
    }

    /** Context 版本（Service 用）。 */
    static boolean openDsh(Context context) {
        return launchDsh(context) || openDeepLink(context);
    }
}