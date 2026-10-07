package com.ponie.dayov12.ui;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Always-labelled tabs delegate to the original V12 navigation listeners. */
public final class FoxNavigation {
    private final FoxTheme theme;
    private final ScrollView home;
    private final TextView homeButton, customizeButton;
    private boolean lastHome;
    private boolean initialized;
    public FoxNavigation(Activity activity, FoxTheme theme, LegacyViews legacy) {
        this.theme = theme;
        home = legacy.field("viewHome", ScrollView.class);
        LinearLayout bottom = legacy.field("bottomNav", LinearLayout.class);
        View homeAction = legacy.field("navHomeContainer", LinearLayout.class);
        View customizeAction = legacy.field("navImpairContainer", LinearLayout.class);
        homeButton = theme.button("Home", false);
        customizeButton = theme.button("Customize", false);
        homeButton.setOnClickListener(v -> { homeAction.performClick(); refresh(); });
        customizeButton.setOnClickListener(v -> { customizeAction.performClick(); refresh(); });
        LinearLayout row = new LinearLayout(activity);
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -2, 1); left.setMarginEnd(theme.dp(10));
        row.addView(homeButton, left);
        row.addView(customizeButton, new LinearLayout.LayoutParams(0, -2, 1));
        bottom.removeAllViews(); bottom.setBackgroundColor(FoxTheme.BG);
        bottom.setPadding(theme.dp(18), theme.dp(12), theme.dp(18), theme.dp(28));
        bottom.addView(row, new LinearLayout.LayoutParams(-1, -2));
        refresh();
    }
    public void refresh() {
        boolean selected = home.getVisibility() == View.VISIBLE;
        if (initialized && selected == lastHome) return;
        initialized = true; lastHome = selected;
        style(homeButton, selected); style(customizeButton, !selected);
    }
    private void style(TextView view, boolean selected) {
        view.setSelected(selected);
        view.setTextColor(selected ? FoxTheme.ACCENT : FoxTheme.MUTED);
        view.setBackground(theme.surface(selected ? 0xff282139 : FoxTheme.SURFACE, 14));
    }
}
