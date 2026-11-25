package com.violinnagger.app

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Decides whether the microphone is hearing a held musical tone in the violin range.
 * Input is mono audio at about 22-24 kHz, one window at a time. No Android APIs here.
 */
class Analyzer(private val sr: Double) {
    data class Result(
        val rms: Float,
        val hz: Double,
        val toned: Boolean,
        val playing: Boolean,
        val credit: Double
    )

    private val gate = 0.006f
    private val clarityMin = 0.88
    private val fMin = 185.0
    private val fMax = 2100.0
    private val centsMax = 50.0
    private val win = 28
    private val need = 0.25

    private val minLag = max(2, floor(sr / fMax).toInt())
    private val maxLag = ceil(sr / fMin).toInt()

    private var prevHz = 0.0
    private val hist = IntArray(win)
    private var pos = 0
    private var credit = 0.0

    private class Pitch(val hz: Double, val clarity: Double)

    private fun pitch(x: FloatArray): Pitch? {
        val n = x.size - maxLag - 2
        if (n < 64) return null
        val nsdf = FloatArray(maxLag + 3)
        var lag = minLag - 1
        while (lag <= maxLag + 1) {
            var r = 0.0
            var m = 0.0
            for (i in 0 until n) {
                val a = x[i].toDouble()
                val b = x[i + lag].toDouble()
                r += a * b
                m += a * a + b * b
            }
            nsdf[lag] = if (m > 0.0) (2.0 * r / m).toFloat() else 0f
            lag++
        }
        lag = minLag - 1
        while (lag <= maxLag && nsdf[lag] > 0f) lag++
        val peakLags = ArrayList<Int>()
        val peakVals = ArrayList<Float>()
        var best = 0f
        while (lag <= maxLag) {
            while (lag <= maxLag && nsdf[lag] <= 0f) lag++
            var pk = -1
            var pv = 0f
            while (lag <= maxLag && nsdf[lag] > 0f) {
                if (nsdf[lag] > pv) {
                    pv = nsdf[lag]
                    pk = lag
                }
                lag++
            }
            if (pk > 0) {
                peakLags.add(pk)
                peakVals.add(pv)
                if (pv > best) best = pv
            }
        }
        if (peakLags.isEmpty()) return null
        val thr = 0.9f * best
        var pick = 0
        for (i in peakLags.indices) {
            if (peakVals[i] >= thr) {
                pick = i
                break
            }
        }
        val k = peakLags[pick]
        var tau = k.toDouble()
        if (k > minLag && k < maxLag) {
            val p0 = nsdf[k - 1].toDouble()
            val p1 = nsdf[k].toDouble()
            val p2 = nsdf[k + 1].toDouble()
            val d = p0 - 2.0 * p1 + p2
            if (d < 0.0) tau = k + 0.5 * (p0 - p2) / d
        }
        return Pitch(sr / tau, peakVals[pick].toDouble())
    }

    fun frame(x: FloatArray, dt: Double): Result {
        var s = 0.0
        for (v in x) s += (v * v).toDouble()
        val rms = sqrt(s / x.size).toFloat()
        var toned = false
        var hz = 0.0
        if (rms >= gate) {
            val p = pitch(x)
            if (p != null && p.clarity >= clarityMin && p.hz >= fMin && p.hz <= fMax) {
                hz = p.hz
                if (prevHz > 0.0 && abs(1200.0 * ln(hz / prevHz) / ln(2.0)) <= centsMax) toned = true
                prevHz = hz
            } else {
                prevHz = 0.0
            }
        } else {
            prevHz = 0.0
        }
        hist[pos] = if (toned) 1 else 0
        pos = (pos + 1) % win
        var sum = 0
        for (v in hist) sum += v
        val playing = sum.toDouble() / win >= need
        if (playing) credit += dt
        return Result(rms, hz, toned, playing, credit)
    }
}
