package com.dshgo.assist;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

/**
 * v5 ASSIST 入口：彩色球唤醒动画 + 语音。
 *
 * 流程：
 *   1. 全屏半透明黑底（#CC000000）+ OrbView 彩色球 + 下方「正在聆听…」；
 *   2. 语音开关打开且本机有识别服务 → SpeechRecognizer(zh-CN) 收一句话；
 *   3. 拿到结果：文字切「正在交给 DSH…」，播放 0.3s 缩放+淡出，然后 ACTION_SEND
 *      发给 dsh-go 的 share.ShareActivity；
 *   4. 点屏幕任意处 / 12 秒超时 / 麦克风被拒 / 识别失败 / 语音开关关闭
 *      → 直接打开 dsh-go；
 *   5. Instrumentation 里「测试唤醒动画」按钮会带 EXTRA_ANIM_ONLY=true：
 *      只播 2.6 秒动画，不收音，然后打开 dsh-go。
 */
public class ListenActivity extends Activity implements RecognitionListener {

    static final String EXTRA_ANIM_ONLY = "anim_only";

    private static final int REQ_MIC = 44;
    private static final long TIMEOUT_MS = 12000;
    private static final long ANIM_ONLY_MS = 2600;
    private static final long INTENT_TEXT_DELAY_MS = 700;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private View content;
    private OrbView orb;
    private TextView statusText;

    private SpeechRecognizer recognizer;
    private boolean finished;

    private final Runnable timeout = new Runnable() {
        @Override
        public void run() {
            fallback("超时，直接打开 DSH 小V");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        BootTrace.mark("ListenActivity.onCreate enter");
        try {
            super.onCreate(savedInstanceState);
            setContentView(buildUi());
            orb.start();
            View decor = getWindow() == null ? null : getWindow().getDecorView();
            if (decor != null) {
                decor.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        fallback("已取消");
                    }
                });
            }
        } catch (Throwable t) {
            BootTrace.mark("ListenActivity.onCreate FAIL " + t);
            try {
                CrashHandler.writeManualReport(this, "ListenActivity", t);
            } catch (Throwable ignored) {
                // ignore
            }
            try {
                Dispatcher.openDsh(this);
            } catch (Throwable ignored) {
                // ignore
            }
            finishNow();
            return;
        }
        try {
            route(getIntent());
        } catch (Throwable t) {
            BootTrace.mark("ListenActivity.route FAIL " + t);
            animateOutAndOpen();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        destroyRecognizer();
        if (orb != null) {
            orb.stop();
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ UI --
    private View buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xCC000000);

        content = new LinearLayout(this);
        ((LinearLayout) content).setOrientation(LinearLayout.VERTICAL);
        ((LinearLayout) content).setGravity(Gravity.CENTER_HORIZONTAL);

        int orbSize = dp(260);
        orb = new OrbView(this);
        LinearLayout.LayoutParams orbParams =
                new LinearLayout.LayoutParams(orbSize, orbSize);
        ((LinearLayout) content).addView(orb, orbParams);

        statusText = new TextView(this);
        statusText.setText("正在聆听…");
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        statusText.setTextColor(Color.WHITE);
        statusText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        textParams.topMargin = dp(20);
        ((LinearLayout) content).addView(statusText, textParams);

        TextView hint = new TextView(this);
        hint.setText("点屏幕任意位置取消");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        hint.setTextColor(0x99FFFFFF);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = dp(8);
        ((LinearLayout) content).addView(hint, hintParams);

        FrameLayout.LayoutParams contentParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        contentParams.gravity = Gravity.CENTER;
        contentParams.topMargin = -dp(60);   // 球居中偏上
        root.addView(content, contentParams);

        root.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                fallback("已取消");
            }
        });
        return root;
    }

    // -------------------------------------------------------------- route ---
    private void route(Intent intent) {
        boolean animOnly = intent != null && intent.getBooleanExtra(EXTRA_ANIM_ONLY, false);
        if (animOnly) {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    animateOutAndOpen();
                }
            }, ANIM_ONLY_MS);
            return;
        }
        if (!isVoiceEnabled()) {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    animateOutAndOpen();
                }
            }, 600);
            return;
        }
        String text = VoiceEntry.textFromIntent(intent);
        if (text != null && text.trim().length() > 0) {
            final String payload = text;
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    deliver(payload);
                }
            }, INTENT_TEXT_DELAY_MS);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    fallback("本机没有语音识别服务");
                }
            }, 300);
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            try {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            } catch (Throwable t) {
                fallback("申请麦克风权限失败");
            }
            return;
        }
        startListening();
    }

    // ---------------------------------------------------------- recognise ---
    private void startListening() {
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(this);
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN");
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
            intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
            recognizer.startListening(intent);
            handler.postDelayed(timeout, TIMEOUT_MS);
        } catch (Throwable t) {
            fallback("语音识别启动失败");
        }
    }

    private void destroyRecognizer() {
        if (recognizer != null) {
            try {
                recognizer.destroy();
            } catch (Throwable ignored) {
                // ignore
            }
            recognizer = null;
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
        if (granted) {
            startListening();
        } else {
            fallback("没有麦克风权限");
        }
    }

    // ------------------------------------------------------------- result ---
    private void deliver(final String text) {
        if (finished) {
            return;
        }
        handler.removeCallbacks(timeout);
        if (statusText != null) {
            statusText.setText("正在交给 DSH…");
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                animateOut(new Runnable() {
                    @Override
                    public void run() {
                        Dispatcher.shareText(ListenActivity.this, text);
                        finishNow();
                    }
                });
            }
        }, 260);
    }

    private void fallback(String reason) {
        if (finished) {
            return;
        }
        handler.removeCallbacks(timeout);
        destroyRecognizer();
        animateOutAndOpen();
    }

    private void animateOutAndOpen() {
        if (finished) {
            return;
        }
        animateOut(new Runnable() {
            @Override
            public void run() {
                Dispatcher.openDsh(ListenActivity.this);
                finishNow();
            }
        });
    }

    /** 0.3s 缩放 + 淡出。 */
    private void animateOut(final Runnable after) {
        if (finished) {
            return;
        }
        finished = true;
        destroyRecognizer();
        if (content == null) {
            after.run();
            return;
        }
        content.animate()
                .alpha(0f)
                .scaleX(0.92f)
                .scaleY(0.92f)
                .setDuration(300)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        after.run();
                    }
                })
                .start();
    }

    private void finishNow() {
        try {
            finish();
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

    @Override
    public void onBackPressed() {
        fallback("已取消");
    }

    // ---------------------------------------------------- RecognitionListener --
    @Override
    public void onReadyForSpeech(Bundle params) {
        if (statusText != null) {
            statusText.setText("正在聆听…");
        }
    }

    @Override
    public void onBeginningOfSpeech() {
        if (statusText != null) {
            statusText.setText("正在听…");
        }
    }

    @Override
    public void onRmsChanged(float rmsdB) {
    }

    @Override
    public void onBufferReceived(byte[] buffer) {
    }

    @Override
    public void onEndOfSpeech() {
        if (statusText != null) {
            statusText.setText("识别中…");
        }
    }

    @Override
    public void onError(int error) {
        fallback("没听懂（" + error + "）");
    }

    @Override
    public void onResults(Bundle results) {
        ArrayList<String> list = results == null ? null
                : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        String text = (list != null && !list.isEmpty()) ? list.get(0) : null;
        if (text == null || text.trim().length() == 0) {
            fallback("没有识别到内容");
        } else {
            deliver(text);
        }
    }

    @Override
    public void onPartialResults(Bundle partialResults) {
    }

    @Override
    public void onEvent(int eventType, Bundle params) {
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}