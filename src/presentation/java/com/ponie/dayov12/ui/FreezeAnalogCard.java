package com.ponie.dayov12.ui;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Dashboard control for the independent hold-to-Freeze analog overlay. */
final class FreezeAnalogCard {
    private static final int ON = 0xff2f9d67;
    private static final int OFF = 0xff202431;

    final LinearLayout view;
    private final FoxTheme theme;
    private final FreezeAnalogManager manager;
    private final TextView state;
    private final TextView button;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshLoop = new Runnable() {
        @Override public void run() {
            if (!view.isAttachedToWindow()) return;
            refresh();
            handler.postDelayed(this, 350L);
        }
    };

    FreezeAnalogCard(Activity activity, FoxTheme theme) {
        this.theme = theme;
        this.manager = FreezeAnalogManager.get(activity);
        this.view = theme.card();

        theme.add(view, theme.text("FREEZE ANALOG", 11, FoxTheme.ACCENT, true), 0);
        theme.add(view, theme.text("Hold control", 22, FoxTheme.TEXT, true), 8);
        theme.add(view, theme.text(
                "Hold the analog to enable Freeze and release to disable it. Triple-tap the analog for its hidden editor.",
                12, FoxTheme.MUTED, false), 8);

        state = theme.text("OFF", 12, FoxTheme.MUTED, true);
        state.setGravity(Gravity.START);
        theme.add(view, state, 12);

        button = theme.text("ENABLE", 14, FoxTheme.TEXT, true);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(theme.dp(48));
        button.setPadding(theme.dp(12), theme.dp(12), theme.dp(12), theme.dp(12));
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setOnClickListener(v -> {
            manager.setEnabled(!manager.isEnabled());
            refresh();
        });
        theme.add(view, button, 8);

        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {
                handler.removeCallbacks(refreshLoop);
                handler.post(refreshLoop);
            }
            @Override public void onViewDetachedFromWindow(View v) {
                handler.removeCallbacks(refreshLoop);
            }
        });
        refresh();
    }

    void refresh() {
        boolean active = manager.isActive();
        boolean waiting = manager.isWaitingForInput();
        if (active) {
            state.setText("ON");
            state.setTextColor(ON);
            button.setText("DISABLE");
            button.setBackground(theme.surface(ON, 12));
            button.setContentDescription("Disable Freeze Analog");
        } else if (waiting) {
            state.setText("WAITING FOR SHIZUKU");
            state.setTextColor(FoxTheme.ACCENT);
            button.setText("CANCEL");
            button.setBackground(theme.surface(OFF, 12));
            button.setContentDescription("Cancel Freeze Analog activation");
        } else {
            state.setText("OFF");
            state.setTextColor(FoxTheme.MUTED);
            button.setText("ENABLE");
            button.setBackground(theme.surface(OFF, 12));
            button.setContentDescription("Enable Freeze Analog");
        }
        button.setTextColor(FoxTheme.TEXT);
    }
}
