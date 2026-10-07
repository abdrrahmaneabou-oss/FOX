package com.ponie.dayov12.ui;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/** Installs the independent Freeze Analog card immediately below START/CONNECT. */
public final class FreezeAnalogUi {
    private FreezeAnalogUi() {}

    public static void attach(Activity activity) {
        try {
            FoxTheme theme = new FoxTheme(activity);
            LegacyViews legacy = new LegacyViews(activity);
            ScrollView home = legacy.field("viewHome", ScrollView.class);
            LinearLayout column = (LinearLayout) home.getChildAt(0);
            FreezeAnalogCard card = new FreezeAnalogCard(activity, theme);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = theme.dp(14);
            int index = Math.min(2, column.getChildCount());
            column.addView(card.view, index, lp);
            card.view.setTag("fox-freeze-analog-card");
            android.util.Log.i("FreezeAnalog", "Dashboard card attached at index " + index);
        } catch (RuntimeException error) {
            android.util.Log.e("FreezeAnalog", "Unable to attach dashboard card", error);
        }
    }
}
