package com.torqeedo.controller.protocol

import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Processor for linear Hall sensor feedback using a 2D Vector Path Interpolation Engine.
 *
 * This handles the case where the magnet passes directly over a sensor, causing
 * non-monotonic ratios. It treats (SensorA, SensorB) as a point in 2D space and
 * projects it onto a calibrated steering arc.
 *
 * Logic Stage:
 * 1. Filter raw ADC values (Sensor A, Sensor B)
 * 2. Subtract calibrated BIAS to get magnetic vectors
 * 3. Find closest line segment on the calibrated 2D path
 * 4. Interpolate physical angle along that segment
 * 5. Smooth the resulting angle output
 */
class SteerSensorProcessor {
    companion object {
        const val DEFAULT_BIAS = 2048
        const val TABLE_SIZE = 128
        private const val TAG = "SteerSensorProcessor"

        // Beta factors for Low Pass Filter (0.0 to 1.0). Lower = more smoothing.
        private const val LPF_BETA_RAW = 0.04f   // Aggressive smoothing for noisy ADC
        private const val LPF_BETA_ANGLE = 0.10f // Smooth the final output angle
        
        // Minimum signal magnitude sqrt(A_vec^2 + B_vec^2) to trust the sensor reading.
        // Below this, we freeze the angle to prevent jumping to endpoints.
        const val MIN_MAGNITUDE = 40f 
    }

    var bias1: Int = DEFAULT_BIAS
    var bias2: Int = DEFAULT_BIAS

    // Calibrated path in ADC space (relative to BIAS)
    private val pathA = FloatArray(TABLE_SIZE)
    private val pathB = FloatArray(TABLE_SIZE)
    private val angleTable = FloatArray(TABLE_SIZE)

    // Filter states
    private var filteredA = 0f
    private var filteredB = 0f
    private var filteredAngle = 0f
    private var firstSample = true

    init {
        resetTable()
    }

    /**
     * Resets to a default arc mapping.
     */
    fun resetTable() {
        for (i in 0 until TABLE_SIZE) {
            val angle = (i - 64).toFloat() * (90f / 128f)
            angleTable[i] = angle
            // Default arc assuming sensors are at +/- 22.5 deg relative to magnet center
            val rad = Math.toRadians(angle.toDouble())
            pathA[i] = (Math.sin(rad + Math.toRadians(22.5)) * 1000.0).toFloat()
            pathB[i] = (Math.sin(rad - Math.toRadians(22.5)) * 1000.0).toFloat()
        }
        firstSample = true
    }

    /**
     * stage 1: Low Pass Filter on raw inputs
     * stage 2: Magnitude check (magnet presence)
     * stage 3: Find nearest segment on the 2D path
     * stage 4: Interpolate angle along that segment
     * stage 5: Final smoothing
     */
    fun calculateAngle(rawA: Int, rawB: Int): Float {
        // Stage 1: Filter raw ADC inputs
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

        // Stage 5: Angle Smoothing
        if (firstSample) {
            filteredAngle = rawAngle
            firstSample = false
        } else {
            // Apply smoothing to the resulting angle
            filteredAngle += LPF_BETA_ANGLE * (rawAngle - filteredAngle)
        }

        return filteredAngle
    }

    /**
     * Finds the physical angle by projecting the current ADC vector (a, b)
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

            // Segment vector
            val da = a1 - a0
            val db = b1 - b0
            val segLenSq = da * da + db * db

            if (segLenSq < 1e-6f) continue

            // Vector from segment start to point
            val pa = a - a0
            val pb = b - b0

            // Projection factor t
            var t = (pa * da + pb * db) / segLenSq
            t = t.coerceIn(0f, 1f)

            // Closest point on this segment
            val closeA = a0 + t * da
            val closeB = b0 + t * db
            
            val distSq = (a - closeA) * (a - closeA) + (b - closeB) * (b - closeB)
            
            if (distSq < minSqDist) {
                minSqDist = distSq
                bestIdx = i
                bestT = t
            }
        }

        // Interpolate angle based on the segment and project factor T
        val angleStart = angleTable[bestIdx]
        val angleEnd = angleTable[bestIdx + 1]
        return angleStart + bestT * (angleEnd - angleStart)
    }

    fun getVectorA(rawA: Int): Float = (if (firstSample) rawA.toFloat() else filteredA) - bias1
    fun getVectorB(rawB: Int): Float = (if (firstSample) rawB.toFloat() else filteredB) - bias2
    
    fun getFilteredA() = filteredA
    fun getFilteredB() = filteredB

    fun updateTable(vectorsA: FloatArray, vectorsB: FloatArray) {
        if (vectorsA.size == TABLE_SIZE && vectorsB.size == TABLE_SIZE) {
            vectorsA.copyInto(pathA)
            vectorsB.copyInto(pathB)
            // Re-generate angle table mapped to indices
            for (i in 0 until TABLE_SIZE) {
                angleTable[i] = (i - 64).toFloat() * (90f / 128f)
            }
            firstSample = true
        }
    }

    /**
     * Rebuilds the 2D path from discrete calibration points.
     * Triple: (A_vector, B_vector, physicalAngle)
     */
    fun fillTableFromPoints(points: List<Triple<Float, Float, Float>>) {
        if (points.size < 2) return

        // Sort by physical angle (Triple is A_vector, B_vector, Angle)
        val sortedPoints = points.sortedBy { it.third }

        for (i in 0 until TABLE_SIZE) {
            val targetAngle = (i - 64).toFloat() * (90f / 128f)
            angleTable[i] = targetAngle

            var p0 = sortedPoints.first()
            var p1 = sortedPoints.last()

            if (targetAngle <= p0.third) {
                pathA[i] = p0.first
                pathB[i] = p0.second
            } else if (targetAngle >= p1.third) {
                pathA[i] = p1.first
                pathB[i] = p1.second
            } else {
                for (j in 0 until sortedPoints.size - 1) {
                    if (targetAngle >= sortedPoints[j].third && targetAngle <= sortedPoints[j+1].third) {
                        p0 = sortedPoints[j]
                        p1 = sortedPoints[j+1]
                        break
                    }
                }
                val angleDiff = p1.third - p0.third
                if (abs(angleDiff) > 1e-6f) {
                    val t = (targetAngle - p0.third) / angleDiff
                    pathA[i] = p0.first + t * (p1.first - p0.first)
                    pathB[i] = p0.second + t * (p1.second - p0.second)
                } else {
                    pathA[i] = p0.first
                    pathB[i] = p0.second
                }
            }
        }
        firstSample = true
    }

    fun getPathA(): FloatArray = pathA.copyOf()
    fun getPathB(): FloatArray = pathB.copyOf()
    
    fun getRatio(rawA: Int, rawB: Int): Float {
        val a = getVectorA(rawA)
        val b = getVectorB(rawB)
        val denom = if (abs(b) < 1.0f) (if (b < 0) -1.0f else 1.0f) else b
        return a / denom
    }
}
