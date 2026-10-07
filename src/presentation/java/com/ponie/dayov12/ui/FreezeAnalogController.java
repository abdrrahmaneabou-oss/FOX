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

/** Runtime state machine and visual layer for the independent hold-to-Freeze analog. */
final class FreezeAnalogController {
    interface SettingsRequestListener {
        void onFreezeAnalogSettingsRequested(FreezeAnalogController controller);
    }

    interface TouchRelay {
        void relay(MotionEvent event);
    }

    private static final long TRIPLE_TAP_WINDOW_MS = 650L;
    private static final int INVALID_POINTER = -1;

    private final Context context;
    private final WindowManager windowManager;
    private final LegacyFreezeBridge freeze;
    private final FreezeAnalogSettings settings;
    private final AnalogView view;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final float density;
    private final float tapSlopPx;

    private WindowManager.LayoutParams params;
    private SettingsRequestListener settingsListener;
    private TouchRelay touchRelay;
    private boolean shown;
    private boolean tracking;
    private boolean positionEdit;
    private int pointerId = INVALID_POINTER;
    private float downX;
    private float downY;
    private boolean tapCandidate;
    private long firstTapTime;
    private int tapCount;

    FreezeAnalogController(Context context) {
        this.context = context.getApplicationContext();
        this.windowManager = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
        this.freeze = new LegacyFreezeBridge(this.context);
        this.settings = FreezeAnalogSettings.load(this.context);
        this.density = this.context.getResources().getDisplayMetrics().density;
        this.tapSlopPx = 12f * density;
        this.view = new AnalogView(this.context);
        this.view.setOnTouchListener(new AnalogTouchListener());
        rebuildParams();
    }

    void setSettingsRequestListener(SettingsRequestListener listener) {
        this.settingsListener = listener;
    }

    void setTouchRelay(TouchRelay relay) {
        this.touchRelay = relay;
    }

    synchronized void show() {
        if (shown) return;
        rebuildParams();
        try {
            windowManager.addView(view, params);
            shown = true;
            view.postInvalidate();
        } catch (RuntimeException e) {
            android.util.Log.e("FreezeAnalog", "Cannot add analog overlay", e);
        }
    }

    synchronized void hide() {
        cancelHold();
        positionEdit = false;
        if (!shown) return;
        try { windowManager.removeView(view); }
        catch (RuntimeException ignored) {}
        shown = false;
    }

    synchronized boolean isShown() { return shown; }

    synchronized boolean onPointer(int action, int id, float rawX, float rawY, long eventTime) {
        if (!shown || positionEdit) return false;
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                if (tracking || !containsKnob(rawX, rawY)) return false;
                tracking = true;
                pointerId = id;
                downX = rawX;
                downY = rawY;
                tapCandidate = true;
                updateKnob(rawX, rawY);
                freeze.setPressed(true);
                return true;

            case MotionEvent.ACTION_MOVE:
                if (!tracking || id != pointerId) return false;
                if (distance(rawX - downX, rawY - downY) > tapSlopPx) tapCandidate = false;
                updateKnob(rawX, rawY);
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                if (!tracking || id != pointerId) return false;
                boolean tap = tapCandidate && containsKnob(rawX, rawY);
                finishHold();
                if (tap) registerTap(eventTime);
                return true;

            case MotionEvent.ACTION_CANCEL:
                if (!tracking || id != pointerId) return false;
                finishHold();
                tapCount = 0;
                firstTapTime = 0L;
                return true;

            default:
                return false;
        }
    }

    synchronized void setPositionEdit(boolean enabled) {
        if (positionEdit == enabled) return;
        cancelHold();
        positionEdit = enabled;
        rebuildParams();
        applyLayout();
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
        view.resetKnob();
        view.postInvalidate();
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
        touchRelay = null;
        mainHandler.removeCallbacksAndMessages(null);
    }

    private boolean containsKnob(float rawX, float rawY) {
        float baseRadius = px(settings.baseDp) / 2f;
        float knobRadius = px(settings.knobDp) / 2f;
        float cx = settings.x + baseRadius + view.offset[0];
        float cy = settings.y + baseRadius + view.offset[1];
        return distance(rawX - cx, rawY - cy) <= knobRadius;
    }

    private void updateKnob(float rawX, float rawY) {
        float baseRadius = px(settings.baseDp) / 2f;
        float knobRadius = px(settings.knobDp) / 2f;
        float centerX = settings.x + baseRadius;
        float centerY = settings.y + baseRadius;
        FreezeAnalogGeometry.clamp(rawX - centerX, rawY - centerY, baseRadius, knobRadius, view.offset);
        view.postInvalidate();
    }

    private void finishHold() {
        freeze.setPressed(false);
        tracking = false;
        pointerId = INVALID_POINTER;
        tapCandidate = false;
        view.resetKnob();
        view.postInvalidate();
    }

    private void cancelHold() {
        freeze.forceOff();
        tracking = false;
        pointerId = INVALID_POINTER;
        tapCandidate = false;
        view.resetKnob();
        view.postInvalidate();
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
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        WindowManager.LayoutParams next = new WindowManager.LayoutParams(
                diameter, diameter, type, flags, android.graphics.PixelFormat.TRANSLUCENT);
        next.gravity = Gravity.TOP | Gravity.START;
        next.x = settings.x;
        next.y = settings.y;
        params = next;
    }

    private void applyLayout() {
        view.resetKnob();
        view.postInvalidate();
        if (!shown) return;
        try { windowManager.updateViewLayout(view, params); }
        catch (RuntimeException e) { android.util.Log.e("FreezeAnalog", "Cannot update analog overlay", e); }
    }

    private float px(int dp) { return dp * density; }

    private static float distance(float dx, float dy) {
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private final class AnalogTouchListener implements View.OnTouchListener {
        private float editStartRawX;
        private float editStartRawY;
        private int editStartBaseX;
        private int editStartBaseY;

        @Override public boolean onTouch(View ignored, MotionEvent event) {
            if (positionEdit) {
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
                        return true;
                    default:
                        return true;
                }
            }

            final int action = event.getActionMasked();
            final int actionIndex = event.getActionIndex();
            final int id;
            final float rawX;
            final float rawY;

            if (action == MotionEvent.ACTION_MOVE && tracking) {
                int index = event.findPointerIndex(pointerId);
                if (index < 0) return true;
                id = pointerId;
                rawX = event.getRawX(index);
                rawY = event.getRawY(index);
            } else {
                id = event.getPointerId(actionIndex);
                rawX = event.getRawX(actionIndex);
                rawY = event.getRawY(actionIndex);
            }

            boolean handled = onPointer(action, id, rawX, rawY, event.getEventTime());
            boolean capturedStream = handled || tracking;

            TouchRelay relay = touchRelay;
            if (relay != null && capturedStream) {
                MotionEvent copy = MotionEvent.obtain(event);
                try { relay.relay(copy); }
                catch (RuntimeException relayError) {
                    android.util.Log.w("FreezeAnalog", "Touch relay rejected event", relayError);
                } finally {
                    copy.recycle();
                }
            }
            return capturedStream;
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
