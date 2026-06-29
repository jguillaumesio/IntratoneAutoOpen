package com.intratone.autoopen

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Pure-Kotlin Goertzel DTMF detector.
 *
 * Extracted from [DTMFDetector] so the DSP math can be unit-tested on the JVM
 * without any Android dependencies. Business logic is identical; this class
 * owns the pure functions (Goertzel, digit-map lookup, debounce window,
 * threshold / dominance checks) while [DTMFDetector] handles the Android audio
 * capture and thread.
 */
object DTMFDetectorCore {

    /** Sample rate used by phone audio. */
    const val SAMPLE_RATE: Int = 8000

    /** Block size (~25 ms at [SAMPLE_RATE]) – the Goertzel window. */
    const val BLOCK_SIZE: Int = 205

    /** Normalised-magnitude threshold – tone must be this strong to count. */
    const val THRESHOLD: Double = 0.4

    /** Minimum time between the same digit twice (debounce). */
    const val DEBOUNCE_MS: Long = 80

    /** Low DTMF group frequencies. */
    val LOW_FREQS = doubleArrayOf(697.0, 770.0, 852.0, 941.0)

    /** High DTMF group frequencies. */
    val HIGH_FREQS = doubleArrayOf(1209.0, 1336.0, 1477.0, 1633.0)

    /** DTMF digit map indexed by [lowGroup][highGroup]. */
    val DIGIT_MAP = arrayOf(
        charArrayOf('1', '2', '3', 'A'),
        charArrayOf('4', '5', '6', 'B'),
        charArrayOf('7', '8', '9', 'C'),
        charArrayOf('*', '0', '#', 'D'),
    )

    /**
     * All sixteen DTMF characters for convenience in tests / iteration.
     */
    val ALL_DIGITS: CharArray
        get() = charArrayOf(
            '1', '2', '3', 'A',
            '4', '5', '6', 'B',
            '7', '8', '9', 'C',
            '*', '0', '#', 'D',
        )

    /**
     * Goertzel magnitude of [targetFreq] within [samples] (using the first [n]
     * samples).
     *
     * Returns a normalised magnitude in roughly [0, 1] for a pure tone of the
     * target frequency.
     */
    fun goertzel(samples: DoubleArray, n: Int, targetFreq: Double): Double {
        val k = n.toDouble() * targetFreq / SAMPLE_RATE
        val w = 2.0 * PI * k / n
        val coeff = 2.0 * cos(w)
        var s0 = 0.0
        var s1 = 0.0
        var s2 = 0.0
        for (i in 0 until n) {
            s0 = samples[i] + coeff * s1 - s2
            s2 = s1
            s1 = s0
        }
        val magnitude = s1 * s1 + s2 * s2 - coeff * s1 * s2
        return sqrt(kotlin.math.abs(magnitude)) / n
    }

    /** Index of the maximum value in [arr]. */
    fun findMaxIndex(arr: DoubleArray): Int {
        var maxIdx = 0
        var maxVal = arr[0]
        for (i in 1 until arr.size) {
            if (arr[i] > maxVal) {
                maxVal = arr[i]
                maxIdx = i
            }
        }
        return maxIdx
    }

    /**
     * True if the peak at [peakIdx] is at least twice as strong as every other
     * element of [mags].
     */
    fun isDominant(mags: DoubleArray, peakIdx: Int): Boolean {
        val peak = mags[peakIdx]
        for (i in mags.indices) {
            if (i != peakIdx && mags[i] > peak * 0.5) return false
        }
        return true
    }

    /**
     * Synthesise a single block of audio containing [lowFreq] + [highFreq] at
     * the given [amplitude] (0..1).
     */
    fun generateDTMFBlock(
        lowFreq: Double,
        highFreq: Double,
        amplitude: Double = 0.5,
    ): DoubleArray {
        val samples = DoubleArray(BLOCK_SIZE)
        for (i in samples.indices) {
            val t = i.toDouble() / SAMPLE_RATE
            samples[i] = amplitude * (kotlin.math.sin(2.0 * PI * lowFreq * t) +
                kotlin.math.sin(2.0 * PI * highFreq * t))
        }
        return samples
    }

    /**
     * Synthesise a block that encodes [digit] by picking the correct DTMF
     * frequency pair from [LOW_FREQS] and [HIGH_FREQS].
     *
     * @throws IllegalArgumentException if [digit] is not a valid DTMF character.
     */
    fun generateDTMFBlockForDigit(
        digit: Char,
        amplitude: Double = 0.5,
    ): DoubleArray {
        val upper = digit.uppercaseChar()
        for (r in DIGIT_MAP.indices) {
            for (c in DIGIT_MAP[r].indices) {
                if (DIGIT_MAP[r][c] == upper) {
                    return generateDTMFBlock(LOW_FREQS[r], HIGH_FREQS[c], amplitude)
                }
            }
        }
        throw IllegalArgumentException("Unknown DTMF digit: $digit")
    }

    /**
     * Add white Gaussian noise to [samples] in place. [snrDb] sets the
     * signal-to-noise ratio; 0 dB means signal and noise have equal power,
     * negative dB means more noise.
     */
    fun addNoise(samples: DoubleArray, snrDb: Double = 10.0, seed: Long? = null) {
        val rng = if (seed != null) Random(seed) else Random
        // Measure signal RMS
        var signalPower = 0.0
        for (s in samples) signalPower += s * s
        signalPower /= samples.size
        val signalRms = sqrt(signalPower)

        // Noise RMS that yields the requested SNR.
        val noiseRms = signalRms / kotlin.math.pow(10.0, snrDb / 20.0)

        for (i in samples.indices) {
            val u1 = rng.nextDouble().coerceAtLeast(1e-9)
            val u2 = rng.nextDouble()
            val gaussian = sqrt(-2.0 * kotlin.math.ln(u1)) * cos(2.0 * PI * u2)
            samples[i] += gaussian * noiseRms
        }
    }

    /**
     * Detect the DTMF digit encoded in [samples]. Returns the character or
     * `null` if no valid DTMF tone is present (silence, single frequency,
     * etc.).
     *
     * This is the single public DSP entry point used by [DTMFDetector]'s
     * detection loop – keeping both code paths identical.
     */
    fun detect(samples: DoubleArray, n: Int = samples.size): Char? {
        if (n <= 0) return null
        val read = minOf(n, samples.size)

        // Check amplitude – skip silence.
        var maxAmp = 0.0
        for (i in 0 until read) maxAmp = kotlin.math.max(maxAmp, kotlin.math.abs(samples[i]))
        if (maxAmp < 0.01) return null

        val lowMags = DoubleArray(4)
        val highMags = DoubleArray(4)
        for (i in 0 until 4) {
            lowMags[i] = goertzel(samples, read, LOW_FREQS[i])
            highMags[i] = goertzel(samples, read, HIGH_FREQS[i])
        }

        val lowIdx = findMaxIndex(lowMags)
        val highIdx = findMaxIndex(highMags)

        return if (lowMags[lowIdx] > THRESHOLD && highMags[highIdx] > THRESHOLD &&
            isDominant(lowMags, lowIdx) && isDominant(highMags, highIdx)
        ) {
            DIGIT_MAP[lowIdx][highIdx]
        } else {
            null
        }
    }

    /**
     * Debounce helper: returns `true` if [digit] should be reported based on
     * the previous detection state. Convenience wrapper used by the
     * detection loop in [DTMFDetector].
     */
    fun shouldReport(
        digit: Char,
        lastDetected: Char,
        lastDetectedTimeMs: Long,
        nowMs: Long,
    ): Boolean {
        if (digit == lastDetected && (nowMs - lastDetectedTimeMs) < DEBOUNCE_MS) {
            return false
        }
        return true
    }
}
