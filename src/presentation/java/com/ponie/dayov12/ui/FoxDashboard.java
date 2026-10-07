package com.ponie.dayov12.ui;

import android.app.Activity;
import android.app.Application;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** Presentation-only controller. Original controls and listeners are reused, never reimplemented. */
public final class FoxDashboard implements Application.ActivityLifecycleCallbacks {
    private static final int START_ACTIVE = 0xffd84a4a;
    private static final int START_IDLE = 0xff202431;

    private final Activity activity;
    private final FoxTheme theme;
    private final LegacyViews legacy;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private FoxConnectionCard connection;
    private FreezeAnalogCard freezeAnalogCard;
    private TextView primary;
    private FoxNavigation navigation;
    private boolean active;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!active || activity.isDestroyed()) return;
            connection.refresh();
            navigation.refresh();
            if (freezeAnalogCard != null) freezeAnalogCard.refresh();

            String label = primary.getText().toString();
            boolean sessionRunning = label.equals("STOP");
            boolean vpnConnected = connection.isConnected();

            if (!vpnConnected && sessionRunning) {
                primary.performClick();
                label = primary.getText().toString();
                sessionRunning = label.equals("STOP");
            }
            primary.setEnabled(vpnConnected || sessionRunning);
            primary.setAlpha(vpnConnected || sessionRunning ? 1f : 0.62f);
            if (!label.equals(lastAction) || vpnConnected != lastVpnConnected) {
                lastAction = label;
                lastVpnConnected = vpnConnected;
                paintPrimary(sessionRunning, vpnConnected);
            }
            handler.postDelayed(this, 250);
        }
    };
    private String lastAction = "";
    private boolean lastVpnConnected;

    public FoxDashboard(Activity activity) {
        this.activity = activity;
        theme = new FoxTheme(activity);
        legacy = new LegacyViews(activity);
    }

    public void install() {
        ScrollView home = legacy.field("viewHome", ScrollView.class);
        ScrollView customize = legacy.field("viewImpair", ScrollView.class);
        RelativeLayout root = legacy.field("rexRootLayout", RelativeLayout.class);
        primary = legacy.field("btnActionPrimary", TextView.class);
        TextView badge = legacy.field("tvEngineBadge", TextView.class);
        TextView permission = legacy.field("btnVpnState", TextView.class);
        TextView homeNav = legacy.field("tvHome", TextView.class);
        TextView customizeNav = legacy.field("tvImpair", TextView.class);
        LinearLayout homeColumn = (LinearLayout) home.getChildAt(0);
        View session = LegacyViews.topChild(homeColumn, primary);
        View access = LegacyViews.topChild(homeColumn, permission);
        View hero = LegacyViews.topChild(homeColumn, legacy.field("tvHeroStatus", TextView.class));
        List<View> retained = new ArrayList<>();
        for (int i = 0; i < homeColumn.getChildCount(); i++) {
            View child = homeColumn.getChildAt(i);
            if (child != session && child != hero && child != access) retained.add(child);
        }

        connection = new FoxConnectionCard(activity, theme, this::disableSessionBeforeDisconnect);
        freezeAnalogCard = new FreezeAnalogCard(activity, theme);

        LinearLayout sessionCard = theme.card();
        theme.add(sessionCard, theme.text("CONTROL CENTER", 11, FoxTheme.ACCENT, true), 0);
        theme.add(sessionCard, theme.text("Your session", 26, FoxTheme.TEXT, true), 10);
        theme.add(sessionCard, theme.text("Connect AmneziaWG first, then enable the floating abilities.", 13, FoxTheme.MUTED, false), 10);
        LegacyViews.detach(badge);
        badge.setTextSize(12);
        badge.setPadding(0, theme.dp(10), 0, theme.dp(10));
        badge.setBackground(null);
        theme.add(sessionCard, badge, 6);
        LegacyViews.detach(primary);
        primary.setMinHeight(theme.dp(54));
        primary.setTextSize(16);
        primary.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        primary.setPadding(theme.dp(16), theme.dp(16), theme.dp(16), theme.dp(16));
        primary.setEnabled(connection.isConnected());
        paintPrimary(primary.getText().toString().equals("STOP"), connection.isConnected());
        theme.add(sessionCard, primary, 6);

        homeColumn.removeAllViews();
        homeColumn.setPadding(theme.dp(18), theme.dp(8), theme.dp(18), theme.dp(24));
        homeColumn.setBackgroundColor(FoxTheme.BG);
        theme.add(homeColumn, sessionCard, 0);
        theme.add(homeColumn, connection.view, 14);
        theme.add(homeColumn, freezeAnalogCard.view, 14);
        for (View child : retained) {
            quietStyle(child);
            theme.add(homeColumn, child, 14);
        }
        quietStyle(access);
        theme.add(homeColumn, access, 18);
        home.setOnScrollChangeListener(null);
        home.setFillViewport(false);
        home.setVerticalScrollBarEnabled(false);
        root.setBackgroundColor(FoxTheme.BG);
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (!(child instanceof android.widget.FrameLayout)) quietStyle(child);
        }
        quietStyle(customize);
        compactPreview(customize);
        homeNav.setText("Home");
        customizeNav.setText("Customize");
        homeNav.setTextSize(13);
        customizeNav.setTextSize(13);
        homeNav.setContentDescription("Home");
        customizeNav.setContentDescription("Customize");
        navigation = new FoxNavigation(activity, theme, legacy);
        legacy.pauseDecoration();
        activity.getApplication().registerActivityLifecycleCallbacks(this);
        android.util.Log.i("FoxUI", "Presentation installed; Freeze Analog dashboard card is live");
    }

    private void disableSessionBeforeDisconnect() {
        if (primary != null && "STOP".contentEquals(primary.getText())) primary.performClick();
        if (primary != null) paintPrimary(false, false);
    }

    private void paintPrimary(boolean sessionRunning, boolean vpnConnected) {
        int color = sessionRunning ? START_ACTIVE : START_IDLE;
        primary.setBackground(theme.surface(color, 14));
        primary.setTextColor(sessionRunning ? FoxTheme.TEXT : (vpnConnected ? FoxTheme.TEXT : FoxTheme.MUTED));
    }

    private void compactPreview(View view) {
        if (view.getClass().getName().endsWith("$RexPedestalView")) {
            ViewGroup.LayoutParams lp = view.getLayoutParams();
            if (lp != null) {
                lp.height = theme.dp(140);
                view.setLayoutParams(lp);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) compactPreview(group.getChildAt(i));
        }
    }

    private void quietStyle(View view) {
        String type = view.getClass().getName();
        if (type.endsWith("$RexMotionBackgroundView") || type.endsWith("$RexEnergyRailView")
                || type.endsWith("$RexSessionPulseView") || type.endsWith("$RexReadinessRingView")) {
            view.setVisibility(View.GONE);
            return;
        }
        view.setAlpha(1f);
        view.setTranslationY(0f);
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            if (text.getTextSize() / activity.getResources().getDisplayMetrics().scaledDensity < 12) text.setTextSize(12);
            text.setLetterSpacing(0.015f);
            if (text.isClickable()) text.setMinHeight(theme.dp(48));
        }
        if (view instanceof SeekBar) {
            SeekBar seek = (SeekBar) view;
            seek.setProgressTintList(android.content.res.ColorStateList.valueOf(FoxTheme.ACCENT));
            seek.setThumbTintList(android.content.res.ColorStateList.valueOf(FoxTheme.ACCENT));
        }
        if (view.getBackground() != null && view.getBackground().getClass().getName().endsWith("$RexDepthCardDrawable")) {
            view.setBackground(theme.surface(FoxTheme.SURFACE, 18));
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) quietStyle(group.getChildAt(i));
        }
    }

    @Override public void onActivityResumed(Activity owner) {
        if (owner != activity) return;
        active = true;
        handler.removeCallbacks(refresh);
        handler.post(refresh);
        legacy.pauseDecoration();
    }

    @Override public void onActivityPaused(Activity owner) {
        if (owner == activity) {
            active = false;
            handler.removeCallbacks(refresh);
        }
    }

    @Override public void onActivityDestroyed(Activity owner) {
        if (owner != activity) return;
        active = false;
        handler.removeCallbacksAndMessages(null);
        activity.getApplication().unregisterActivityLifecycleCallbacks(this);
    }

    @Override public void onActivityCreated(Activity a, Bundle b) {}
    @Override public void onActivityStarted(Activity a) {}
    @Override public void onActivityStopped(Activity a) {}
    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
}
