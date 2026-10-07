package com.ponie.dayov12.ui;

import android.app.Activity;
import android.graphics.Typeface;
import android.view.Gravity;
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

    FreezeAnalogCard(Activity activity, FoxTheme theme) {
        this.theme = theme;
        this.manager = FreezeAnalogManager.get(activity);
        this.view = theme.card();

        theme.add(view, theme.text("FREEZE ANALOG", 11, FoxTheme.ACCENT, true), 0);
        theme.add(view, theme.text("Hold control", 22, FoxTheme.TEXT, true), 8);
        theme.add(view, theme.text(
                "Press and hold the analog to enable Freeze. Release to disable it. Triple-tap the analog for its hidden editor.",
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
        refresh();
    }

    void refresh() {
        boolean enabled = manager.isEnabled();
        state.setText(enabled ? "ON" : "OFF");
        state.setTextColor(enabled ? ON : FoxTheme.MUTED);
        button.setText(enabled ? "DISABLE" : "ENABLE");
        button.setBackground(theme.surface(enabled ? ON : OFF, 12));
        button.setTextColor(FoxTheme.TEXT);
        button.setContentDescription(enabled ? "Disable Freeze Analog" : "Enable Freeze Analog");
    }
}
