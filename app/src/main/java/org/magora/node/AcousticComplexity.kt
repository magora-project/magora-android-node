package org.magora.node

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Acoustic Complexity Index matching firmware detect.py calculate_aci() at pin f0c1940:
 * samples normalized by max abs (0 if digital silence), split into framerate/4 chunks,
 * per chunk sum(|diff(|rfft|)|) / sum(|rfft|) (skipped when the spectrum sum is 0),
 * mean over chunks, rounded to 3 decimals with Python's round-half-even semantics.
 */
object AcousticComplexity {

    fun calculate(samples: FloatArray, sampleRate: Int): Double? {
        var maxVal = 0f
        for (s in samples) {
            val a = abs(s)
            if (a > maxVal) maxVal = a
        }
        if (maxVal == 0f) return 0.0

        val chunkSize = sampleRate / 4
        val chunkCount = samples.size / chunkSize
        if (chunkCount < 2) return null

        var total = 0.0
        var used = 0
        for (i in 0 until chunkCount) {
            val chunk = DoubleArray(chunkSize) { samples[i * chunkSize + it].toDouble() / maxVal }
            val spectrum = rfftMagnitudes(chunk)
            val sum = spectrum.sum()
            if (sum > 0) {
                var flux = 0.0
                for (k in 1 until spectrum.size) flux += abs(spectrum[k] - spectrum[k - 1])
                total += flux / sum
                used++
            }
        }
        if (used == 0) return null
        return BigDecimal(total / used).setScale(3, RoundingMode.HALF_EVEN).toDouble()
    }

    private fun rfftMagnitudes(input: DoubleArray): DoubleArray {
        val n = input.size
        val re = input.copyOf()
        val im = DoubleArray(n)
        fft(re, im)
        val out = DoubleArray(n / 2 + 1)
        for (k in out.indices) out[k] = hypot(re[k], im[k])
        return out
    }

    /** Mixed-radix decimation-in-time FFT (any length; radix-2/3/5-heavy sizes are fastest). */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        if (n <= 1) return
        val p = smallestPrimeFactor(n)
        val m = n / p
        val subRe = Array(p) { DoubleArray(m) }
        val subIm = Array(p) { DoubleArray(m) }
        for (l in 0 until p) {
            var k = 0
            var idx = l
            while (k < m) {
                subRe[l][k] = re[idx]
                subIm[l][k] = im[idx]
                idx += p
                k++
            }
        }
        for (l in 0 until p) fft(subRe[l], subIm[l])
        for (k in 0 until n) {
            val r = k % m
            var sr = 0.0
            var si = 0.0
            for (l in 0 until p) {
                val angle = -2.0 * Math.PI * l * k / n
                val c = cos(angle)
                val s = sin(angle)
                sr += subRe[l][r] * c - subIm[l][r] * s
                si += subRe[l][r] * s + subIm[l][r] * c
            }
            re[k] = sr
            im[k] = si
        }
    }

    private fun smallestPrimeFactor(n: Int): Int {
        if (n % 2 == 0) return 2
        var f = 3
        while (f.toLong() * f <= n) {
            if (n % f == 0) return f
            f += 2
        }
        return n
    }
}
