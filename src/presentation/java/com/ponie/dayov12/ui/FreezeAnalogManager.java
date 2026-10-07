package com.ponie.dayov12.ui;

import android.content.Context;
import android.view.MotionEvent;

/** Process-wide owner for the Freeze analog and its Shizuku touch backend. */
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
    private final FreezeAnalogController controller;
    private final ShizukuInputBridge inputBridge;
    private FreezeAnalogSettingsOverlay settingsOverlay;
    private boolean enabled;
    private volatile boolean permissionDenied;

    private FreezeAnalogManager(Context context) {
        this.context = context;
        this.controller = new FreezeAnalogController(context);
        this.controller.setSettingsRequestListener(this);
        this.inputBridge = new ShizukuInputBridge(context, this);
        this.controller.setTouchRelay(this::relayToShizuku);
    }

    int connectShizuku() {
        permissionDenied = false;
        return inputBridge.ensureReady();
    }

    int getShizukuState() {
        if (inputBridge.isReady()) return ShizukuInputBridge.READY;
        if (permissionDenied) return ShizukuInputBridge.DENIED;
        return inputBridge.peekState();
    }

    boolean isShizukuReady() {
        return inputBridge.isReady();
    }

    int getShizukuBackendKind() {
        return inputBridge.getBackendKind();
    }

    /** Returns true only if the requested state could actually be applied. */
    synchronized boolean setEnabled(boolean value) {
        if (enabled == value) return true;
        if (value && !inputBridge.isReady()) {
            inputBridge.ensureReady();
            return false;
        }
        enabled = value;
        if (value) {
            controller.show();
        } else {
            closeSettings(false);
            controller.hide();
        }
        return true;
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

    @Override public void onInputBridgeReadyChanged(boolean ready) {
        if (!ready) setEnabled(false);
    }

    @Override public void onInputBridgePermissionDenied() {
        permissionDenied = true;
        setEnabled(false);
    }

    synchronized void closeSettings(boolean save) {
        if (settingsOverlay == null) return;
        settingsOverlay.dismiss(save);
        settingsOverlay = null;
    }

    synchronized void shutdown() {
        enabled = false;
        closeSettings(false);
        controller.setTouchRelay(null);
        controller.shutdown();
        inputBridge.shutdown();
        if (instance == this) instance = null;
    }

    private void relayToShizuku(MotionEvent event) {
        if (!enabled || !inputBridge.isReady()) return;
        int action = event.getActionMasked();
        if (action != MotionEvent.ACTION_DOWN
                && action != MotionEvent.ACTION_MOVE
                && action != MotionEvent.ACTION_UP
                && action != MotionEvent.ACTION_CANCEL) {
            return;
        }

        inputBridge.sendTouch(
                action,
                event.getDownTime(),
                event.getEventTime(),
                event.getRawX(),
                event.getRawY());
    }
}
