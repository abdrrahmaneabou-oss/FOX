package com.ponie.dayov12.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * Two-window analog implementation:
 * - the visual window is permanently NOT_TOUCHABLE;
 * - the transparent capture window receives the physical gesture;
 * - once DOWN is captured, the capture window is parked off-screen and the exact
 *   DOWN/MOVE/UP stream is relayed through the user-authorized Shizuku bridge.
 */
final class FreezeAnalogController {
    interface SettingsRequestListener {
        void onFreezeAnalogSettingsRequested(FreezeAnalogController controller);
    }

    private static final long TRIPLE_TAP_WINDOW_MS = 650L;
    private static final int INVALID_POINTER = -1;
    private static final int PARK_DISTANCE_PX = 8192;

    private final Context context;
    private final WindowManager windowManager;
    private final LegacyFreezeBridge freeze;
    private final FreezeAnalogSettings settings;
    private final ShizukuInputBridge inputBridge;
    private final AnalogView visualView;
    private final View captureView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final float density;
    private final float tapSlopPx;

    private WindowManager.LayoutParams visualParams;
    private WindowManager.LayoutParams captureParams;
    private SettingsRequestListener settingsListener;
    private boolean shown;
    private boolean tracking;
    private boolean positionEdit;
    private boolean captureParked;
    private boolean gestureActivatesFreeze;
    private int pointerId = INVALID_POINTER;
    private float downX;
    private float downY;
    private boolean tapCandidate;
    private long firstTapTime;
    private int tapCount;

    FreezeAnalogController(Context context, ShizukuInputBridge inputBridge) {
        this.context = context.getApplicationContext();
        this.windowManager = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
        this.freeze = new LegacyFreezeBridge(this.context);
        this.settings = FreezeAnalogSettings.load(this.context);
        this.inputBridge = inputBridge;
        this.density = this.context.getResources().getDisplayMetrics().density;
        this.tapSlopPx = 12f * density;
        this.visualView = new AnalogView(this.context);
        this.captureView = new View(this.context);
        this.captureView.setBackgroundColor(0x00000000);
        this.captureView.setOnTouchListener(new CaptureTouchListener());
        rebuildParams();
    }

    void setSettingsRequestListener(SettingsRequestListener listener) {
        this.settingsListener = listener;
    }

    synchronized boolean show() {
        if (shown) return true;
        if (!inputBridge.isReady()) return false;
        rebuildParams();
        try {
            windowManager.addView(visualView, visualParams);
            windowManager.addView(captureView, captureParams);
            shown = true;
            captureParked = false;
            visualView.postInvalidate();
            return true;
        } catch (RuntimeException error) {
            try { windowManager.removeView(captureView); } catch (RuntimeException ignored) {}
            try { windowManager.removeView(visualView); } catch (RuntimeException ignored) {}
            shown = false;
            android.util.Log.e("FreezeAnalog", "Cannot add analog overlay", error);
            return false;
        }
    }

    synchronized void hide() {
        cancelHold();
        positionEdit = false;
        if (!shown) return;
        try { windowManager.removeView(captureView); } catch (RuntimeException ignored) {}
        try { windowManager.removeView(visualView); } catch (RuntimeException ignored) {}
        shown = false;
        captureParked = false;
    }

    synchronized boolean isShown() { return shown; }

    synchronized void setPositionEdit(boolean enabled) {
        if (positionEdit == enabled) return;
        cancelHold();
        positionEdit = enabled;
        restoreCaptureWindow();
    }

    synchronized boolean isPositionEdit() { return positionEdit; }

    synchronized void setBasePosition(int x, int y, boolean persist) {
        int screenW = context.getResources().getDisplayMetrics().widthPixels;
        int screenH = context.getResources().getDisplayMetrics().heightPixels;
        int diameter = Math.round(px(settings.baseDp));
        settings.x = clamp(x, 0, Math.max(0, screenW - diameter));
        settings.y = clamp(y, 0, Math.max(0, screenH - diameter));
        if (persist) settings.save(context);
        rebuildParams();
        applyLayout();
    }

    synchronized void setBaseDiameterDp(int diameterDp, boolean persist) {
        settings.baseDp = diameterDp;
        settings.normalize();
        if (persist) settings.save(context);
        rebuildParams();
        applyLayout();
    }

    synchronized void setKnobDiameterDp(int diameterDp, boolean persist) {
        settings.knobDp = diameterDp;
        settings.normalize();
        if (persist) settings.save(context);
        visualView.resetKnob();
        visualView.postInvalidate();
    }

    synchronized int getBaseX() { return settings.x; }
    synchronized int getBaseY() { return settings.y; }
    synchronized int getBaseDiameterDp() { return settings.baseDp; }
    synchronized int getKnobDiameterDp() { return settings.knobDp; }

    synchronized void saveSettings() {
        settings.normalize();
        settings.save(context);
    }

    synchronized void shutdown() {
        hide();
        settingsListener = null;
        mainHandler.removeCallbacksAndMessages(null);
    }

    private boolean contains(float rawX, float rawY) {
        float radius = px(settings.baseDp) / 2f;
        float cx = settings.x + radius;
        float cy = settings.y + radius;
        return distance(rawX - cx, rawY - cy) <= radius;
    }

    private void updateKnob(float rawX, float rawY) {
        float baseRadius = px(settings.baseDp) / 2f;
        float knobRadius = px(settings.knobDp) / 2f;
        float centerX = settings.x + baseRadius;
        float centerY = settings.y + baseRadius;
        FreezeAnalogGeometry.clamp(
                rawX - centerX,
                rawY - centerY,
                baseRadius,
                knobRadius,
                visualView.offset);
        visualView.postInvalidate();
    }

    private void finishHold() {
        if (gestureActivatesFreeze) freeze.setPressed(false);
        tracking = false;
        gestureActivatesFreeze = false;
        pointerId = INVALID_POINTER;
        tapCandidate = false;
        visualView.resetKnob();
        visualView.postInvalidate();
    }

    private void cancelHold() {
        freeze.forceOff();
        tracking = false;
        gestureActivatesFreeze = false;
        pointerId = INVALID_POINTER;
        tapCandidate = false;
        visualView.resetKnob();
        visualView.postInvalidate();
        restoreCaptureWindow();
    }

    private void registerTap(long eventTime) {
        if (tapCount == 0 || eventTime - firstTapTime > TRIPLE_TAP_WINDOW_MS) {
            firstTapTime = eventTime;
            tapCount = 1;
            return;
        }
        tapCount++;
        if (tapCount < 3) return;
        tapCount = 0;
        firstTapTime = 0L;
        final SettingsRequestListener listener = settingsListener;
        if (listener != null) {
            mainHandler.post(() -> listener.onFreezeAnalogSettingsRequested(FreezeAnalogController.this));
        }
    }

    private void rebuildParams() {
        int diameter = Math.max(1, Math.round(px(settings.baseDp)));
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        int common = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;

        visualParams = new WindowManager.LayoutParams(
                diameter,
                diameter,
                type,
                common | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                android.graphics.PixelFormat.TRANSLUCENT);
        visualParams.gravity = Gravity.TOP | Gravity.START;
        visualParams.x = settings.x;
        visualParams.y = settings.y;

        captureParams = new WindowManager.LayoutParams(
                diameter,
                diameter,
                type,
                common,
                android.graphics.PixelFormat.TRANSLUCENT);
        captureParams.gravity = Gravity.TOP | Gravity.START;
        captureParams.x = settings.x;
        captureParams.y = settings.y;
    }

    private void applyLayout() {
        visualView.resetKnob();
        visualView.postInvalidate();
        if (!shown) return;
        captureParked = false;
        try { windowManager.updateViewLayout(visualView, visualParams); }
        catch (RuntimeException error) { android.util.Log.e("FreezeAnalog", "Cannot update visual overlay", error); }
        try { windowManager.updateViewLayout(captureView, captureParams); }
        catch (RuntimeException error) { android.util.Log.e("FreezeAnalog", "Cannot update capture overlay", error); }
    }

    /** Move only the transparent input window away before injecting the duplicate DOWN. */
    private void parkCaptureWindow() {
        if (!shown || captureParked) return;
        captureParams.x = -PARK_DISTANCE_PX - captureParams.width;
        captureParams.y = -PARK_DISTANCE_PX - captureParams.height;
        try {
            windowManager.updateViewLayout(captureView, captureParams);
            captureParked = true;
        } catch (RuntimeException error) {
            android.util.Log.e("FreezeAnalog", "Cannot park capture overlay", error);
        }
    }

    private void restoreCaptureWindow() {
        if (!shown || (!captureParked && captureParams.x == settings.x && captureParams.y == settings.y)) return;
        captureParams.x = settings.x;
        captureParams.y = settings.y;
        try {
            windowManager.updateViewLayout(captureView, captureParams);
            captureParked = false;
        } catch (RuntimeException error) {
            android.util.Log.e("FreezeAnalog", "Cannot restore capture overlay", error);
        }
    }

    private boolean relay(int action, long downTime, long eventTime, float rawX, float rawY) {
        return inputBridge.inject(action, downTime, eventTime, rawX, rawY);
    }

    private float px(int dp) { return dp * density; }

    private static float distance(float dx, float dy) {
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private final class CaptureTouchListener implements View.OnTouchListener {
        private float editStartRawX;
        private float editStartRawY;
        private int editStartBaseX;
        private int editStartBaseY;

        @Override public boolean onTouch(View ignored, MotionEvent event) {
            if (positionEdit) return handlePositionEdit(event);

            final int action = event.getActionMasked();
            switch (action) {
                case MotionEvent.ACTION_DOWN: {
                    if (tracking) return true;
                    pointerId = event.getPointerId(0);
                    float rawX = event.getRawX();
                    float rawY = event.getRawY();
                    tracking = true;
                    downX = rawX;
                    downY = rawY;
                    gestureActivatesFreeze = contains(rawX, rawY);
                    tapCandidate = gestureActivatesFreeze;
                    if (gestureActivatesFreeze) {
                        updateKnob(rawX, rawY);
                        freeze.setPressed(true);
                    }

                    // Park before injecting DOWN so the duplicate starts on the window below.
                    parkCaptureWindow();
                    if (!relay(MotionEvent.ACTION_DOWN, event.getDownTime(), event.getEventTime(), rawX, rawY)) {
                        android.util.Log.w("FreezeAnalog", "DOWN relay was not accepted");
                    }
                    return true;
                }

                case MotionEvent.ACTION_MOVE: {
                    if (!tracking) return true;
                    int index = event.findPointerIndex(pointerId);
                    if (index < 0) return true;
                    float rawX = event.getRawX(index);
                    float rawY = event.getRawY(index);
                    if (gestureActivatesFreeze) {
                        if (distance(rawX - downX, rawY - downY) > tapSlopPx) tapCandidate = false;
                        updateKnob(rawX, rawY);
                    }
                    relay(MotionEvent.ACTION_MOVE, event.getDownTime(), event.getEventTime(), rawX, rawY);
                    return true;
                }

                case MotionEvent.ACTION_UP: {
                    if (!tracking) return true;
                    float rawX = event.getRawX();
                    float rawY = event.getRawY();
                    boolean tap = gestureActivatesFreeze && tapCandidate && contains(rawX, rawY);

                    // Complete the injected stream before restoring the capture window.
                    relay(MotionEvent.ACTION_UP, event.getDownTime(), event.getEventTime(), rawX, rawY);
                    finishHold();
                    if (tap) registerTap(event.getEventTime());
                    restoreCaptureWindow();
                    return true;
                }

                case MotionEvent.ACTION_CANCEL: {
                    if (tracking) {
                        float rawX = event.getRawX();
                        float rawY = event.getRawY();
                        relay(MotionEvent.ACTION_CANCEL, event.getDownTime(), event.getEventTime(), rawX, rawY);
                    }
                    finishHold();
                    tapCount = 0;
                    firstTapTime = 0L;
                    restoreCaptureWindow();
                    return true;
                }

                // The analog owns one physical pointer. Extra pointers are left untouched;
                // the primary DOWN/MOVE/UP stream remains exact and continuous.
                case MotionEvent.ACTION_POINTER_DOWN:
                case MotionEvent.ACTION_POINTER_UP:
                default:
                    return true;
            }
        }

        private boolean handlePositionEdit(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    editStartRawX = event.getRawX();
                    editStartRawY = event.getRawY();
                    editStartBaseX = settings.x;
                    editStartBaseY = settings.y;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    setBasePosition(
                            editStartBaseX + Math.round(event.getRawX() - editStartRawX),
                            editStartBaseY + Math.round(event.getRawY() - editStartRawY),
                            false);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                default:
                    return true;
            }
        }
    }

    private final class AnalogView extends View {
        private final Paint baseFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint baseStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint knobFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint knobStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float[] offset = new float[]{0f, 0f};
        private final RectF bounds = new RectF();

        AnalogView(Context context) {
            super(context);
            setLayerType(View.LAYER_TYPE_HARDWARE, null);
            baseFill.setStyle(Paint.Style.FILL);
            baseFill.setColor(0x3a10141f);
            baseStroke.setStyle(Paint.Style.STROKE);
            baseStroke.setStrokeWidth(Math.max(1f, 1.5f * density));
            baseStroke.setColor(0xa6d8dceb);
            knobFill.setStyle(Paint.Style.FILL);
            knobFill.setColor(0xd6b89aff);
            knobStroke.setStyle(Paint.Style.STROKE);
            knobStroke.setStrokeWidth(Math.max(1f, 1.25f * density));
            knobStroke.setColor(0xfff2f3fa);
        }

        void resetKnob() {
            offset[0] = 0f;
            offset[1] = 0f;
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            float cx = w / 2f;
            float cy = h / 2f;
            float baseR = Math.min(w, h) / 2f - baseStroke.getStrokeWidth();
            float knobR = px(settings.knobDp) / 2f;
            bounds.set(cx - baseR, cy - baseR, cx + baseR, cy + baseR);
            canvas.drawOval(bounds, baseFill);
            canvas.drawOval(bounds, baseStroke);
            canvas.drawCircle(cx + offset[0], cy + offset[1], knobR, knobFill);
            canvas.drawCircle(cx + offset[0], cy + offset[1], knobR, knobStroke);
        }
    }
}
