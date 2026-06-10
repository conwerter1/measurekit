package ru.measurekit.domain.sensor

import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sign

/**
 * Углы наклона устройства относительно горизонта.
 *
 * pitch — наклон по короткой оси экрана. Диапазон [-180, +180]:
 *         0° когда экран горизонтален вверх; ±90° когда устройство торцом;
 *         ±180° когда экран смотрит вниз.
 * roll  — наклон по длинной оси экрана. Диапазон [-180, +180].
 *
 * Поведение от ориентации экрана НЕ зависит — оси компенсируются согласно
 * текущему повороту дисплея.
 *
 * Реализация:
 *   - SensorManager.getOrientation возвращает pitch в [-90, +90] (использует asin).
 *     Это работает корректно, пока экран смотрит ВВЕРХ (Z-компонента
 *     rotationMatrix[8] положительна).
 *   - Когда экран переворачивается экраном ВНИЗ (Z<0), pitch начинает "отражаться"
 *     обратно — 100° показывается как 80°, 150° как 30°.
 *   - Чтобы получить полный диапазон ±180°, дозеркаливаем по знаку Z:
 *     если Z<0, то realPitch = sign(pitch) * (180 - |pitch|).
 *     Аналогично для roll.
 */
data class Orientation(
    val pitch: Float,
    val roll: Float,
    val available: Boolean = false
)

@Composable
fun rememberOrientation(): State<Orientation> {
    val ctx = LocalContext.current
    val state = remember { mutableStateOf(Orientation(0f, 0f, false)) }

    DisposableEffect(ctx) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val magnetic = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        val alpha = 0.15f
        val accelValues = FloatArray(3)
        val magneticValues = FloatArray(3)
        var hasAccel = false
        var hasMagnetic = false

        val rotationMatrix = FloatArray(9)
        val remappedRotation = FloatArray(9)
        val orientationAngles = FloatArray(3)

        fun displayRotation(): Int {
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ctx.display?.rotation ?: Surface.ROTATION_0
                } else {
                    @Suppress("DEPRECATION")
                    (ctx as? Activity)?.windowManager?.defaultDisplay?.rotation
                        ?: Surface.ROTATION_0
                }
            } catch (e: Exception) {
                Surface.ROTATION_0
            }
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        if (!hasAccel) {
                            event.values.copyInto(accelValues)
                            hasAccel = true
                        } else {
                            for (i in 0..2) {
                                accelValues[i] += alpha * (event.values[i] - accelValues[i])
                            }
                        }
                    }
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        if (!hasMagnetic) {
                            event.values.copyInto(magneticValues)
                            hasMagnetic = true
                        } else {
                            for (i in 0..2) {
                                magneticValues[i] += alpha * (event.values[i] - magneticValues[i])
                            }
                        }
                    }
                }

                if (!hasAccel) return

                val ok = if (hasMagnetic) {
                    SensorManager.getRotationMatrix(
                        rotationMatrix, null, accelValues, magneticValues
                    )
                } else {
                    computeRotationFromAccelOnly(rotationMatrix, accelValues)
                    true
                }
                if (!ok) return

                val (axisX, axisY) = when (displayRotation()) {
                    Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                    Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                    Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                    else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
                }
                SensorManager.remapCoordinateSystem(
                    rotationMatrix, axisX, axisY, remappedRotation
                )
                SensorManager.getOrientation(remappedRotation, orientationAngles)

                val pitchRad = orientationAngles[1]
                val rollRad = orientationAngles[2]

                // Z-компонента после remap: положительная если экран смотрит вверх,
                // отрицательная если вниз. Используем для определения "обратной"
                // полусферы и расширения pitch/roll до полного ±180°.
                val zUp = remappedRotation[8]

                val pitchDeg0 = (pitchRad * 180f / PI.toFloat())
                val rollDeg0 = (rollRad * 180f / PI.toFloat())

                // Если экран смотрит вниз — отражаем pitch и roll
                val pitchDeg = if (zUp < 0f) {
                    val signP = if (pitchDeg0 == 0f) 1f else sign(pitchDeg0)
                    signP * (180f - abs(pitchDeg0))
                } else {
                    pitchDeg0
                }
                val rollDeg = if (zUp < 0f) {
                    val signR = if (rollDeg0 == 0f) 1f else sign(rollDeg0)
                    signR * (180f - abs(rollDeg0))
                } else {
                    rollDeg0
                }

                state.value = Orientation(
                    pitch = pitchDeg,
                    roll = rollDeg,
                    available = true
                )
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        if (accel != null) {
            sm.registerListener(listener, accel, SensorManager.SENSOR_DELAY_GAME)
        }
        if (magnetic != null) {
            sm.registerListener(listener, magnetic, SensorManager.SENSOR_DELAY_GAME)
        }

        onDispose {
            sm.unregisterListener(listener)
        }
    }

    return state
}

private fun computeRotationFromAccelOnly(R: FloatArray, accel: FloatArray) {
    val ax = accel[0]
    val ay = accel[1]
    val az = accel[2]
    val norm = kotlin.math.sqrt(ax * ax + ay * ay + az * az)
    if (norm < 0.001f) {
        R[0] = 1f; R[1] = 0f; R[2] = 0f
        R[3] = 0f; R[4] = 1f; R[5] = 0f
        R[6] = 0f; R[7] = 0f; R[8] = 1f
        return
    }
    val gx = ax / norm
    val gy = ay / norm
    val gz = az / norm
    val invHyp = 1f / kotlin.math.sqrt(gy * gy + gz * gz).coerceAtLeast(0.0001f)
    R[0] = gz * invHyp
    R[1] = 0f
    R[2] = -gx * invHyp / kotlin.math.sqrt(1f - gx * gx).coerceAtLeast(0.0001f)
    R[3] = -gx * gy * invHyp
    R[4] = gz * invHyp
    R[5] = -gx * gz * invHyp
    R[6] = gx
    R[7] = gy
    R[8] = gz
}
