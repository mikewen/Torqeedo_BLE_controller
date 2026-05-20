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
        // where K = [C, B, D, E, F]^T  (matching coefficients below)
        // normalized equation: x^2 + Bxy + Cy^2 + Dx + Ey + F = 0
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
        // Our normalization was x^2 + k[1]xy + k[0]y^2 + k[2]x + k[3]y + k[4] = 0
        val coeffA = 1.0
        val coeffB = k[1]
        val coeffC = k[0]
        val coeffD = k[2]
        val coeffE = k[3]
        val coeffF = k[4]

        // Center calculation
        // Solve:
        // 2Ax + By + D = 0
        // Bx + 2Cy + E = 0
        val denom = coeffB * coeffB - 4 * coeffA * coeffC
        if (abs(denom) < 1e-10) return null

        val cx = (2.0 * coeffC * coeffD - coeffB * coeffE) / denom
        val cy = (2.0 * coeffA * coeffE - coeffB * coeffD) / denom

        // Remove translation to center to find axes and rotation
        val fPrime = coeffA * cx * cx + coeffB * cx * cy + coeffC * cy * cy + coeffD * cx + coeffE * cy + coeffF

        // Angle of rotation (angle of the axis we'll call axisA)
        val angle = if (abs(coeffB) < 1e-10) {
            if (coeffA < coeffC) 0.0 else PI / 2.0
        } else {
            0.5 * atan2(coeffB, coeffA - coeffC)
        }

        // Eigenvalues for the aligned axes
        // lambda = A cos^2(theta) + B sin(theta)cos(theta) + C sin^2(theta)
        val cosA = cos(angle)
        val sinA = sin(angle)
        val lambdaA = coeffA * cosA * cosA + coeffB * sinA * cosA + coeffC * sinA * sinA
        
        // The other axis is at angle + PI/2
        val cosB = cos(angle + PI / 2.0)
        val sinB = sin(angle + PI / 2.0)
        val lambdaB = coeffA * cosB * cosB + coeffB * sinB * cosB + coeffC * sinB * sinB

        if (fPrime >= 0) {
            // This can happen if the fit results in a hyperbola or the center is not contained.
            // However, we might just have the sign of the whole equation flipped.
            // In algebraic fitting, we usually want fPrime to be negative so that lambda*axis^2 = -fPrime > 0.
            return null
        }

        if (lambdaA <= 0 || lambdaB <= 0) return null // Not an ellipse

        val axisA = sqrt(-fPrime / lambdaA)
        val axisB = sqrt(-fPrime / lambdaB)
        
        return Result(cx.toFloat(), cy.toFloat(), axisA.toFloat(), axisB.toFloat(), angle.toFloat())
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
        // To un-rotate the point, we rotate by -angle
        val cosA = cos(-res.angle.toDouble())
        val sinA = sin(-res.angle.toDouble())
        val rx = tx * cosA - ty * sinA
        val ry = tx * sinA + ty * cosA
        
        // 3. Scale to unit circle
        // rx is aligned with the axis at res.angle, which has length res.axisA
        val nx = rx / res.axisA
        val ny = ry / res.axisB
        
        return Pair(nx.toFloat(), ny.toFloat())
    }
}
