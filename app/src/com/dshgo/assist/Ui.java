package com.dshgo.assist;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * v10 统一视觉：Siri 式极简深色。
 *
 * 设计语言：极简、深色、半透明、留白大、颜色克制；
 * 只用一处强调色（柔和蓝紫 #7AA2FF / #A78BFA），其余靠灰阶 + 白透明度分层。
 * 禁止：重色块、彩色边框、大面积渐变、粗阴影、emoji、五颜六色的圆点。
 *
 * 颜色/尺寸常量（真机调参直接改这里）：
 *   BG            #0A0B0D   页面背景
 *   CARD          0x12FFFFFF 卡片底（白 7%）
 *   BORDER        0x1AFFFFFF 卡片描边（白 10%）
 *   TEXT          #E8EAED   主文字
 *   SUB           #8A8F98   次要文字
 *   ACCENT        #7AA2FF   强调色（蓝）
 *   ACCENT2       #A78BFA   强调色（紫）
 *   DOT_OK        #4ADE80   正常（低饱和绿）
 *   DOT_WARN      #FBBF24   待处理（低饱和琥珀）
 *   DOT_BAD       #F87171   异常（低饱和红）
 *   标题 28sp semibold；副标题 13sp；状态值等宽；区块间距 28dp；卡片内边距 20dp
 */
final class Ui {

    static final int BG = 0xFF0A0B0D;
    static final int CARD = 0x12FFFFFF;
    static final int BORDER = 0x1AFFFFFF;
    static final int TEXT = 0xFFE8EAED;
    static final int SUB = 0xFF8A8F98;
    static final int ACCENT = 0xFF7AA2FF;
    static final int ACCENT2 = 0xFFA78BFA;
    static final int DOT_OK = 0xFF4ADE80;
    static final int DOT_WARN = 0xFFFBBF24;
    static final int DOT_BAD = 0xFFF87171;
    static final int DOT_IDLE = 0xFF4B5058;

    static final int PRIMARY_BG = 0xFF1E3A8A;      // 低调深蓝
    static final int PRIMARY_FG = 0xFFDBEAFE;      // 浅蓝字

    private final float density;

    Ui(Context context) {
        BootTrace.mark("Ui.<init>");
        density = context.getResources().getDisplayMetrics().density;
    }

    int dp(float value) {
        return Math.round(value * density);
    }

    // ------------------------------------------------------------ drawables --
    GradientDrawable rounded(int fill, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    GradientDrawable rounded(int fill, float radiusDp, int strokeColor, float strokeDp) {
        GradientDrawable d = rounded(fill, radiusDp);
        d.setStroke(Math.max(1, dp(strokeDp)), strokeColor);
        return d;
    }

    Drawable circle(int fill) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(fill);
        return d;
    }

    Drawable ripple(Drawable content, int rippleColor) {
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, null);
    }

    // ---------------------------------------------------------------- views --
    LinearLayout column(Context c) {
        LinearLayout ll = new LinearLayout(c);
        ll.setOrientation(LinearLayout.VERTICAL);
        return ll;
    }

    LinearLayout row(Context c) {
        LinearLayout ll = new LinearLayout(c);
        ll.setOrientation(LinearLayout.HORIZONTAL);
        ll.setGravity(Gravity.CENTER_VERTICAL);
        return ll;
    }

    /** 卡片：白 7% 底 + 白 10% 细描边 + 圆角 16dp + 内边距 20dp。 */
    LinearLayout cardView(Context c) {
        LinearLayout ll = column(c);
        ll.setBackground(rounded(CARD, 16f, BORDER, 1f));
        int p = dp(20);
        ll.setPadding(p, p, p, p);
        return ll;
    }

    TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView tv = new TextView(c);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        tv.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        return tv;
    }

    /** 区块标题：13sp、次要文字、上下留白（区块间距 28dp）。 */
    TextView sectionTitle(Context c, String s) {
        TextView tv = text(c, s, 13f, SUB, false);
        tv.setPadding(dp(4), dp(22), dp(4), dp(10));
        return tv;
    }

    /** 状态值：等宽数字，右对齐。 */
    TextView valueText(Context c) {
        TextView tv = text(c, "—", 13f, TEXT, false);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setGravity(Gravity.END);
        return tv;
    }

    /** 状态卡一行：左标签、右值 + 6dp 单色小圆点。 */
    LinearLayout statusRow(Context c, String label) {
        LinearLayout r = row(c);
        r.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40)));
        TextView l = text(c, label, 14f, SUB, false);
        l.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(l);
        return r;
    }

    View dot(Context c) {
        View v = new View(c);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(6), dp(6));
        p.leftMargin = dp(10);
        v.setLayoutParams(p);
        v.setBackground(circle(DOT_IDLE));
        return v;
    }

    View divider(Context c) {
        View v = new View(c);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        v.setLayoutParams(p);
        v.setBackgroundColor(BORDER);
        return v;
    }

    /** 主操作条：高 48dp、圆角 14dp、深蓝底 + 浅蓝字。 */
    Button primaryBar(Context c, String label) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        b.setTextColor(PRIMARY_FG);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setBackground(ripple(rounded(PRIMARY_BG, 14f), 0x33FFFFFF));
        b.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
        return b;
    }

    /**
     * 高级区的一行：左文字 + 右侧小 chevron「›」，整行可点。
     * 不用胶囊按钮，符合「收敛」的设计语言。
     */
    LinearLayout advancedRow(Context c, String label, String detail, View.OnClickListener l) {
        LinearLayout r = row(c);
        r.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));
        r.setBackground(ripple(new GradientDrawable(), 0x14FFFFFF));
        r.setClickable(true);
        r.setOnClickListener(l);

        LinearLayout texts = column(c);
        TextView t = text(c, label, 15f, TEXT, false);
        texts.addView(t);
        if (detail != null && detail.length() > 0) {
            TextView d = text(c, detail, 12f, SUB, false);
            texts.addView(d);
        }
        texts.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(texts);

        TextView chevron = text(c, "›", 20f, 0x66FFFFFF, false);
        chevron.setPadding(dp(8), 0, dp(4), 0);
        r.addView(chevron);
        return r;
    }

    /** 「高级」折叠区标题：点一下展开/收起，chevron 旋转 90°。 */
    LinearLayout advancedHeader(Context c, final View.OnClickListener onToggle,
                                final View chevron) {
        LinearLayout r = row(c);
        r.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
        r.setClickable(true);
        r.setBackground(ripple(new GradientDrawable(), 0x14FFFFFF));
        r.setOnClickListener(onToggle);
        TextView t = text(c, "高级", 13f, SUB, false);
        t.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(t, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(chevron);
        return r;
    }
}