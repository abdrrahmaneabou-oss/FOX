package com.ponie.dayov12.ui;

/** Pure geometry for the hold-to-freeze analog control. */
final class FreezeAnalogGeometry {
    private FreezeAnalogGeometry() {}

    /**
     * Maximum displacement of the movable knob's center.
     *
     * The user contract is stricter than a normal joystick: the knob edge may
     * never cross the base center. That gives displacement <= knobRadius.
     * We also keep the knob completely inside the base: displacement <=
     * baseRadius - knobRadius. The tighter bound wins.
     */
    static float maxOffset(float baseRadius, float knobRadius) {
        if (baseRadius <= 0f || knobRadius <= 0f) return 0f;
        return Math.max(0f, Math.min(knobRadius, baseRadius - knobRadius));
    }

    /** Clamp dx/dy to the legal radial displacement without changing direction. */
    static void clamp(float dx, float dy, float baseRadius, float knobRadius, float[] out) {
        float max = maxOffset(baseRadius, knobRadius);
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length > max && length > 0f) {
            float scale = max / length;
            dx *= scale;
            dy *= scale;
        }
        out[0] = dx;
        out[1] = dy;
    }
}
