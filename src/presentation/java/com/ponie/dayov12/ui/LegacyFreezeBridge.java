package com.ponie.dayov12.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Narrow bridge to the existing FOX Freeze command.
 *
 * Legacy FloatingService uses action com.ponie.dayov12.md.f1 with the boolean
 * extra "enabled". Reusing that contract keeps packet semantics in the original
 * MyVpnService; this class does not duplicate or reinterpret Freeze logic.
 */
final class LegacyFreezeBridge {
    private static final String ACTION_FREEZE = "com.ponie.dayov12.md.f1";
    private static final String EXTRA_ENABLED = "enabled";
    private static final String VPN_SERVICE = "com.ponie.dayov12.MyVpnService";

    private final Context context;
    private boolean lastState;

    LegacyFreezeBridge(Context context) {
        this.context = context.getApplicationContext();
    }

    synchronized void setPressed(boolean pressed) {
        if (pressed == lastState) return;
        Intent intent = new Intent().setClassName(context, VPN_SERVICE)
                .setAction(ACTION_FREEZE)
                .putExtra(EXTRA_ENABLED, pressed);
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
            else context.startService(intent);
            lastState = pressed;
        } catch (RuntimeException first) {
            // If the service is already alive Android can allow startService even when a
            // foreground-service start is restricted. Never claim the state changed until
            // one of the original service entry points accepted the command.
            try {
                context.startService(intent);
                lastState = pressed;
            } catch (RuntimeException second) {
                android.util.Log.e("FreezeAnalog", "Legacy Freeze command rejected", second);
            }
        }
    }

    synchronized void forceOff() {
        if (!lastState) return;
        setPressed(false);
    }
}
