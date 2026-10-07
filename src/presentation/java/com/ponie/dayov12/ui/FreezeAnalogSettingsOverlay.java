package com.ponie.dayov12.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/** Hidden triple-tap editor for position, base size and movable-circle size. */
final class FreezeAnalogSettingsOverlay {
    private static final int BG = 0xff11141d;
    private static final int SURFACE = 0xff1a1f2b;
    private static final int TEXT = 0xfff3f4fa;
    private static final int MUTED = 0xff9ea6b8;
    private static final int ACCENT = 0xffb89aff;

    private final Context context;
    private final WindowManager windowManager;
    private final FreezeAnalogController controller;
    private final float density;
    private LinearLayout panel;
    private WindowManager.LayoutParams params;
    private SeekBar knobSeek;
    private TextView positionState;
    private boolean shown;

    FreezeAnalogSettingsOverlay(Context context, FreezeAnalogController controller) {
        this.context = context.getApplicationContext();
        this.windowManager = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
        this.controller = controller;
        this.density = this.context.getResources().getDisplayMetrics().density;
    }

    void show() {
        if (shown) return;
        panel = buildPanel();
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        params = new WindowManager.LayoutParams(
                dp(320), WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.END;
        params.x = dp(18);
        params.y = dp(72);
        try {
            windowManager.addView(panel, params);
            shown = true;
        } catch (RuntimeException e) {
            android.util.Log.e("FreezeAnalog", "Cannot show settings overlay", e);
        }
    }

    void dismiss(boolean save) {
        controller.setPositionEdit(false);
        if (save) controller.saveSettings();
        if (!shown || panel == null) return;
        try { windowManager.removeView(panel); }
        catch (RuntimeException ignored) {}
        shown = false;
        panel = null;
    }

    boolean isShown() { return shown; }

    private LinearLayout buildPanel() {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(14), dp(14), dp(14));
        root.setBackground(FoxThemeStatic.surface(context, BG, 20, 0xff32394a));

        TextView title = text("FREEZE ANALOG", 12, ACCENT, true);
        add(root, title, 0);
        TextView heading = text("Hidden controls", 21, TEXT, true);
        add(root, heading, 7);
        TextView help = text("Triple-tap opens this panel. Changes are committed only with SAVE.", 12, MUTED, false);
        add(root, help, 7);

        LinearLayout position = card();
        TextView positionTitle = text("ADJUST POSITION", 14, TEXT, true);
        positionState = text("Tap, then drag the circular base itself", 12, MUTED, false);
        add(position, positionTitle, 0);
        add(position, positionState, 6);
        position.setOnClickListener(v -> {
            boolean next = !controller.isPositionEdit();
            controller.setPositionEdit(next);
            positionState.setText(next ? "POSITION MODE ON — drag the base" : "Tap, then drag the circular base itself");
            positionState.setTextColor(next ? ACCENT : MUTED);
        });
        add(root, position, 14);

        LinearLayout base = card();
        TextView baseTitle = text("BASE SIZE", 14, TEXT, true);
        TextView baseValue = text(controller.getBaseDiameterDp() + " dp", 12, MUTED, false);
        SeekBar baseSeek = new SeekBar(context);
        baseSeek.setMax(220 - 72);
        baseSeek.setProgress(controller.getBaseDiameterDp() - 72);
        add(base, baseTitle, 0);
        add(base, baseValue, 6);
        add(base, baseSeek, 4);
        add(root, base, 10);

        LinearLayout knob = card();
        TextView knobTitle = text("MOVABLE CIRCLE SIZE", 14, TEXT, true);
        TextView knobValue = text(controller.getKnobDiameterDp() + " dp", 12, MUTED, false);
        knobSeek = new SeekBar(context);
        refreshKnobRange(knobValue);
        add(knob, knobTitle, 0);
        add(knob, knobValue, 6);
        add(knob, knobSeek, 4);
        add(root, knob, 10);

        baseSeek.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                controller.setBaseDiameterDp(72 + progress, false);
                baseValue.setText(controller.getBaseDiameterDp() + " dp");
                refreshKnobRange(knobValue);
            }
        });
        knobSeek.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                controller.setKnobDiameterDp(24 + progress, false);
                knobValue.setText(controller.getKnobDiameterDp() + " dp");
            }
        });

        TextView save = text("SAVE", 14, 0xff0c0e14, true);
        save.setGravity(Gravity.CENTER);
        save.setMinHeight(dp(48));
        save.setPadding(dp(12), dp(12), dp(12), dp(12));
        save.setBackground(FoxThemeStatic.surface(context, ACCENT, 13, ACCENT));
        save.setOnClickListener(v -> dismiss(true));
        add(root, save, 12);
        return root;
    }

    private void refreshKnobRange(TextView value) {
        if (knobSeek == null) return;
        int maxDp = Math.max(24, controller.getBaseDiameterDp() / 2);
        knobSeek.setMax(maxDp - 24);
        int current = Math.min(maxDp, Math.max(24, controller.getKnobDiameterDp()));
        controller.setKnobDiameterDp(current, false);
        knobSeek.setProgress(current - 24);
        value.setText(current + " dp");
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(13), dp(14), dp(13));
        card.setBackground(FoxThemeStatic.surface(context, SURFACE, 15, 0xff303647));
        return card;
    }

    private TextView text(String value, int size, int color, boolean medium) {
        TextView t = new TextView(context);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return t;
    }

    private void add(LinearLayout parent, View child, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        parent.addView(child, lp);
    }

    private int dp(float value) { return Math.round(value * density); }

    private abstract static class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar seekBar) {}
        @Override public void onStopTrackingTouch(SeekBar seekBar) {}
    }

    /** Local drawable helper keeps this feature independent from Activity-bound FoxTheme. */
    private static final class FoxThemeStatic {
        static android.graphics.drawable.GradientDrawable surface(
                Context context, int color, int radiusDp, int strokeColor) {
            float density = context.getResources().getDisplayMetrics().density;
            android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
            d.setColor(color);
            d.setCornerRadius(radiusDp * density);
            d.setStroke(Math.max(1, Math.round(density)), strokeColor);
            return d;
        }
    }
}
