package com.torqeedo.controller.protocol

import kotlin.math.*

/**
 * Least-squares ellipse fitter for magnetometer calibration.
 * Fits Ax^2 + Bxy + Cy^2 + Dx + Ey + F = 0
 */
class MagEllipseCalibrator {

    data class Result(
        val centerX: Float,
        val centerY: Float,
        val axisA: Float,
        val axisB: Float,
        val angle: Float // rotation in radians
    )

    private val samples = mutableListOf<Pair<Float, Float>>()
    private val maxSamples = 1000

    fun addSample(x: Float, y: Float) {
        samples.add(Pair(x, y))
        if (samples.size > maxSamples) {
            samples.removeAt(0)
        }
    }

    fun clear() {
        samples.clear()
    }

    fun getSampleSize() = samples.size

    /**
     * Fits an ellipse to the collected samples.
     * Uses the algebraic distance minimization: x^2 + Ay^2 + Bxy + Cx + Dy + E = 0
     */
    fun fit(): Result? {
        if (samples.size < 6) return null

        // We want to solve M * K = Y
        // where K = [A, B, C, D, E]^T
        // row of M = [y^2, x*y, x, y, 1]
        // row of Y = -x^2

        val mMat = Array(5) { DoubleArray(5) }
        val yVec = DoubleArray(5)

        for (p in samples) {
            val x = p.first.toDouble()
            val y = p.second.toDouble()

            val x2 = x * x
            val y2 = y * y
            val xy = x * y

            val row = doubleArrayOf(y2, xy, x, y, 1.0)
            val yVal = -x2

            for (i in 0..4) {
                for (j in 0..4) {
                    mMat[i][j] += row[i] * row[j]
                }
                yVec[i] += row[i] * yVal
            }
        }

        val k = solve(mMat, yVec) ?: return null

        // Coefficients: Ax^2 + Bxy + Cy^2 + Dx + Ey + F = 0
        // Our normalization was x^2 + k[0]y^2 + k[1]xy + k[2]x + k[3]y + k[4] = 0
        val coeffA = 1.0
        val coeffB = k[1]
        val coeffC = k[0]
        val coeffD = k[2]
        val coeffE = k[3]
        val coeffF = k[4]

        // Center calculation
        val denom = coeffB * coeffB - 4 * coeffA * coeffC
        if (abs(denom) < 1e-10) return null

        val cx = (2.0 * coeffC * coeffD - coeffB * coeffE) / denom
        val cy = (2.0 * coeffA * coeffE - coeffB * coeffD) / denom

        // Remove translation to center to find axes and rotation
        val fPrime = coeffA * cx * cx + coeffB * cx * cy + coeffC * cy * cy + coeffD * cx + coeffE * cy + coeffF

        // Eigenvalues of [[A, B/2], [B/2, C]]
        val term1 = coeffA + coeffC
        val term2 = sqrt((coeffA - coeffC) * (coeffA - coeffC) + coeffB * coeffB)
        val lambda1 = (term1 + term2) / 2.0
        val lambda2 = (term1 - term2) / 2.0

        if (fPrime >= 0) return null 

        val axis1 = sqrt(-fPrime / lambda1)
        val axis2 = sqrt(-fPrime / lambda2)
        
        val angle = if (abs(coeffB) < 1e-10) {
            if (coeffA < coeffC) 0.0 else PI / 2.0
        } else {
            0.5 * atan2(coeffB, coeffA - coeffC)
        }

        return Result(cx.toFloat(), cy.toFloat(), axis1.toFloat(), axis2.toFloat(), angle.toFloat())
    }

    private fun solve(matrix: Array<DoubleArray>, rhs: DoubleArray): DoubleArray? {
        val n = 5
        val a = Array(n) { i -> DoubleArray(n + 1) { j -> if (j < n) matrix[i][j] else rhs[i] } }

        for (i in 0 until n) {
            var maxIdx = i
            for (k in i + 1 until n) {
                if (abs(a[k][i]) > abs(a[maxIdx][i])) maxIdx = k
            }
            val temp = a[i]
            a[i] = a[maxIdx]
            a[maxIdx] = temp

            if (abs(a[i][i]) < 1e-18) return null

            for (k in i + 1 until n) {
                val factor = a[k][i] / a[i][i]
                for (j in i until n + 1) {
                    a[k][j] -= factor * a[i][j]
                }
            }
        }

        val solution = DoubleArray(n)
        for (i in n - 1 downTo 0) {
            var sum = 0.0
            for (j in i + 1 until n) {
                sum += a[i][j] * solution[j]
            }
            solution[i] = (a[i][n] - sum) / a[i][i]
        }
        return solution
    }
    
    /**
     * Maps a raw (x, y) point to a normalized position on a unit circle
     * based on the ellipse parameters.
     * Returns a Pair(normalizedX, normalizedY)
     */
    fun normalize(x: Float, y: Float, res: Result): Pair<Float, Float> {
        // 1. Translate to origin
        val tx = x.toDouble() - res.centerX
        val ty = y.toDouble() - res.centerY
        
        // 2. Rotate to align with axes
        val cosA = cos(-res.angle.toDouble())
        val sinA = sin(-res.angle.toDouble())
        val rx = tx * cosA - ty * sinA
        val ry = tx * sinA + ty * cosA
        
        // 3. Scale to unit circle
        val nx = rx / res.axisA
        val ny = ry / res.axisB
        
        return Pair(nx.toFloat(), ny.toFloat())
    }
}
