package com.ponie.dayov12.ui;

import android.content.Context;

/**
 * Process-wide owner for the independent Freeze analog feature.
 *
 * The dashboard card is intentionally not wired yet. The eventual input bridge only
 * needs to call {@link #onGlobalPointer}; all Freeze/geometry/settings behavior stays
 * behind this small boundary.
 */
final class FreezeAnalogManager implements FreezeAnalogController.SettingsRequestListener {
    private static volatile FreezeAnalogManager instance;

    static FreezeAnalogManager get(Context context) {
        FreezeAnalogManager local = instance;
        if (local != null) return local;
        synchronized (FreezeAnalogManager.class) {
            local = instance;
            if (local == null) {
                local = new FreezeAnalogManager(context.getApplicationContext());
                instance = local;
            }
            return local;
        }
    }

    private final Context context;
    private final FreezeAnalogController controller;
    private FreezeAnalogSettingsOverlay settingsOverlay;
    private boolean enabled;

    private FreezeAnalogManager(Context context) {
        this.context = context;
        this.controller = new FreezeAnalogController(context);
        this.controller.setSettingsRequestListener(this);
    }

    synchronized void setEnabled(boolean value) {
        if (enabled == value) return;
        enabled = value;
        if (value) {
            controller.show();
        } else {
            closeSettings(false);
            controller.hide();
        }
    }

    synchronized boolean isEnabled() { return enabled; }

    boolean onGlobalPointer(int action, int pointerId, float rawX, float rawY, long eventTime) {
        if (!enabled) return false;
        return controller.onPointer(action, pointerId, rawX, rawY, eventTime);
    }

    @Override public synchronized void onFreezeAnalogSettingsRequested(FreezeAnalogController owner) {
        if (!enabled) return;
        if (settingsOverlay != null && settingsOverlay.isShown()) return;
        settingsOverlay = new FreezeAnalogSettingsOverlay(context, controller);
        settingsOverlay.show();
    }

    synchronized void closeSettings(boolean save) {
        if (settingsOverlay == null) return;
        settingsOverlay.dismiss(save);
        settingsOverlay = null;
    }

    synchronized void shutdown() {
        enabled = false;
        closeSettings(false);
        controller.shutdown();
        if (instance == this) instance = null;
    }
}
