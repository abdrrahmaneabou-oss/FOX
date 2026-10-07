package com.ponie.dayov12.ui;

import android.content.Context;
import android.content.SharedPreferences;

/** Small private preference model for the independent Freeze analog overlay. */
final class FreezeAnalogSettings {
    private static final String PREFS = "fox_freeze_analog";
    private static final String X = "base_x";
    private static final String Y = "base_y";
    private static final String BASE = "base_diameter_dp";
    private static final String KNOB = "knob_diameter_dp";

    int x;
    int y;
    int baseDp;
    int knobDp;

    static FreezeAnalogSettings load(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        FreezeAnalogSettings s = new FreezeAnalogSettings();
        float density = context.getResources().getDisplayMetrics().density;
        int screenW = context.getResources().getDisplayMetrics().widthPixels;
        int screenH = context.getResources().getDisplayMetrics().heightPixels;
        s.baseDp = clamp(p.getInt(BASE, 112), 72, 220);
        s.knobDp = clamp(p.getInt(KNOB, 44), 24, Math.max(24, s.baseDp / 2));
        int diameter = Math.round(s.baseDp * density);
        s.x = p.getInt(X, Math.max(0, screenW - diameter - Math.round(28 * density)));
        s.y = p.getInt(Y, Math.max(0, screenH / 2 - diameter / 2));
        return s;
    }

    void save(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(X, x)
                .putInt(Y, y)
                .putInt(BASE, baseDp)
                .putInt(KNOB, knobDp)
                .apply();
    }

    void normalize() {
        baseDp = clamp(baseDp, 72, 220);
        knobDp = clamp(knobDp, 24, Math.max(24, baseDp / 2));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
