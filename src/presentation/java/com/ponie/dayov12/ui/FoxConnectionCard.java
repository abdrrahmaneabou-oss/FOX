package com.ponie.dayov12.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.VpnService;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.ponie.dayov12.FoxConfigStore;
import com.ponie.dayov12.FoxTransport;
import com.ponie.dayov12.MyVpnService;

/** Explicit user-driven AmneziaWG connection card. Opening the dashboard never starts or stops VPN. */
public final class FoxConnectionCard {
    public static final int VPN_PERMISSION_REQUEST = 17440;
    private static final int CONNECTED = 0xff43d17a;
    private final Activity activity;
    private final FoxTheme theme;
    private final Runnable beforeDisconnect;
    private final TextView status, config, connectButton;
    public final LinearLayout view;

    public FoxConnectionCard(Activity activity, FoxTheme theme, Runnable beforeDisconnect) {
        this.activity = activity;
        this.theme = theme;
        this.beforeDisconnect = beforeDisconnect;
        view = theme.card();
        theme.add(view, theme.text("AMNEZIAWG", 11, FoxTheme.ACCENT, true), 0);
        theme.add(view, theme.text("Connection", 22, FoxTheme.TEXT, true), 8);
        status = theme.text("Disconnected", 14, FoxTheme.TEXT, false);
        theme.add(view, status, 12);
        config = theme.text("", 12, FoxTheme.MUTED, false);
        theme.add(view, config, 8);

        LinearLayout connectionRow = new LinearLayout(activity);
        connectButton = theme.button("CONNECT", false);
        TextView importButton = theme.button("Import .conf", false);
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -2, 1);
        left.setMarginEnd(theme.dp(8));
        connectionRow.addView(connectButton, left);
        connectionRow.addView(importButton, new LinearLayout.LayoutParams(0, -2, 1));
        theme.add(view, connectionRow, 16);

        TextView detailsButton = theme.button("Details", false);
        theme.add(view, detailsButton, 8);

        connectButton.setOnClickListener(v -> toggleConnection());
        importButton.setOnClickListener(v -> ConfigImportController.choose(activity));
        detailsButton.setOnClickListener(v -> showDetails());
        refresh();
    }

    public boolean isConnected() {
        return MyVpnService.isVpnRunning;
    }

    public void refresh() {
        boolean connected = MyVpnService.isVpnRunning;
        String raw = FoxTransport.status();
        setText(status, connected ? raw.split("\\n", 2)[0] : "Disconnected");
        setText(config, FoxConfigStore.exists(activity)
            ? "Configuration saved on this device"
            : "Import an AmneziaWG .conf file before connecting");
        connectButton.setText(connected ? "CONNECTED" : "CONNECT");
        connectButton.setTextColor(connected ? FoxTheme.BG : FoxTheme.TEXT);
        connectButton.setBackground(theme.surface(connected ? CONNECTED : 0xff202431, 12));
        connectButton.setContentDescription(connected ? "Disconnect AmneziaWG" : "Connect AmneziaWG");
    }

    private void toggleConnection() {
        if (MyVpnService.isVpnRunning) {
            if (beforeDisconnect != null) beforeDisconnect.run();
            activity.stopService(new Intent(activity, MyVpnService.class));
            refresh();
            return;
        }
        if (!FoxConfigStore.exists(activity)) {
            ConfigImportController.toast(activity, "Import an AmneziaWG .conf file first");
            return;
        }
        Intent permission = VpnService.prepare(activity);
        if (permission != null) {
            try { activity.startActivityForResult(permission, VPN_PERMISSION_REQUEST); }
            catch (android.content.ActivityNotFoundException e) {
                ConfigImportController.toast(activity, "VPN permission is unavailable");
            }
            return;
        }
        startVpn(activity);
    }

    public static boolean result(Activity activity, int request, int result) {
        if (request != VPN_PERMISSION_REQUEST) return false;
        if (result == Activity.RESULT_OK) startVpn(activity);
        else ConfigImportController.toast(activity, "VPN permission was not granted");
        return true;
    }

    private static void startVpn(Activity activity) {
        Intent start = new Intent(activity, MyVpnService.class)
            .setAction("com.ponie.dayov12.md.s1");
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) activity.startForegroundService(start);
            else activity.startService(start);
        } catch (RuntimeException e) {
            ConfigImportController.toast(activity, "Unable to start AmneziaWG");
        }
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
