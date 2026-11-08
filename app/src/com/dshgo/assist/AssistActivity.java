package com.dshgo.assist;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;

/**
 * v10：ASSIST 入口（不抢前台）。
 *
 * 只做三件事：
 *   1. 没有悬浮窗权限 → 起 ListenActivity 兜底（旧的整屏动画界面）；
 *   2. 需要语音但没麦权限 → 请求一次（回调后再起服务）；
 *   3. 起 OverlayService（前台服务，负责顶部胶囊 + 语音识别）然后立刻
 *      `finishAndRemoveTask()`（v19：连空任务一起移除，绝不把 App 主任务带到前台），
 *      theme = Theme.NoDisplay + overridePendingTransition(0,0)，无闪、前台 App 不动。
 */
public class AssistActivity extends Activity {

    private static final int REQ_MIC = 45;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        BootTrace.mark("AssistActivity.onCreate");
        try {
            super.onCreate(savedInstanceState);
            if (!Settings.canDrawOverlays(this)) {
                BootTrace.mark("AssistActivity: 没有悬浮窗权限 → ListenActivity 兜底");
                startFallback();
                return;
            }
            boolean voice = isVoiceEnabled();
            if (voice && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                try {
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
                } catch (Throwable t) {
                    startOverlay(false);
                    finishNoAnim();
                }
                return;
            }
            startOverlay(voice);
            finishNoAnim();
        } catch (Throwable t) {
            BootTrace.mark("AssistActivity.onCreate FAIL " + t);
            try {
                CrashHandler.writeManualReport(this, "AssistActivity", t);
            } catch (Throwable ignored) {
                // ignore
            }
            startFallback();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_MIC) {
            return;
        }
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        startOverlay(granted);
        finishNoAnim();
    }

    private void startOverlay(boolean voice) {
        try {
            Intent service = new Intent(this, OverlayService.class)
                    .setAction(OverlayService.ACTION_START)
                    .putExtra(OverlayService.EXTRA_VOICE, voice);
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(service);
            } else {
                startService(service);
            }
            BootTrace.mark("AssistActivity: OverlayService started (voice=" + voice + ")");
        } catch (Throwable t) {
            BootTrace.mark("AssistActivity startOverlay FAIL " + t);
        }
    }

    private void startFallback() {
        try {
            startActivity(new Intent(this, ListenActivity.class));
        } catch (Throwable t) {
            BootTrace.mark("AssistActivity startFallback FAIL " + t);
        }
        finishNoAnim();
    }

    private void finishNoAnim() {
        try {
            finishAndRemoveTask();
            overridePendingTransition(0, 0);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    private boolean isVoiceEnabled() {
        try {
            SharedPreferences sp = getSharedPreferences("assist", MODE_PRIVATE);
            return sp.getBoolean("voice_on_assist", true);
        } catch (Throwable ignored) {
            return true;
        }
    }
}
