package com.torqeedo.controller.protocol

import android.util.Log
import kotlin.math.abs

class SteerSensorProcessor {
    companion object {
        const val DEFAULT_BIAS = 2048
        const val TABLE_SIZE = 128
        private const val TAG = "SteerSensorProcessor"
    }

    var bias1: Int = DEFAULT_BIAS
    var bias2: Int = DEFAULT_BIAS

    private val ratioTable = FloatArray(TABLE_SIZE)
    private val angleTable = FloatArray(TABLE_SIZE)

    init {
        resetTable()
    }

    fun resetTable() {
        for (i in 0 until TABLE_SIZE) {
            // Default range -45 to 45 degrees
            val angle = (i - 64).toFloat() * (90f / 128f)
            angleTable[i] = angle
            // Initial assumption: ratio is linear to angle (1.0 at 22.5 deg)
            ratioTable[i] = angle / 22.5f
        }
    }

    /**
     * Calculates the steering angle based on raw ADC values from Sensor A and Sensor B.
     */
    fun calculateAngle(rawA: Int, rawB: Int): Float {
        val ratio = getRatio(rawA, rawB)
        return interpolate(ratio)
    }

    fun getRatio(rawA: Int, rawB: Int): Float {
        val valA = (rawA - bias1).toFloat()
        val valB = (rawB - bias2).toFloat()
        // Use a small epsilon to avoid division by zero
        val denom = if (abs(valB) < 0.1f) (if (valB < 0) -0.1f else 0.1f) else valB
        return valA / denom
    }

    private fun interpolate(ratio: Float): Float {
        // Find if the ratio table is monotonic ascending or descending
        val isAscending = ratioTable[0] < ratioTable[TABLE_SIZE - 1]
        
        var foundIdx = -1
        if (isAscending) {
            val res = ratioTable.binarySearch(ratio)
            if (res >= 0) return angleTable[res]
            foundIdx = -(res + 1)
        } else {
            // Linear search for descending or non-standard table
            for (i in 0 until TABLE_SIZE - 1) {
                val r0 = ratioTable[i]
                val r1 = ratioTable[i+1]
                if ((ratio >= r0 && ratio <= r1) || (ratio <= r0 && ratio >= r1)) {
                    foundIdx = i + 1
                    break
                }
            }
        }

        if (foundIdx <= 0) return angleTable[0]
        if (foundIdx >= TABLE_SIZE) return angleTable[TABLE_SIZE - 1]
        
        val r0 = ratioTable[foundIdx - 1]
        val r1 = ratioTable[foundIdx]
        val a0 = angleTable[foundIdx - 1]
        val a1 = angleTable[foundIdx]
        
        if (abs(r1 - r0) < 1e-9f) return a0
        val t = (ratio - r0) / (r1 - r0)
        return a0 + t * (a1 - a0)
    }
    
    fun getRatioTable(): FloatArray = ratioTable.copyOf()

    fun updateTable(ratios: FloatArray) {
        if (ratios.size == TABLE_SIZE) {
            ratios.copyInto(ratioTable)
            Log.d(TAG, "LUT ratio table updated")
        }
    }

    /**
     * Fills the table by interpolating between provided calibration points.
     * Points are (ratio, physical_angle).
     */
    fun fillTableFromPoints(points: List<Pair<Float, Float>>) {
        if (points.size < 2) return
        
        val sortedPoints = points.sortedBy { it.second } // Sort by physical angle
        
        for (i in 0 until TABLE_SIZE) {
            val targetAngle = (i - 64).toFloat() * (90f / 128f) // LUT maps -45..45 physical degrees
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
                val t = (targetAngle - p0.second) / (p1.second - p0.second)
                ratioTable[i] = p0.first + t * (p1.first - p0.first)
            }
        }
        Log.d(TAG, "LUT filled from ${points.size} points")
    }
}
