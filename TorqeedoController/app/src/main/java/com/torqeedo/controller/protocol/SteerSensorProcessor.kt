package com.torqeedo.controller.protocol

import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Processor for linear Hall sensor feedback or Magnetometer steering using a 2D Vector Path Interpolation Engine.
 *
 * This handles non-monotonic responses by treating (SensorA, SensorB) or (MagX, MagY)
 * as a point in 2D space and projects it onto a calibrated steering path.
 */
class SteerSensorProcessor {
    companion object {
        const val DEFAULT_BIAS = 0
        const val TABLE_SIZE = 128
        private const val TAG = "SteerSensorProcessor"

        // Beta factors for Low Pass Filter (0.0 to 1.0). Lower = more smoothing.
        private const val LPF_BETA_RAW = 0.05f
        private const val LPF_BETA_ANGLE = 0.1f
        
        // Minimum signal magnitude sqrt(X^2 + Y^2) to trust the sensor reading.
        const val MIN_MAGNITUDE = 10f 
    }

    var bias1: Int = DEFAULT_BIAS
    var bias2: Int = DEFAULT_BIAS

    // Calibrated path in ADC/Mag space (relative to BIAS)
    private val pathA = FloatArray(TABLE_SIZE)
    private val pathB = FloatArray(TABLE_SIZE)
    private val angleTable = FloatArray(TABLE_SIZE)

    // Filter states
    private var filteredA = 0f
    private var filteredB = 0f
    private var filteredAngle = 0f
    private var firstSample = true

    // Calibration points: Physical Angle -> (A_vector, B_vector)
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
        for (i in 0 until TABLE_SIZE) {
            val angle = (i - 64).toFloat() * (200f / 128f)
            angleTable[i] = angle
            val rad = Math.toRadians(angle.toDouble())
            pathA[i] = (Math.sin(rad) * 1000.0).toFloat()
            pathB[i] = (Math.cos(rad) * 1000.0).toFloat()
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
        calibrationPoints[angle] = Pair(x - bias1, y - bias2)
        rebuildTable()
    }

    fun clearCalibrationPoints() {
        calibrationPoints.clear()
        resetTable()
    }

    fun setEllipse(cx: Float, cy: Float, a: Float, b: Float, angle: Float) {
        bias1 = cx.toInt()
        bias2 = cy.toInt()
        // Re-adjust all calibration points relative to new bias
        // (In a real scenario, we might want to store raw points and subtract bias during rebuild)
    }

    /**
     * Calculates the steering angle from raw sensor inputs.
     */
    fun calculateAngle(rawA: Int, rawB: Int): Float {
        // Stage 1: Filter raw inputs
        if (firstSample) {
            filteredA = rawA.toFloat()
            filteredB = rawB.toFloat()
        } else {
            filteredA += LPF_BETA_RAW * (rawA - filteredA)
            filteredB += LPF_BETA_RAW * (rawB - filteredB)
        }

        val curA = filteredA - bias1
        val curB = filteredB - bias2
        
        // Stage 2: Magnitude check
        val mag = sqrt(curA * curA + curB * curB)
        if (mag < MIN_MAGNITUDE) {
            return filteredAngle 
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
        if (calibrationPoints.size < 2) return

        val sorted = calibrationPoints.toList().sortedBy { it.first }
        
        for (i in 0 until TABLE_SIZE) {
            val targetAngle = (i - 64).toFloat() * (200f / 128f)
            angleTable[i] = targetAngle

            var p0 = sorted.first()
            var p1 = sorted.last()

            if (targetAngle <= p0.first) {
                pathA[i] = p0.second.first
                pathB[i] = p0.second.second
            } else if (targetAngle >= p1.first) {
                pathA[i] = p1.second.first
                pathB[i] = p1.second.second
            } else {
                for (j in 0 until sorted.size - 1) {
                    if (targetAngle >= sorted[j].first && targetAngle <= sorted[j+1].first) {
                        p0 = sorted[j]
                        p1 = sorted[j+1]
                        break
                    }
                }
                val angleDiff = p1.first - p0.first
                if (abs(angleDiff) > 1e-6f) {
                    val t = (targetAngle - p0.first) / angleDiff
                    pathA[i] = p0.second.first + t * (p1.second.first - p0.second.first)
                    pathB[i] = p0.second.second + t * (p1.second.second - p0.second.second)
                } else {
                    pathA[i] = p0.second.first
                    pathB[i] = p0.second.second
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
        return calibrationPoints.map { Triple(it.key, it.value.first + bias1, it.value.second + bias2) }
    }

    fun getPathA(): FloatArray = pathA.copyOf()
    fun getPathB(): FloatArray = pathB.copyOf()
    
    fun getRatio(rawA: Int, rawB: Int): Float {
        val a = rawA - bias1
        val b = rawB - bias2
        val denom = if (abs(b.toFloat()) < 1.0f) (if (b < 0) -1.0f else 1.0f) else b.toFloat()
        return a.toFloat() / denom
    }
}
