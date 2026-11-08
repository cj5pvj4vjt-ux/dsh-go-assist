package com.dshgo.assist;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * v17：全屏边缘辉光（参考 Apple Intelligence / Siri 的边缘流光）。
 *
 * 实现要点（不用模糊遮罩，避免软件层卡顿）：
 *   - 沿屏幕四边画「多层同心圆角矩形描边」，层数 14，每层向内缩进 depth/14，
 *     描边宽度 ≈ depth/14 + 1px，alpha 由外向内递减（1 → 0.05），
 *     叠在一起就形成约 48dp 的柔和渐隐。
 *   - 渐变色用全屏 SweepGradient（四色低饱和 #4285F4/#EA4335/#FBBC05/#34A853），
 *     每层用 Matrix 旋转 angle = phase*360 + i*6°，看起来像光在边框上缓慢流动/旋转。
 *   - 圆角与屏幕圆角一致：28dp。
 *   - 动画：ValueAnimator(0→1) INFINITE，周期 待机 4000ms / 聆听 2200ms；
 *     整体 alpha 待机 90 / 聆听 160。
 *
 * 全屏窗口本身 FLAG_NOT_TOUCHABLE，触摸全部透传给前台 App。
 */
public class EdgeGlowView extends View {

    static final int GLOW_DEPTH_DP = 48;
    static final int CORNER_DP = 28;
    static final int RING_COUNT = 14;
    static final long PERIOD_IDLE_MS = 4000L;
    static final long PERIOD_LISTENING_MS = 2200L;
    static final int ALPHA_IDLE = 90;
    static final int ALPHA_LISTENING = 160;

    private static final int BLUE = 0xFF4285F4;
    private static final int RED = 0xFFEA4335;
    private static final int YELLOW = 0xFFFBBC05;
    private static final int GREEN = 0xFF34A853;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final Matrix matrix = new Matrix();
    private ValueAnimator animator;
    private float phase;
    private boolean listening;

    public EdgeGlowView(Context context) {
        super(context);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
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
            drawGlow(canvas);
        } catch (Throwable t) {
            BootTrace.mark("EdgeGlowView.onDraw FAIL(warn) " + t);
        }
    }

    private void drawGlow(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        float depth = GLOW_DEPTH_DP * density;
        float corner = CORNER_DP * density;
        int maxAlpha = listening ? ALPHA_LISTENING : ALPHA_IDLE;
        float ringStep = depth / RING_COUNT;

        // 全屏 SweepGradient，四色环绕；每层旋转不同角度形成流动感
        for (int i = 0; i < RING_COUNT; i++) {
            float inset = i * ringStep;
            rect.set(inset, inset, w - inset, h - inset);
            path.reset();
            path.addRoundRect(rect, Math.max(1f, corner - inset * 0.35f),
                    Math.max(1f, corner - inset * 0.35f), Path.Direction.CW);

            float fade = 1f - (i / (float) (RING_COUNT - 1));     // 1 → 0
            int alpha = (int) (maxAlpha * fade * fade);
            if (alpha <= 1) {
                continue;
            }
            strokePaint.setStrokeWidth(ringStep + density);
            strokePaint.setAlpha(Math.min(255, alpha));
            int[] colors = {BLUE, RED, YELLOW, GREEN, BLUE};
            LinearGradient gradient = new LinearGradient(
                    0, 0, w, h, colors, null, Shader.TileMode.CLAMP);
            matrix.setRotate(phase * 360f + i * 6f, w / 2f, h / 2f);
            gradient.setLocalMatrix(matrix);
            strokePaint.setShader(gradient);
            canvas.drawPath(path, strokePaint);
        }
        strokePaint.setShader(null);
    }
}