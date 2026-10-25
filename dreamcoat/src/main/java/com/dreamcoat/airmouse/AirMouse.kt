package com.dreamcoat.airmouse

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Gyro air mouse. Uses TYPE_GAME_ROTATION_VECTOR and measures each frame's rotation in the
 * world frame, so it works whether the phone is held flat like a remote or upright:
 *  - turning left/right (rotation about gravity) moves the cursor horizontally;
 *  - tilting the top edge up/down (rotation about the phone's horizontal side axis) moves it vertically.
 * Movement is only emitted while the clutch is engaged.
 */
class AirMouse(context: Context, private val onDelta: (dx: Int, dy: Int) -> Unit) : SensorEventListener {

    /** Pixels per radian of rotation. */
    @Volatile var sensitivity = DEFAULT_SENSITIVITY

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

    private val current = FloatArray(9)
    private val previous = FloatArray(9)
    private var hasPrevious = false
    private var remX = 0f
    private var remY = 0f

    @Volatile private var engaged = false

    val isAvailable get() = sensor != null

    fun setClutch(on: Boolean) {
        if (on == engaged) return
        engaged = on
        if (on) {
            hasPrevious = false
            remX = 0f
            remY = 0f
            sensor?.let { sensorManager.registerListener(this, it, SAMPLING_US) }
        } else {
            sensorManager?.unregisterListener(this)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!engaged) return
        SensorManager.getRotationMatrixFromVector(current, event.values)
        if (!hasPrevious) {
            current.copyInto(previous)
            hasPrevious = true
            return
        }
        // Small world-frame rotation: D = R_cur * R_prev^T; axis-angle from its skew part.
        val d = FloatArray(9)
        for (i in 0..2) for (j in 0..2) {
            d[i * 3 + j] = current[i * 3] * previous[j * 3] +
                current[i * 3 + 1] * previous[j * 3 + 1] +
                current[i * 3 + 2] * previous[j * 3 + 2]
        }
        val wx = (d[7] - d[5]) / 2f
        val wy = (d[2] - d[6]) / 2f
        val wz = (d[3] - d[1]) / 2f
        current.copyInto(previous)

        // Phone's side (X) axis in world coords, flattened to horizontal.
        var hx = current[0]
        var hy = current[3]
        val hn = hypot(hx, hy)
        if (hn < 0.2f) return // phone held sideways; pitch axis undefined
        hx /= hn; hy /= hn

        val yaw = wz
        val pitch = wx * hx + wy * hy
        if (abs(yaw) < DEADZONE_RAD && abs(pitch) < DEADZONE_RAD) return

        remX += -yaw * sensitivity
        remY += -pitch * sensitivity
        val dx = remX.toInt()
        val dy = remY.toInt()
        remX -= dx
        remY -= dy
        if (dx != 0 || dy != 0) onDelta(dx, dy)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        const val DEFAULT_SENSITIVITY = 1400f
        private const val DEADZONE_RAD = 0.0012f
        private const val SAMPLING_US = 16_666 // ~60 Hz
    }
}
