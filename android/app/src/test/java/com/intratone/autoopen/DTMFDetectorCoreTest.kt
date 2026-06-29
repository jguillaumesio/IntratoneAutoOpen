package com.intratone.autoopen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.PI

/**
 * Offline unit tests for the Goertzel DTMF detector.
 *
 * These tests run as plain JVM tests (no Android runtime needed) against
 * [DTMFDetectorCore] by synthesising PCM samples with known frequencies and
 * asserting the detector reports the correct digit — or rejects invalid input.
 */
class DTMFDetectorCoreTest {

    // ---------------------------------------------------------------- helpers

    /** Count how many of [detected] match their expected digit. */
    private fun digitErrorRate(detected: List<Char?>, expected: Char): Double {
        val errors = detected.count { it != expected }
        return errors.toDouble() / detected.size
    }

    // ================================================================ GOERTZEL

    @Test
    fun goertzel_pureTone_returnsStrongMagnitude() {
        // A 697 Hz sine at full amplitude must produce a magnitude well above
        // the detector threshold at 697 Hz.
        val sr = DTMFDetectorCore.SAMPLE_RATE
        val n = DTMFDetectorCore.BLOCK_SIZE
        val samples = DoubleArray(n) { i -> sin(2.0 * PI * 697.0 * i / sr) }

        val mag = DTMFDetectorCore.goertzel(samples, n, 697.0)
        assertTrue(
            "Expected strong Goertzel magnitude at on-target frequency, got $mag",
            mag > DTMFDetectorCore.THRESHOLD,
        )
    }

    @Test
    fun goertzel_offTargetFrequency_returnsWeakMagnitude() {
        // A 697 Hz tone must produce a weak magnitude at 1209 Hz.
        val sr = DTMFDetectorCore.SAMPLE_RATE
        val n = DTMFDetectorCore.BLOCK_SIZE
        val samples = DoubleArray(n) { i -> sin(2.0 * PI * 697.0 * i / sr) }

        val mag = DTMFDetectorCore.goertzel(samples, n, 1209.0)
        assertTrue(
            "Expected weak magnitude at off-target frequency, got $mag",
            mag < DTMFDetectorCore.THRESHOLD,
        )
    }

    // ========================================================== ALL 16 DIGITS

    @Test
    fun detect_allDigits_cleanAudio() {
        // Synthesise a clean block for every DTMF digit and verify detection.
        for (digit in DTMFDetectorCore.ALL_DIGITS) {
            val samples = DTMFDetectorCore.generateDTMFBlockForDigit(digit)
            val result = DTMFDetectorCore.detect(samples)
            assertEquals(
                "Clean DTMF for '$digit' should be detected",
                digit.uppercaseChar().let { it },
                result,
            )
        }
    }

    @Test
    fun detect_allDigits_lowercaseInput() {
        // Lowercase a-d must map to uppercase A-D.
        for (lowercase in charArrayOf('a', 'b', 'c', 'd')) {
            val samples = DTMFDetectorCore.generateDTMFBlockForDigit(lowercase)
            val result = DTMFDetectorCore.detect(samples)
            assertNotNull("Lowercase '$lowercase' should be detected", result)
            assertEquals(lowercase.uppercaseChar(), result)
        }
    }

    @Test
    fun detect_allDigits_multipleBlocks_under5PercentError() {
        // Issue #4 acceptance: <5% error rate over many blocks per digit.
        val blocksPerDigit = 50
        val rng = kotlin.random.Random(12345) // deterministic

        for (digit in DTMFDetectorCore.ALL_DIGITS) {
            var detected = 0
            var errors = 0
            repeat(blocksPerDigit) {
                val noiseSamples = DTMFDetectorCore.generateDTMFBlockForDigit(digit)
                DTMFDetectorCore.addNoise(noiseSamples, snrDb = 15.0, seed = rng.nextLong())
                if (DTMFDetectorCore.detect(noiseSamples) == digit) detected++ else errors++
            }
            val rate = errors.toDouble() / blocksPerDigit
            assertTrue(
                "Digit '$digit' error rate $rate exceeds 5% ($errors/$blocksPerDigit)",
                rate < 0.05,
            )
        }
    }

    // ================================================================ NOISE

    @Test
    fun detect_noisyAudio_stillCorrectAtHighSNR() {
        // At 20 dB SNR the detector should be essentially perfect.
        val rng = kotlin.random.Random(42)
        for (digit in DTMFDetectorCore.ALL_DIGITS) {
            repeat(20) {
                val samples = DTMFDetectorCore.generateDTMFBlockForDigit(digit)
                DTMFDetectorCore.addNoise(samples, snrDb = 20.0, seed = rng.nextLong())
                val result = DTMFDetectorCore.detect(samples)
                assertEquals(
                    "Noisy (20 dB) '$digit' should still be detected",
                    digit, result,
                )
            }
        }
    }

    @Test
    fun detect_heavyNoise_tolerates10dB() {
        // At 10 dB SNR error rate must remain under 5%.
        val rng = kotlin.random.Random(99)
        val blocksPerDigit = 50
        for (digit in DTMFDetectorCore.ALL_DIGITS) {
            var errors = 0
            repeat(blocksPerDigit) {
                val samples = DTMFDetectorCore.generateDTMFBlockForDigit(digit)
                DTMFDetectorCore.addNoise(samples, snrDb = 10.0, seed = rng.nextLong())
                if (DTMFDetectorCore.detect(samples) != digit) errors++
            }
            val rate = errors.toDouble() / blocksPerDigit
            assertTrue(
                "Digit '$digit' error rate $rate at 10 dB SNR exceeds 5%",
                rate < 0.05,
            )
        }
    }

    // ========================================================== EDGE CASES

    @Test
    fun detect_silence_returnsNull() {
        val samples = DoubleArray(DTMFDetectorCore.BLOCK_SIZE) { 0.0 }
        assertNull("Silence should not be detected as any digit",
            DTMFDetectorCore.detect(samples))
    }

    @Test
    fun detect_singleLowFrequency_returnsNull() {
        // A single (low) frequency without its high partner must be rejected.
        val sr = DTMFDetectorCore.SAMPLE_RATE
        val n = DTMFDetectorCore.BLOCK_SIZE
        val samples = DoubleArray(n) { i -> sin(2.0 * PI * 697.0 * i / sr) }
        assertNull("Single low frequency should not be detected",
            DTMFDetectorCore.detect(samples))
    }

    @Test
    fun detect_singleHighFrequency_returnsNull() {
        val sr = DTMFDetectorCore.SAMPLE_RATE
        val n = DTMFDetectorCore.BLOCK_SIZE
        val samples = DoubleArray(n) { i -> sin(2.0 * PI * 1336.0 * i / sr) }
        assertNull("Single high frequency should not be detected",
            DTMFDetectorCore.detect(samples))
    }

    @Test
    fun detect_overlappingTones_returnsOneDigit() {
        // Two valid DTMF pairs combined – detector must pick the stronger one.
        val a = DTMFDetectorCore.generateDTMFBlockForDigit('1')
        val b = DTMFDetectorCore.generateDTMFBlockForDigit('5')
        val mixed = DoubleArray(a.size) { i -> 0.7 * a[i] + 0.3 * b[i] }
        val result = DTMFDetectorCore.detect(mixed)
        assertNotNull("Mixed tones should still yield a digit", result)
        assertEquals("Stronger tone '1' should win", '1', result)
    }

    @Test
    fun detect_belowThresholdAmplitude_returnsNull() {
        // A DTMF pair at 1% amplitude must be rejected as silence.
        val samples = DTMFDetectorCore.generateDTMFBlockForDigit('5', amplitude = 0.01)
        assertNull("Very quiet tone should not be detected",
            DTMFDetectorCore.detect(samples))
    }

    @Test
    fun detect_emptyInput_returnsNull() {
        val empty = DoubleArray(0)
        assertNull("Empty input should return null",
            DTMFDetectorCore.detect(empty))
    }

    // ====================================================== HELPER FUNCTIONS

    @Test
    fun findMaxIndex_correctIndex() {
        val arr = doubleArrayOf(0.1, 0.5, 0.3, 0.2)
        assertEquals(1, DTMFDetectorCore.findMaxIndex(arr))
    }

    @Test
    fun findMaxIndex_firstElementMax() {
        val arr = doubleArrayOf(0.9, 0.1, 0.2, 0.3)
        assertEquals(0, DTMFDetectorCore.findMaxIndex(arr))
    }

    @Test
    fun isDominant_peakTwiceOthers() {
        val mags = doubleArrayOf(0.8, 0.3, 0.2, 0.1)
        assertTrue("Peak with 2x margin should be dominant",
            DTMFDetectorCore.isDominant(mags, 0))
    }

    @Test
    fun isDominant_peakNotTwiceOthers() {
        val mags = doubleArrayOf(0.5, 0.4, 0.3, 0.2)
        assertFalse("Peak without 2x margin should not be dominant",
            DTMFDetectorCore.isDominant(mags, 0))
    }

    // ============================================================ DEBOUNCE

    @Test
    fun shouldReport_sameDigit_withinDebounce_returnsFalse() {
        val now = 1000L
        // lastDetected='5', reported at t=950, now=1000 → within 80ms window
        assertFalse(
            DTMFDetectorCore.shouldReport('5', '5', 950L, now),
        )
    }

    @Test
    fun shouldReport_sameDigit_afterDebounce_returnsTrue() {
        val now = 1100L
        // lastDetected='5' at t=950 → 150ms later, well past 80ms debounce
        assertTrue(
            DTMFDetectorCore.shouldReport('5', '5', 950L, now),
        )
    }

    @Test
    fun shouldReport_differentDigit_returnsTrue() {
        assertTrue(
            DTMFDetectorCore.shouldReport('6', '5', 950L, 1000L),
        )
    }

    // ===================================================== DIGIT GENERATION

    @Test
    fun generateDTMFBlock_correctLength() {
        val samples = DTMFDetectorCore.generateDTMFBlock(697.0, 1209.0)
        assertEquals(DTMFDetectorCore.BLOCK_SIZE, samples.size)
    }

    @Test
    fun generateDTMFBlock_allDigits_valid() {
        // Every DTMF digit must produce a non-empty block and be detectable.
        for (digit in DTMFDetectorCore.ALL_DIGITS) {
            val samples = DTMFDetectorCore.generateDTMFBlockForDigit(digit)
            assertEquals(DTMFDetectorCore.BLOCK_SIZE, samples.size)
            val detected = DTMFDetectorCore.detect(samples)
            assertEquals("Generated block for '$digit' must detect correctly",
                digit, detected)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun generateDTMFBlock_unknownDigit_throws() {
        DTMFDetectorCore.generateDTMFBlockForDigit('Z')
    }

    // ================================================= DETECT HELPER CONTRACTS

    @Test
    fun detectWithExplicitBlockSize() {
        // Pass only the first 100 samples – must still work on that prefix.
        val full = DTMFDetectorCore.generateDTMFBlockForDigit('9')
        val result = DTMFDetectorCore.detect(full, n = 100)
        // With fewer samples the frequency resolution is coarser; this just
        // verifies the method doesn't throw and returns a nullable Char.
        // (Resolution assertion is intentionally loose.)
        assertTrue(
            "Partial-block detection should not throw",
            result == '9' || result == null,
        )
    }
}
