package com.dshgo.assist;

import android.content.Intent;

/**
 * 语音入口的文本提取工具（v2）。
 *
 * 只负责「从系统助手 Intent 里取出用户已经给出的文本」，不直接申请
 * 麦克风权限；真正收音在 VoiceActivity（可选能力，可被用户关闭）。
 */
final class VoiceEntry {

    /** 自定义 extra：语音/助手链路把一句话塞进来时使用。 */
    static final String EXTRA_ASSIST_INPUT = "android.intent.extra.ASSIST_INPUT";

    private VoiceEntry() {
    }

    /**
     * 取出 Intent 携带的文本：
     *   ACTION_PROCESS_TEXT → EXTRA_PROCESS_TEXT；
     *   其他 → EXTRA_TEXT；
     *   自定义 → android.intent.extra.ASSIST_INPUT。
     *
     * @return 文本，取不到返回 null。
     */
    static String textFromIntent(Intent intent) {
        if (intent == null) {
            return null;
        }
        try {
            CharSequence text = null;
            if (Intent.ACTION_PROCESS_TEXT.equals(intent.getAction())) {
                text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
            }
            if (text == null) {
                text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            }
            if (text == null) {
                text = intent.getCharSequenceExtra(EXTRA_ASSIST_INPUT);
            }
            if (text == null) {
                text = intent.getStringExtra(EXTRA_ASSIST_INPUT);
            }
            return text == null ? null : text.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
