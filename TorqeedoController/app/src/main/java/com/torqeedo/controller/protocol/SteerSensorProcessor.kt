package com.torqeedo.controller.protocol

import kotlin.math.abs

class SteerSensorProcessor {
    companion object {
        const val DEFAULT_BIAS = 2048
        const val TABLE_SIZE = 128
    }

    var bias1: Int = DEFAULT_BIAS
    var bias2: Int = DEFAULT_BIAS

    // A 128-point ratio calibration table.
    // Normalized ratio alongside the physical angle.
    // Established during "zero magnetic field" baseline voltage measurement (BIAS).
    private val ratioTable = FloatArray(TABLE_SIZE)
    private val angleTable = FloatArray(TABLE_SIZE)

    init {
        // Initialize with default linear mapping for demonstration.
        // In practice, this table would be calibrated.
        for (i in 0 until TABLE_SIZE) {
            ratioTable[i] = (i - 64).toFloat() / 32f // Example ratios
            angleTable[i] = (i - 64).toFloat()      // Example angles -64 to 63
        }
    }

    /**
     * Calculates the steering angle based on raw ADC values from Sensor A and Sensor B.
     * Uses a ratio-based approach to normalize against magnetic field strength variations.
     * Established BIAS1 and BIAS2 are subtracted first.
     */
    fun calculateAngle(rawA: Int, rawB: Int): Float {
        val valA = (rawA - bias1).toFloat()
        val valB = (rawB - bias2).toFloat()
        
        // Use a small epsilon to avoid division by zero
        // The ratio is valA / valB as per typical 45-degree Hall sensor placement logic (tangent-like)
        val denom = if (abs(valB) < 1e-6f) 1e-6f else valB
        val ratio = valA / denom

        return interpolate(ratio)
    }

    private fun interpolate(ratio: Float): Float {
        // Nearest-Neighbor Ratio Engine with Interpolation
        var idx = ratioTable.binarySearch(ratio)
        
        if (idx >= 0) return angleTable[idx]
        
        idx = -(idx + 1)
        
        if (idx == 0) return angleTable[0]
        if (idx >= TABLE_SIZE) return angleTable[TABLE_SIZE - 1]
        
        // Interpolate between idx-1 and idx
        val r0 = ratioTable[idx - 1]
        val r1 = ratioTable[idx]
        val a0 = angleTable[idx - 1]
        val a1 = angleTable[idx]
        
        val t = (ratio - r0) / (r1 - r0)
        return a0 + t * (a1 - a0)
    }
    
    fun updateTable(ratios: FloatArray, angles: FloatArray) {
        if (ratios.size == TABLE_SIZE && angles.size == TABLE_SIZE) {
            ratios.copyInto(ratioTable)
            angles.copyInto(angleTable)
        }
    }
}
