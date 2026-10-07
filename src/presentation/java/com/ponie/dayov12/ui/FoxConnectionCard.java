package com.ponie.dayov12.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.ponie.dayov12.FoxConfigStore;
import com.ponie.dayov12.FoxTransport;

/** Read-only transport status; permission and session state are never labelled as a handshake. */
public final class FoxConnectionCard {
    private final Activity activity;
    private final TextView status, config;
    public final LinearLayout view;
    public FoxConnectionCard(Activity activity, FoxTheme theme) {
        this.activity = activity; view = theme.card();
        theme.add(view, theme.text("AMNEZIAWG", 11, FoxTheme.ACCENT, true), 0);
        theme.add(view, theme.text("Connection", 22, FoxTheme.TEXT, true), 8);
        status = theme.text("", 14, FoxTheme.TEXT, false); theme.add(view, status, 12);
        config = theme.text("", 12, FoxTheme.MUTED, false); theme.add(view, config, 8);
        LinearLayout buttons = new LinearLayout(activity);
        TextView importButton = theme.button("Import .conf", true);
        TextView detailsButton = theme.button("Details", false);
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -2, 1); left.setMarginEnd(theme.dp(8));
        buttons.addView(importButton, left); buttons.addView(detailsButton, new LinearLayout.LayoutParams(0, -2, 1));
        theme.add(view, buttons, 16);
        importButton.setOnClickListener(v -> ConfigImportController.choose(activity));
        detailsButton.setOnClickListener(v -> showDetails());
        refresh();
    }
    public void refresh() {
        String raw = FoxTransport.status();
        setText(status, raw.split("\n", 2)[0]);
        setText(config, FoxConfigStore.exists(activity) ? "Configuration saved on this device" : "Import an AmneziaWG .conf file before starting");
    }
    private static void setText(TextView view, String text) {
        if (!text.contentEquals(view.getText())) view.setText(text);
    }
    private void showDetails() {
        new AlertDialog.Builder(activity).setTitle("AmneziaWG connection")
            .setMessage(FoxTransport.status() + "\n\nTraffic follows the imported profile routes. FOX processing remains limited to its original game packages.\n\nVPN permission alone does not confirm a working connection. Android's VPN settings include Block connections without VPN.")
            .setPositiveButton("VPN settings", (dialog, which) -> {
                try { activity.startActivity(new Intent(Settings.ACTION_VPN_SETTINGS)); }
                catch (android.content.ActivityNotFoundException e) { ConfigImportController.toast(activity, "VPN settings are unavailable"); }
            }).setNegativeButton("Close", null).show();
    }
}
