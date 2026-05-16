package com.torqeedo.controller.protocol

import android.util.Log
import kotlin.math.abs

/**
 * Processor for linear Hall sensor feedback.
 * Sensor A and B are placed at 45 degrees.
 * Uses a 128-point Ratio Calibration Table and interpolation.
 */
class SteerSensorProcessor {
    companion object {
        const val DEFAULT_BIAS = 2048
        const val TABLE_SIZE = 128
        private const val TAG = "SteerSensorProcessor"
        
        // Beta for Low Pass Filter (0.0 to 1.0)
        // Lower value = more smoothing, but more lag.
        private const val LPF_BETA_RAW = 0.1f   // Smoothing for raw ADC
        private const val LPF_BETA_ANGLE = 0.2f // Smoothing for calculated angle
    }

    var bias1: Int = DEFAULT_BIAS
    var bias2: Int = DEFAULT_BIAS

    private val ratioTable = FloatArray(TABLE_SIZE)
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
     * Resets the LUT to a default linear mapping.
     */
    fun resetTable() {
        for (i in 0 until TABLE_SIZE) {
            val angle = (i - 64).toFloat() * (90f / 128f)
            angleTable[i] = angle
            // Default assumes ratio is proportional to angle
            ratioTable[i] = angle / 22.5f 
        }
        firstSample = true
    }

    /**
     * Calculates the steering angle based on raw ADC values from Sensor A and Sensor B.
     * Applies dual-stage Low Pass Filtering to suppress noise.
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
        
        val ratio = getRatioInternal(filteredA, filteredB)
        val rawAngle = interpolate(ratio)
        
        // Stage 2: Filter the resulting angle to eliminate any remaining jumps
        if (firstSample) {
            filteredAngle = rawAngle
            firstSample = false
        } else {
            // Stronger smoothing for the final angle output
            filteredAngle += LPF_BETA_ANGLE * (rawAngle - filteredAngle)
        }
        
        return filteredAngle
    }

    /**
     * Returns the current ratio using filtered ADC values.
     */
    fun getRatio(rawA: Int, rawB: Int): Float {
        val a = if (firstSample) rawA.toFloat() else filteredA
        val b = if (firstSample) rawB.toFloat() else filteredB
        return getRatioInternal(a, b)
    }

    private fun getRatioInternal(valA: Float, valB: Float): Float {
        val a = valA - bias1
        val b = valB - bias2
        
        // Denominator epsilon: Using a larger epsilon (20 units) to avoid instability
        // when the magnetic field is very weak near Sensor B's bias point.
        val epsilon = 20f
        val denom = if (abs(b) < epsilon) (if (b < 0) -epsilon else epsilon) else b
        
        return a / denom
    }

    private fun interpolate(ratio: Float): Float {
        // Detect monotonic direction of the ratio table
        val isAscending = ratioTable[0] < ratioTable[TABLE_SIZE - 1]
        
        // Boundary clamping
        val rMin = if (isAscending) ratioTable[0] else ratioTable[TABLE_SIZE - 1]
        val rMax = if (isAscending) ratioTable[TABLE_SIZE - 1] else ratioTable[0]
        
        if (ratio <= rMin) return if (isAscending) angleTable[0] else angleTable[TABLE_SIZE - 1]
        if (ratio >= rMax) return if (isAscending) angleTable[TABLE_SIZE - 1] else angleTable[0]

        // Search for the interval containing 'ratio'
        var foundIdx = -1
        for (i in 0 until TABLE_SIZE - 1) {
            val r0 = ratioTable[i]
            val r1 = ratioTable[i + 1]
            // Check if ratio is between r0 and r1 regardless of direction
            if ((ratio >= r0 && ratio <= r1) || (ratio <= r0 && ratio >= r1)) {
                foundIdx = i + 1
                break
            }
        }

        if (foundIdx <= 0) return angleTable[0]
        if (foundIdx >= TABLE_SIZE) return angleTable[TABLE_SIZE - 1]
        
        val r0 = ratioTable[foundIdx - 1]
        val r1 = ratioTable[foundIdx]
        val a0 = angleTable[foundIdx - 1]
        val a1 = angleTable[foundIdx]
        
        // Linear interpolation within the found interval
        val rDiff = r1 - r0
        if (abs(rDiff) < 1e-9f) return a0
        
        val t = (ratio - r0) / rDiff
        return a0 + t * (a1 - a0)
    }
    
    fun getRatioTable(): FloatArray = ratioTable.copyOf()

    fun updateTable(ratios: FloatArray) {
        if (ratios.size == TABLE_SIZE) {
            ratios.copyInto(ratioTable)
            Log.d(TAG, "LUT updated with ${ratios.size} samples")
            // Reset filters to avoid lag jump after calibration update
            firstSample = true
        }
    }

    fun fillTableFromPoints(points: List<Pair<Float, Float>>) {
        if (points.size < 2) return
        
        // Sort points by physical angle to ensure consistent table filling
        val sortedPoints = points.sortedBy { it.second } 
        
        for (i in 0 until TABLE_SIZE) {
            // LUT covers -45 to 45 degrees
            val targetAngle = (i - 64).toFloat() * (90f / 128f)
            angleTable[i] = targetAngle
            
            var p0 = sortedPoints.first()
            var p1 = sortedPoints.last()
            
            if (targetAngle <= p0.second) {
                ratioTable[i] = p0.first
            } else if (targetAngle >= p1.second) {
                ratioTable[i] = p1.first
            } else {
                for (j in 0 until sortedPoints.size - 1) {
                    if (targetAngle >= sortedPoints[j].second && targetAngle <= sortedPoints[j+1].second) {
                        p0 = sortedPoints[j]
                        p1 = sortedPoints[j+1]
                        break
                    }
                }
                val angleDiff = p1.second - p0.second
                if (abs(angleDiff) > 1e-6f) {
                    val t = (targetAngle - p0.second) / angleDiff
                    ratioTable[i] = p0.first + t * (p1.first - p0.first)
                } else {
                    ratioTable[i] = p0.first
                }
            }
        }
        firstSample = true
    }
}
