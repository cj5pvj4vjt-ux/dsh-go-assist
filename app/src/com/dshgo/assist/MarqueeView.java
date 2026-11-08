package com.dshgo.assist;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * v10 跑马灯（克制版）：一条 2dp 细光带，像一道光从左到右划过，而不是一块彩虹。
 *
 * 动画参数（真机调参看这里）：
 *   - 视高 2dp（由布局给定），圆角 = 高/2，两端羽化到完全透明
 *   - 驱动：ValueAnimator(0→1)，LinearInterpolator，INFINITE
 *   - 周期：待机 2000ms；聆听中 900ms（v16）
 *   - 高光宽度：0.40 × 视宽；扫动范围 = 视宽 + 高光宽度（从左画外到右画外）
 *   - 四色（各降到约 70% 亮度）：
 *       蓝 #2E5DAB / 红 #A32F25 / 黄 #B08304 / 绿 #24763A
 *   - 渐变 6 个色标：透明 → 蓝 → 红 → 黄 → 绿 → 透明，
 *     位置 0 / .18 / .38 / .58 / .78 / 1（两端羽化）
 *   - 整体 alpha：待机 120，聆听 200（v21 白底降亮度）
 */
public class MarqueeView extends View {

    private static final int BLUE = 0xFF2E5DAB;
    private static final int RED = 0xFFA32F25;
    private static final int YELLOW = 0xFFB08304;
    private static final int GREEN = 0xFF24763A;

    private static final long PERIOD_IDLE_MS = 2000L;
    private static final long PERIOD_LISTENING_MS = 900L;
    private static final float BAND_RATIO = 0.40f;
    private static final int ALPHA_IDLE = 120;
    private static final int ALPHA_LISTENING = 200;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path band = new Path();

    private ValueAnimator animator;
    private float phase;
    private boolean listening;

    public MarqueeView(Context context) {
        super(context);
        paint.setStyle(Paint.Style.FILL);
    }

    /** 聆听中：周期 1100ms、亮度更高。 */
    public void setListening(boolean listening) {
        if (this.listening == listening) {
            return;
        }
        this.listening = listening;
        restartAnimator();
    }

    public boolean isListening() {
        return listening;
    }

    private void restartAnimator() {
        stopAnimator();
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(listening ? PERIOD_LISTENING_MS : PERIOD_IDLE_MS);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                phase = (float) animation.getAnimatedValue();
                invalidate();
            }
        });
        animator.start();
    }

    private void stopAnimator() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        restartAnimator();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopAnimator();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        try {
            drawBand(canvas);
        } catch (Throwable t) {
            BootTrace.mark("MarqueeView.onDraw FAIL(warn) " + t);
        }
    }

    private void drawBand(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float radius = h / 2f;
        band.reset();
        band.addRoundRect(0, 0, w, h, radius, radius, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(band);

        float bandWidth = w * BAND_RATIO;
        float offset = -bandWidth + phase * (w + bandWidth);
        LinearGradient gradient = new LinearGradient(0, 0, bandWidth, 0,
                new int[]{0x00FFFFFF, BLUE, RED, YELLOW, GREEN, 0x00FFFFFF},
                new float[]{0f, 0.18f, 0.38f, 0.58f, 0.78f, 1f},
                Shader.TileMode.CLAMP);
        Matrix matrix = new Matrix();
        matrix.setTranslate(offset, 0);
        gradient.setLocalMatrix(matrix);

        paint.setShader(gradient);
        paint.setAlpha(listening ? ALPHA_LISTENING : ALPHA_IDLE);
        canvas.drawRect(0, 0, w, h, paint);
        canvas.restore();
    }
}