package com.gyromapper.core.motion

/**
 * One Euro Filter implementation ported from the official C++/Java version.
 * 
 * Reference: G. Casiez, N. Roussel, and D. Vogel. "1€ Filter: A Simple Speed-based
 * Low-pass Filter for Noisy Input in Interactive Systems."
 */
class OneEuroFilter(
    private val minCutoff: Double = 1.0,
    private val beta: Double = 0.007,
    private val dCutoff: Double = 1.0
) {
    private var xPrev: Double? = null
    private var dxPrev: Double? = null
    private var tPrev: Double? = null

    fun filter(x: Double, t: Double): Double {
        if (xPrev == null || tPrev == null) {
            xPrev = x
            tPrev = t
            dxPrev = 0.0
            return x
        }

        val dt = (t - tPrev!!).coerceAtLeast(0.0001)
        val dx = (x - xPrev!!) / dt
        val edx = lowPass(dx, dxPrev!!, dt, dCutoff)
        dxPrev = edx

        val cutoff = minCutoff + beta * kotlin.math.abs(edx)
        val result = lowPass(x, xPrev!!, dt, cutoff)
        xPrev = x
        tPrev = t
        return result
    }

    private fun lowPass(x: Double, xPrev: Double, dt: Double, cutoff: Double): Double {
        val tau = 1.0 / (2.0 * Math.PI * cutoff)
        val alpha = dt / (tau + dt)
        return xPrev + alpha * (x - xPrev)
    }

    fun reset() {
        xPrev = null
        dxPrev = null
        tPrev = null
    }
}

class OneEuroFilter2D(
    minCutoff: Double = 1.0,
    beta: Double = 0.007,
    dCutoff: Double = 1.0
) {
    private val filterX = OneEuroFilter(minCutoff, beta, dCutoff)
    private val filterY = OneEuroFilter(minCutoff, beta, dCutoff)

    fun filter(x: Double, y: Double, t: Double): Pair<Double, Double> {
        return filterX.filter(x, t) to filterY.filter(y, t)
    }

    fun reset() {
        filterX.reset()
        filterY.reset()
    }
}
