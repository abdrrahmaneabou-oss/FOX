package com.ponie.dayov12.ui;

import android.content.Context;
import android.view.MotionEvent;

/** Process-wide owner for the independent fourth Freeze analog control. */
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
    private final FreezeInputBridge inputBridge;
    private FreezeAnalogSettingsOverlay settingsOverlay;
    private boolean enabled;

    private FreezeAnalogManager(Context context) {
        this.context = context;
        this.controller = new FreezeAnalogController(context);
        this.controller.setSettingsRequestListener(this);
        this.inputBridge = new FreezeInputBridge(context, this);
    }

    /** Follows the same lifetime as the existing floating controls. */
    synchronized void setEnabled(boolean value) {
        if (enabled == value) return;
        enabled = value;
        if (value) {
            inputBridge.start();
            if (inputBridge.isReady()) controller.show();
        } else {
            closeSettings(false);
            controller.hide();
            inputBridge.stop();
        }
    }

    synchronized boolean isEnabled() { return enabled; }

    /** Called only after the privileged system monitor is actually ready. */
    synchronized void onInputBridgeReady(boolean ready) {
        if (!enabled || !ready) {
            closeSettings(false);
            controller.hide();
            return;
        }
        controller.show();
    }

    /**
     * Dispatch one copied system MotionEvent without intercepting the original event.
     * MOVE/CANCEL may contain several pointers, so every active pointer is offered to
     * the controller while DOWN/UP uses only the action pointer.
     */
    void onGlobalMotion(int action, int actionIndex, int[] ids, float[] xs, float[] ys, long eventTime) {
        if (!enabled || ids == null || xs == null || ys == null) return;
        int count = Math.min(ids.length, Math.min(xs.length, ys.length));
        if (count <= 0) return;

        if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_CANCEL) {
            for (int i = 0; i < count; i++) {
                controller.onPointer(action, ids[i], xs[i], ys[i], eventTime);
            }
            return;
        }

        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN
                || action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            int index = Math.max(0, Math.min(actionIndex, count - 1));
            controller.onPointer(action, ids[index], xs[index], ys[index], eventTime);
        }
    }

    @Override public synchronized void onFreezeAnalogSettingsRequested(FreezeAnalogController owner) {
        if (!enabled || !inputBridge.isReady()) return;
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
        inputBridge.shutdown();
        if (instance == this) instance = null;
    }
}
