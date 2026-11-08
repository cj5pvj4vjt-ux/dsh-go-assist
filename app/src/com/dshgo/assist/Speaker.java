package com.dshgo.assist;

import android.content.Context;
import android.speech.tts.TextToSpeech;

import java.util.Locale;

/**
 * v34：回复后自动朗读（系统 TTS 引擎）。
 *   - 初始化失败不影响其它功能；
 *   - 超长回复只读前 400 字（其余在卡片里看）；
 *   - 朗读可被「点卡片 / 点✕ / 收回」打断。
 */
final class Speaker {

    private TextToSpeech tts;
    private boolean ready;
    private boolean failed;
    private String pending;   // v35：引擎还在初始化时先攒着，就绪后补读

    void init(Context ctx) {
        if (tts != null || failed) {
            return;
        }
        try {
            final Context app = ctx.getApplicationContext();
            tts = new TextToSpeech(app, new TextToSpeech.OnInitListener() {
                @Override
                public void onInit(int status) {
                    try {
                        if (status == TextToSpeech.SUCCESS && tts != null) {
                            int r = tts.setLanguage(Locale.SIMPLIFIED_CHINESE);
                            if (r == TextToSpeech.LANG_MISSING_DATA
                                    || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                                tts.setLanguage(Locale.getDefault());
                            }
                            ready = true;
                            OverlayLog.mark("tts init ok engine=" + tts.getDefaultEngine());
                            if (pending != null) {
                                String p = pending;
                                pending = null;
                                OverlayLog.mark("tts flush pending len=" + p.length());
                                speak(p);
                            }
                        } else {
                            failed = true;
                            OverlayLog.mark("tts init FAIL status=" + status);
                        }
                    } catch (Throwable t) {
                        failed = true;
                        OverlayLog.mark("tts onInit FAIL " + t.getClass().getSimpleName());
                    }
                }
            });
        } catch (Throwable t) {
            failed = true;
            OverlayLog.mark("tts create FAIL " + t.getClass().getSimpleName());
        }
    }

    boolean isReady() {
        return ready;
    }

    /** 朗读文本；返回是否真的开始读。 */
    boolean speak(String text) {
        if (text == null || text.trim().isEmpty() || failed || tts == null) {
            return false;
        }
        try {
            String body = text.trim();
            if (!ready) {
                // v35：引擎初始化慢（第一次要拉起 TTS 服务），先挂起，onInit 后补读
                pending = body;
                OverlayLog.mark("tts pending len=" + body.length() + " (engine initializing)");
                return true;
            }
            boolean capped = body.length() > 400;
            if (capped) {
                body = body.substring(0, 400) + "……";
            }
            int r = tts.speak(body, TextToSpeech.QUEUE_FLUSH, null, "dsh-reply");
            OverlayLog.mark("tts speak len=" + body.length() + " rc=" + r
                    + (capped ? " capped=true" : ""));
            return r == TextToSpeech.SUCCESS;
        } catch (Throwable t) {
            OverlayLog.mark("tts speak FAIL " + t.getClass().getSimpleName());
            return false;
        }
    }

    void stop() {
        try {
            if (tts != null) {
                tts.stop();
                OverlayLog.mark("tts stop");
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }

    void shutdown() {
        try {
            if (tts != null) {
                tts.stop();
                tts.shutdown();
            }
        } catch (Throwable ignored) {
            // ignore
        }
        tts = null;
        ready = false;
    }
}
