package com.ponie.dayov12.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared visual tokens. This layer never reads or changes network preferences. */
public final class FoxTheme {
    public static final int BG = 0xff0c0e14, SURFACE = 0xff151822, BORDER = 0xff292e3d;
    public static final int TEXT = 0xfff2f3fa, MUTED = 0xffa2aabc, ACCENT = 0xffb89aff;
    private final Context context;
    public FoxTheme(Context context) { this.context = context; }
    public int dp(float value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    public GradientDrawable surface(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color); d.setCornerRadius(dp(radius)); d.setStroke(dp(1), BORDER);
        return d;
    }
    public TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        view.setIncludeFontPadding(false);
        return view;
    }
    public LinearLayout column() {
        LinearLayout view = new LinearLayout(context); view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }
    public LinearLayout card() {
        LinearLayout view = column(); view.setPadding(dp(18), dp(18), dp(18), dp(18));
        view.setBackground(surface(SURFACE, 20)); return view;
    }
    public TextView button(String label, boolean primary) {
        TextView view = text(label, 14, primary ? BG : TEXT, true);
        view.setGravity(Gravity.CENTER); view.setMinHeight(dp(48));
        view.setPadding(dp(12), dp(12), dp(12), dp(12));
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x338e78c9),
            surface(primary ? ACCENT : 0xff202431, 12), null));
        view.setFocusable(true); view.setContentDescription(label);
        return view;
    }
    public void add(LinearLayout parent, View child, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(top);
        parent.addView(child, lp);
    }
}
