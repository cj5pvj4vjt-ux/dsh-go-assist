package com.dshgo.assist;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * v10：胶囊左侧的 3 根细波形竖条（Siri 感的音量柱）。
 *
 * 参数（真机调参看这里）：
 *   - 3 根竖条，宽 2dp、圆角 1dp、间距 3dp；总宽 12dp、画高 18dp
 *   - 高度 8dp ~ 18dp（base 8dp + 能量/正弦，最大夹到 18dp），相位彼此错开 1/3 周期
 *   - 周期：待机 1400ms；聆听中 700ms（{@link #setListening(boolean)} 切换）
 *   - 颜色 #E6FFFFFF（白 90%）
 */
public class WaveBarsView extends View {

    private static final int BAR_COLOR = 0xFF1A1A1E;
    private static final int BAR_WIDTH_DP = 2;
    private static final int BAR_GAP_DP = 3;
    private static final float BASE_HEIGHT_DP = 8f;
    private static final float AMPLITUDE_DP = 2f;
    private static final long PERIOD_IDLE_MS = 1400L;
    private static final long PERIOD_LISTENING_MS = 700L;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private ValueAnimator animator;
    private float phase;
    private boolean listening;
    /** v15：由真实 RMS 驱动的能量（0~1），说话时波形条真的会变高。 */
    private float level;

    public WaveBarsView(Context context) {
        super(context);
        paint.setColor(BAR_COLOR);
        paint.setStyle(Paint.Style.FILL);
    }

    public void setListening(boolean listening) {
        if (this.listening == listening) {
            return;
        }
        this.listening = listening;
        restartAnimator();
    }

    public void start() {
        restartAnimator();
    }

    /**
     * v15：喂入一帧 RMS（int16 幅度）。做「快攻慢放」：
     * 立刻抬到当前能量，之后由动画帧缓慢衰减，于是说话时能看到明显起伏。
     */
    public void setLevel(int rms) {
        float v = Math.min(1f, Math.max(0f, rms / 3000f));
        if (v > level) {
            level = v;
        }
        postInvalidateOnAnimation();
    }

    public void resetLevel() {
        level = 0f;
    }

    public void stop() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    private void restartAnimator() {
        stop();
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(listening ? PERIOD_LISTENING_MS : PERIOD_IDLE_MS);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                phase = (float) animation.getAnimatedValue();
                level *= 0.92f;               // 慢放：没有新能量就回落
                invalidate();
            }
        });
        animator.start();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        restartAnimator();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        try {
            int h = getHeight();
            float density = getResources().getDisplayMetrics().density;
            float barWidth = BAR_WIDTH_DP * density;
            float gap = BAR_GAP_DP * density;
            float centerY = h / 2f;
            for (int i = 0; i < 3; i++) {
                double sine = Math.sin(2 * Math.PI * (phase + i / 3.0));
                // v15：高度 = 基础 + 真实能量驱动 + 一点点正弦呼吸
                float energy = 8f * Math.min(1f, Math.max(0f, level));
                float barHeight = (BASE_HEIGHT_DP + energy
                        + 2f * (float) sine) * density;
                float minH = 8 * density;
                float maxH = 18 * density;
                if (barHeight < minH) {
                    barHeight = minH;
                } else if (barHeight > maxH) {
                    barHeight = maxH;
                }
                float left = i * (barWidth + gap);
                float top = centerY - barHeight / 2f;
                rect.set(left, top, left + barWidth, top + barHeight);
                canvas.drawRoundRect(rect, barWidth / 2f, barWidth / 2f, paint);
            }
        } catch (Throwable t) {
            BootTrace.mark("WaveBarsView.onDraw FAIL(warn) " + t);
        }
    }
}