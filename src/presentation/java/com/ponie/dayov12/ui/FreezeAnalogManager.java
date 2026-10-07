package com.ponie.dayov12.ui;

import android.content.Context;

/** Process-wide owner for the independent hold-to-Freeze analog feature. */
final class FreezeAnalogManager implements
        FreezeAnalogController.SettingsRequestListener,
        ShizukuInputBridge.Listener {
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
    private final ShizukuInputBridge inputBridge;
    private final FreezeAnalogController controller;
    private FreezeAnalogSettingsOverlay settingsOverlay;
    private boolean requestedEnabled;
    private boolean active;

    private FreezeAnalogManager(Context context) {
        this.context = context;
        this.inputBridge = new ShizukuInputBridge(context, this);
        this.controller = new FreezeAnalogController(context, inputBridge);
        this.controller.setSettingsRequestListener(this);
    }

    /** Called only by the explicit dashboard card. */
    synchronized void setEnabled(boolean value) {
        requestedEnabled = value;
        if (!value) {
            closeSettings(false);
            controller.hide();
            active = false;
            return;
        }

        int state = inputBridge.ensureReady();
        if (state == ShizukuInputBridge.READY) {
            active = controller.show();
        } else {
            active = false;
            controller.hide();
            if (state == ShizukuInputBridge.DENIED) requestedEnabled = false;
        }
    }

    /** True includes the short period while Shizuku permission/service binding completes. */
    synchronized boolean isEnabled() { return requestedEnabled; }
    synchronized boolean isActive() { return active && controller.isShown() && inputBridge.isReady(); }
    synchronized boolean isWaitingForInput() { return requestedEnabled && !isActive(); }

    @Override public synchronized void onInputBridgeReadyChanged(boolean ready) {
        if (!requestedEnabled) {
            if (!ready) active = false;
            return;
        }
        if (ready) {
            active = controller.show();
        } else {
            closeSettings(false);
            controller.hide();
            active = false;
        }
    }

    @Override public synchronized void onInputBridgePermissionDenied() {
        requestedEnabled = false;
        active = false;
        closeSettings(false);
        controller.hide();
    }

    @Override public synchronized void onFreezeAnalogSettingsRequested(FreezeAnalogController owner) {
        if (!isActive()) return;
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
        requestedEnabled = false;
        active = false;
        closeSettings(false);
        controller.shutdown();
        inputBridge.shutdown();
        if (instance == this) instance = null;
    }
}
