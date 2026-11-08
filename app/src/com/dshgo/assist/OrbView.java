package com.dshgo.assist;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * v5 唤醒动画：4 个 Google 色柔光圆公转 + 呼吸 + 中心融合，模仿 Google Assistant
 * 的彩色球，但降低饱和/透明度，耐看、不刺眼。全部 Canvas + ValueAnimator。
 *
 * 动画参数（写死在这里，方便按真机观感调）：
 *   - 驱动：ValueAnimator 0→1，duration=3000ms，LinearInterpolator，INFINITE
 *   - 球体基准半径 R = min(w,h) * 0.16
 *   - 公转周期：蓝 6.0s / 红 8.0s / 黄 7.0s / 绿 9.0s（方向交替）
 *   - 公转半径：0.95R ~ 1.30R（四颗错开，形成融合感）
 *   - 核心半径：0.50R ~ 0.62R，外发光半径 = 核心的 2.0 倍
 *   - 呼吸：缩放 0.92 ~ 1.08，周期 2.4s ~ 3.3s
 *   - 透明度：核心 alpha 0.70 ~ 0.92，外发光 0.18 ~ 0.32
 *   - 中心融合：径向渐变（四色混合的白）半径 1.5R，alpha 0.42→0
 */
public class OrbView extends View {

    private static final int BLUE = 0xFF4285F4;
    private static final int RED = 0xFFEA4335;
    private static final int YELLOW = 0xFFFBBC05;
    private static final int GREEN = 0xFF34A853;

    private static final int[] COLORS = {BLUE, RED, YELLOW, GREEN};
    /** 公转周期（秒）。 */
    private static final float[] ORBIT_PERIOD_S = {6.0f, 8.0f, 7.0f, 9.0f};
    /** 公转方向（交替，避免四颗同步转）。 */
    private static final float[] ORBIT_DIR = {1f, -1f, 1f, -1f};
    /** 公转半径（相对 R）。 */
    private static final float[] ORBIT_RADIUS = {1.02f, 1.24f, 1.12f, 1.30f};
    /** 初始相位（0..1 圈）。 */
    private static final float[] PHASE = {0.00f, 0.27f, 0.55f, 0.78f};
    /** 核心半径（相对 R）。 */
    private static final float[] CORE_RADIUS = {0.58f, 0.52f, 0.55f, 0.50f};
    /** 呼吸周期（秒）。 */
    private static final float[] BREATH_PERIOD_S = {2.4f, 3.0f, 2.7f, 3.3f};

    private static final long CYCLE_MS = 3000L;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] shaderStroke = new float[2];

    private ValueAnimator animator;
    private float t;            // 0..1，3 秒一个循环
    private boolean running;

    public OrbView(Context context) {
        super(context);
        init();
    }

    public OrbView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        paint.setStyle(Paint.Style.FILL);
    }

    public void start() {
        if (animator != null && animator.isRunning()) {
            return;
        }
        running = true;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(CYCLE_MS);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                t = (float) animation.getAnimatedValue();
                invalidate();
            }
        });
        animator.start();
    }

    public void stop() {
        running = false;
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (running) {
            start();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        try {
            drawOrbs(canvas);
        } catch (Throwable t) {
            BootTrace.mark("OrbView.onDraw FAIL(warn) " + t);
        }
    }

    private void drawOrbs(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float cx = w / 2f;
        float cy = h / 2f;
        float R = Math.min(w, h) * 0.16f;
        float cycleT = t;
        float seconds = cycleT * (CYCLE_MS / 1000f);

        // 1) 中心融合光团：四色混出的暖白，径向渐变
        float fusionRadius = R * 1.5f;
        paint.setShader(new RadialGradient(cx, cy, fusionRadius,
                new int[]{0x6BFFFFFF, 0x30FFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, fusionRadius, paint);
        paint.setShader(null);

        // 2) 四颗柔光球
        for (int i = 0; i < COLORS.length; i++) {
            float period = ORBIT_PERIOD_S[i];
            float orbitAngle = (float) (2 * Math.PI * (seconds / period * ORBIT_DIR[i] + PHASE[i]));
            float breathPeriod = BREATH_PERIOD_S[i];
            float breath = (float) Math.sin(2 * Math.PI * (seconds / breathPeriod + i * 0.21));
            float scale = 1f + 0.08f * breath;

            float orbitDist = R * ORBIT_RADIUS[i];
            float ox = cx + (float) Math.cos(orbitAngle) * orbitDist;
            float oy = cy + (float) Math.sin(orbitAngle) * orbitDist;

            float coreR = R * CORE_RADIUS[i] * scale;
            float glowR = coreR * 2.0f;
            int color = COLORS[i];

            int glowCenter = withAlpha(color, 0.20f + 0.08f * (breath * 0.5f + 0.5f));
            int coreCenter = withAlpha(color, 0.78f + 0.14f * (breath * 0.5f + 0.5f));
            float[] stops = {0f, 0.45f, 1f};

            shaderStroke[0] = 0f;
            shaderStroke[1] = 0f;
            paint.setShader(new RadialGradient(ox, oy, glowR,
                    new int[]{glowCenter, withAlpha(color, 0.10f), 0x00000000},
                    stops, Shader.TileMode.CLAMP));
            canvas.drawCircle(ox, oy, glowR, paint);

            paint.setShader(new RadialGradient(ox, oy, coreR,
                    new int[]{coreCenter, withAlpha(color, 0.55f), withAlpha(color, 0.08f)},
                    stops, Shader.TileMode.CLAMP));
            canvas.drawCircle(ox, oy, coreR, paint);
            paint.setShader(null);
        }

        // 3) 最中心再叠一层柔白，让四球“融成一团”
        paint.setShader(new RadialGradient(cx, cy, R * 0.95f,
                new int[]{0x40FFFFFF, 0x00FFFFFF},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, R * 0.95f, paint);
        paint.setShader(null);
    }

    private static int withAlpha(int color, float alpha) {
        int a = Math.round(Math.max(0f, Math.min(1f, alpha)) * 255f);
        return (a << 24) | (color & 0x00FFFFFF);
    }
}