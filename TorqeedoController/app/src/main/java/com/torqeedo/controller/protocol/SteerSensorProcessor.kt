package com.torqeedo.controller.protocol

import android.util.Log
import kotlin.math.*

/**
 * Processor for linear Hall sensor feedback, Magnetometer steering, or ToF Distance sensor
 * using a 1D/2D Vector Path Interpolation Engine.
 *
 * This handles non-monotonic responses by treating (SensorA, SensorB) or (MagX, MagY)
 * as a point in 2D space and projects it onto a calibrated steering path.
 * For 1D sensors (like VL53L0X), it uses only the X component (Distance).
 */
class SteerSensorProcessor {
    companion object {
        const val DEFAULT_BIAS = 0
        // Using 129 points for a symmetric table from -100 to 100 with a center at 0.
        const val TABLE_SIZE = 129
        private const val TAG = "SteerSensorProcessor"

        // Beta factors for Low Pass Filter (0.0 to 1.0). Lower = more smoothing.
        private const val LPF_BETA_RAW = 1.0f      // No filtering, firmware already does this
        private const val LPF_BETA_ANGLE = 1.0f      // No filtering
        
        // Minimum signal magnitude sqrt(X^2 + Y^2) to trust the sensor reading.
        const val MIN_MAGNITUDE = 5f
    }

    var is1DMode: Boolean = false

    var bias1: Int = DEFAULT_BIAS
    var bias2: Int = DEFAULT_BIAS

    var useEllipseCorrection: Boolean = false
        set(value) {
            field = value
            rebuildTable()
        }

    private var ellipseA: Float = 1.0f
    private var ellipseB: Float = 1.0f
    private var ellipseTheta: Float = 0.0f

    // Calibrated path in ADC/Mag/Distance space
    private val pathA = FloatArray(TABLE_SIZE)
    private val pathB = FloatArray(TABLE_SIZE)
    private val angleTable = FloatArray(TABLE_SIZE)

    // Filter states
    private var filteredA = 0f
    private var filteredB = 0f
    private var filteredAngle = 0f
    private var firstSample = true

    // Calibration points: Physical Angle -> (Raw X, Raw Y)
    private val calibrationPoints = mutableMapOf<Float, Pair<Float, Float>>()

    // Backward compatibility helpers for 3-point calibration
    var zeroX: Int = 0; var zeroY: Int = 0
    var portX: Int = 0; var portY: Int = 0
    var stbdX: Int = 0; var stbdY: Int = 0

    init {
        resetTable()
    }

    /**
     * Resets to a default arc mapping.
     */
    fun resetTable() {
        val center = (TABLE_SIZE - 1) / 2f
        val steps = (TABLE_SIZE - 1).toFloat()
        for (i in 0 until TABLE_SIZE) {
            val angle = (i - center) * (200f / steps)
            angleTable[i] = angle
            val rad = Math.toRadians(angle.toDouble())
            pathA[i] = (sin(rad) * 1000.0).toFloat()
            pathB[i] = (cos(rad) * 1000.0).toFloat()
        }
        firstSample = true
    }

    fun calibrateZero(x: Int, y: Int) { 
        zeroX = x; zeroY = y 
        addCalibrationPoint(0f, x.toFloat(), y.toFloat())
    }
    
    fun calibratePort(x: Int, y: Int) { 
        portX = x; portY = y 
        addCalibrationPoint(-100f, x.toFloat(), y.toFloat())
    }
    
    fun calibrateStbd(x: Int, y: Int) { 
        stbdX = x; stbdY = y 
        addCalibrationPoint(100f, x.toFloat(), y.toFloat())
    }

    fun addCalibrationPoint(angle: Float, x: Float, y: Float) {
        calibrationPoints[angle] = Pair(x, y)
        rebuildTable()
    }

    fun clearCalibrationPoints() {
        calibrationPoints.clear()
        resetTable()
    }

    fun setEllipse(cx: Float, cy: Float, a: Float, b: Float, angle: Float) {
        bias1 = cx.toInt()
        bias2 = cy.toInt()
        ellipseA = if (a > 0f) a else 1.0f
        ellipseB = if (b > 0f) b else 1.0f
        ellipseTheta = Math.toRadians(angle.toDouble()).toFloat()
        rebuildTable()
    }

    private fun transform(x: Float, y: Float): Pair<Float, Float> {
        if (is1DMode) return Pair(x, 0f)
        
        val dx = x - bias1
        val dy = y - bias2
        if (!useEllipseCorrection) return Pair(dx, dy)
        
        val cosT = cos(ellipseTheta)
        val sinT = sin(ellipseTheta)
        // Rotate to align with axes, then scale. Radius normalized to 1000.
        val curA = ((dx * cosT + dy * sinT) / ellipseA) * 1000f
        val curB = ((-dx * sinT + dy * cosT) / ellipseB) * 1000f
        return Pair(curA, curB)
    }

    /**
     * Calculates the steering angle (percentage) from raw sensor inputs.
     */
    fun calculateAngle(rawA: Int, rawB: Int): Float {
        // Stage 1: Filter raw inputs
        if (firstSample) {
            filteredA = rawA.toFloat()
            filteredB = if (is1DMode) 0f else rawB.toFloat()
        } else {
            filteredA += LPF_BETA_RAW * (rawA - filteredA)
            if (is1DMode) {
                filteredB = 0f
            } else {
                filteredB += LPF_BETA_RAW * (rawB - filteredB)
            }
        }

        val (curA, curB) = transform(filteredA, filteredB)
        
        // Stage 2: Magnitude check (bypass for 1D)
        if (!is1DMode) {
            val mag = sqrt(curA * curA + curB * curB)
            if (mag < MIN_MAGNITUDE) {
                return filteredAngle 
            }
        }

        val rawAngle = findAngleOnPath(curA, curB)

        // Stage 3: Angle Smoothing
        if (firstSample) {
            filteredAngle = rawAngle
            firstSample = false
        } else {
            filteredAngle += LPF_BETA_ANGLE * (rawAngle - filteredAngle)
        }

        return filteredAngle
    }

    /**
     * Finds the physical angle by projecting the current vector (a, b)
     * onto the nearest segment of the calibrated 2D path.
     */
    private fun findAngleOnPath(a: Float, b: Float): Float {
        var minSqDist = Float.MAX_VALUE
        var bestIdx = 0
        var bestT = 0f

        for (i in 0 until TABLE_SIZE - 1) {
            val a0 = pathA[i]
            val b0 = pathB[i]
            val a1 = pathA[i + 1]
            val b1 = pathB[i + 1]

            val da = a1 - a0
            val db = b1 - b0
            val segLenSq = da * da + db * db

            if (segLenSq < 1e-6f) continue

            val pa = a - a0
            val pb = b - b0

            var t = (pa * da + pb * db) / segLenSq
            t = t.coerceIn(0f, 1f)

            val closeA = a0 + t * da
            val closeB = b0 + t * db
            
            val distSq = (a - closeA) * (a - closeA) + (b - closeB) * (b - closeB)
            
            if (distSq < minSqDist) {
                minSqDist = distSq
                bestIdx = i
                bestT = t
            }
        }

        val angleStart = angleTable[bestIdx]
        val angleEnd = angleTable[bestIdx + 1]
        return angleStart + bestT * (angleEnd - angleStart)
    }

    fun rebuildTable() {
        if (calibrationPoints.size < 2) {
            resetTable()
            return
        }

        val sorted = calibrationPoints.toList().sortedBy { it.first }
        
        val center = (TABLE_SIZE - 1) / 2f
        val steps = (TABLE_SIZE - 1).toFloat()
        
        for (i in 0 until TABLE_SIZE) {
            val targetAngle = (i - center) * (200f / steps)
            angleTable[i] = targetAngle

            var p0raw = sorted.first()
            var p1raw = sorted.last()

            if (targetAngle <= p0raw.first) {
                val pt = transform(p0raw.second.first, p0raw.second.second)
                pathA[i] = pt.first
                pathB[i] = pt.second
            } else if (targetAngle >= p1raw.first) {
                val pt = transform(p1raw.second.first, p1raw.second.second)
                pathA[i] = pt.first
                pathB[i] = pt.second
            } else {
                for (j in 0 until sorted.size - 1) {
                    if (targetAngle >= sorted[j].first && targetAngle <= sorted[j+1].first) {
                        p0raw = sorted[j]
                        p1raw = sorted[j+1]
                        break
                    }
                }
                
                val pt0 = transform(p0raw.second.first, p0raw.second.second)
                val pt1 = transform(p1raw.second.first, p1raw.second.second)

                val angleDiff = p1raw.first - p0raw.first
                if (abs(angleDiff) > 1e-6f) {
                    val t = (targetAngle - p0raw.first) / angleDiff
                    pathA[i] = pt0.first + t * (pt1.first - pt0.first)
                    pathB[i] = pt0.second + t * (pt1.second - pt0.second)
                } else {
                    pathA[i] = pt0.first
                    pathB[i] = pt0.second
                }
            }
        }
        firstSample = true
    }

    /**
     * Backward compatibility for 3-point calibration
     */
    fun fillTableFrom3Points() {
        calibrationPoints.clear()
        addCalibrationPoint(-100f, portX.toFloat(), portY.toFloat())
        addCalibrationPoint(0f, zeroX.toFloat(), zeroY.toFloat())
        addCalibrationPoint(100f, stbdX.toFloat(), stbdY.toFloat())
    }

    fun getCalibrationPoints(): List<Triple<Float, Float, Float>> {
        return calibrationPoints.map { Triple(it.key, it.value.first, it.value.second) }
    }
    
    /**
     * Returns a raw magnetic angle for display/debug purposes.
     */
    fun getRawMagAngle(x: Int, y: Int): Float {
        val (curA, curB) = transform(x.toFloat(), y.toFloat())
        return Math.toDegrees(atan2(curB.toDouble(), curA.toDouble())).toFloat()
    }

    fun getPathA(): FloatArray = pathA.copyOf()
    fun getPathB(): FloatArray = pathB.copyOf()
    
    fun getRatio(rawA: Int, rawB: Int): Float {
        val (a, b) = transform(rawA.toFloat(), rawB.toFloat())
        val denom = if (abs(b) < 1.0f) (if (b < 0) -1.0f else 1.0f) else b
        return a / denom
    }
}
