package tech.fonia.dsp

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun anchors(s: Float, sh: Float, count: Int = 5, spread: Float = 200f): List<SibilantAnchor> =
    (0 until count).flatMap { i ->
        val offset = (i.toFloat() - (count - 1).toFloat() / 2f) * spread / maxOf(1, count - 1).toFloat()
        listOf(
            SibilantAnchor(place = SibilantPlace.S, centroidHz = s + offset, peakHz = s - 300f + offset, bandRatioDb = 8f, tiltDb = 18f),
            SibilantAnchor(place = SibilantPlace.SH, centroidHz = sh + offset, peakHz = sh - 500f + offset, bandRatioDb = 24f, tiltDb = -4f),
        )
    }

class FrameFricationTests {
    private fun features(signal: FloatArray): FrameFeatures {
        val spectrum = FrameSpectrum(2048)
        val extractor = FeatureExtractor(spectrum.binCount, testRate.toFloat() / 2048f, DSPThresholds.v1.bands)
        spectrum.compute(signal)
        return extractor.extract(spectrum.power)
    }

    private fun classify(signal: FloatArray, clarity: Float = 0.1f, group: VoiceGroup): FricationFrame {
        val f = features(signal)
        return classifyFrication(
            f, hfFloorDb = f.hfDb - 30f, clarity = clarity,
            norm = TalkerCalibration().norm(group), thresholds = DSPThresholds.v1.fricative,
        )
    }

    @Test
    fun highBandNoiseReadsAsS() {
        val reading = classify(bandNoise(0.1, 5500f, 12000f), group = VoiceGroup.CHILD)
        assertTrue(reading.present)
        assertTrue(reading.sNess > 0.75f, "sNess ${reading.sNess}")
    }

    @Test
    fun midBandNoiseReadsAsSh() {
        val reading = classify(bandNoise(0.1, 2300f, 4800f), group = VoiceGroup.CHILD)
        assertTrue(reading.present)
        assertTrue(reading.sNess < 0.25f, "sNess ${reading.sNess}")
    }

    @Test
    fun aVowelIsNotAFricative() {
        assertFalse(classify(vowel(0.1), clarity = 0.95f, group = VoiceGroup.CHILD).present)
    }

    @Test
    fun theBoundaryIsLowerForMenThanForChildren() {
        val noise = bandNoise(0.1, 3500f, 7500f)
        assertTrue(classify(noise, group = VoiceGroup.MALE).sNess > classify(noise, group = VoiceGroup.CHILD).sNess)
    }

    @Test
    fun expectedPlaceFollowsTheSound() {
        assertEquals(SibilantPlace.S, expectedPlace(forSound = "s"))
        assertEquals(SibilantPlace.S, expectedPlace(forSound = "Ț"))
        assertEquals(SibilantPlace.SH, expectedPlace(forSound = "ș"))
        assertEquals(SibilantPlace.SH, expectedPlace(forSound = "gi"))
        assertNull(expectedPlace(forSound = "l"))
        assertTrue(isVoicedSibilant("z"))
        assertFalse(isVoicedSibilant("s"))
    }
}

class SegmentStatisticsTests {
    @Test
    fun judgesTheSInAsaAndTheShInAsha() {
        val asa = analyze(carrier(sNoise(0.16)))
        val asha = analyze(carrier(shNoise(0.16)))
        assertEquals(1, asa.fricatives.size, "${asa.fricatives}")
        assertEquals(1, asha.fricatives.size, "${asha.fricatives}")
        val s = assertNotNull(asa.fricatives.firstOrNull())
        val sh = assertNotNull(asha.fricatives.firstOrNull())
        assertEquals(SibilantPlace.S, s.place)
        assertEquals(SibilantPlace.SH, sh.place)
        assertTrue(s.end - s.start > 0.08, "$s")
        assertTrue(s.centroidHz > 7500f, "$s")
        assertTrue(sh.centroidHz < 6000f, "$sh")
        assertTrue(s.bandRatioDb < sh.bandRatioDb)
        assertTrue(s.tiltDb > sh.tiltDb)
    }

    @Test
    fun statisticsComeFromTheMiddleOfTheSegment() {
        val segment = assertNotNull(analyze(carrier(sNoise(0.2))).fricatives.firstOrNull())
        val span = segment.end - segment.start
        assertTrue(abs((segment.coreEnd - segment.coreStart) - span / 2) < 0.002, "$segment")
        assertTrue(abs((segment.coreStart + segment.coreEnd) / 2 - (segment.start + segment.end) / 2) < 0.001, "$segment")
    }

    @Test
    fun transitionsAtTheEdgesDoNotDecideThePlace() {
        // Ș-like onset and offset around an S, as formant transitions would smear it. A verdict from
        // the middle 50% still hears S even though a third of the frames lean the other way.
        val hiss = shNoise(0.06, amp = 0.12f) + sNoise(0.12, amp = 0.12f) + shNoise(0.06, amp = 0.12f)
        val segment = assertNotNull(analyze(carrier(hiss)).fricatives.firstOrNull())
        assertEquals(SibilantPlace.S, segment.place)
    }

    @Test
    fun multitaperEstimateIsSteadierThanAFrame() {
        // A single periodogram's flatness saturates near 0.56 on white noise; multitaper blocks do not.
        val generator = LinearCongruential(seed = 21)
        val noise = FloatArray(4096) { generator.unit() * 0.2f - 0.1f }
        val analyzer = MultitaperAnalyzer(testRate, DSPThresholds.v1.bands, DSPThresholds.v1.fricative)
        val stats = analyzer.features(noise)
        assertTrue(stats.flatness > 0.85f, "flatness ${stats.flatness}")
    }

    @Test
    fun weakShapelessFrictionIsDiffuse() {
        val weak = withNoise(
            silence(0.2) + vowel(0.2, amp = 0.05f) + diffuseNoise(0.16) + vowel(0.2, amp = 0.05f) + silence(0.2), amp = 0.003f,
        )
        val segment = assertNotNull(analyze(weak).fricatives.firstOrNull())
        assertTrue(segment.diffuse, "$segment")
        assertTrue(segment.flatness > 0.7f, "$segment")
    }

    @Test
    fun aStrongSIsNotDiffuse() {
        assertFalse(assertNotNull(analyze(carrier(sNoise(0.16))).fricatives.firstOrNull()).diffuse)
    }

    @Test
    fun aFullBandLateralShapeRaisesTheHint() {
        // Enough room noise that the top of the band reads as present, as it does on a real microphone.
        val summary = analyze(withNoise(silence(0.2) + vowel(0.2) + lateralNoise(0.16) + vowel(0.2) + silence(0.2), amp = 0.005f))
        val segment = assertNotNull(summary.fricatives.firstOrNull())
        assertTrue(segment.lateralHint, "$segment")
        assertEquals(SoundOutcome.LateralHint, judgeSibilant(text = "asa", graphemes = listOf("s"), summary = summary))
    }

    @ParameterizedTest
    @MethodSource("normalSibilants")
    fun normalSibilantsDoNotRaiseTheHintAsS(signal: FloatArray, name: String) {
        val segment = assertNotNull(analyze(carrier(signal)).fricatives.firstOrNull())
        if (name == "s") assertFalse(segment.lateralHint, "$segment")
        assertEquals(name, segment.place.rawValue)
    }

    @Test
    fun noHintWithoutTheTopOfTheBand() {
        // The same fall above 11 kHz, but the capture itself stops at 15 kHz, as the bundled MP3s do.
        val cut = shapedNoise(
            0.16, listOf(1500f to -30f, 4800f to -2f, 6500f to 0f, 9500f to -1f, 11000f to -8f, 14800f to -40f), amp = 0.12f, seed = 13,
        )
        val signal = silence(0.2) + vowel(0.2) + cut + vowel(0.2) + silence(0.2)
        assertTrue(analyze(signal).fricatives.all { !it.lateralHint })
    }

    companion object {
        @JvmStatic
        fun normalSibilants(): List<Arguments> =
            listOf(Arguments.of(sNoise(0.16), "s"), Arguments.of(shNoise(0.16), "sh"))
    }
}

class TalkerCalibrationTests {
    @Test
    fun usesThePriorUntilTenTokensExist() {
        val calibration = TalkerCalibration()
        assertEquals(SibilantNorm.Source.PRIOR, calibration.norm(VoiceGroup.FEMALE).source)
        for (anchor in anchors(s = 6000f, sh = 3800f).take(9)) calibration.add(anchor)
        assertEquals(9, calibration.anchors.size)
        val early = calibration.norm(VoiceGroup.FEMALE)
        assertEquals(SibilantNorm.Source.PRIOR, early.source)
        assertEquals(DSPThresholds.v1.sibilant.female.centroidHz, early.centroidHz)
        for (anchor in anchors(s = 6000f, sh = 3800f).takeLast(1)) calibration.add(anchor)
        val fitted = calibration.norm(VoiceGroup.FEMALE)
        assertEquals(SibilantNorm.Source.FITTED, fitted.source)
        assertTrue(abs(fitted.centroidHz - 4900f) < 1f, "centroid ${fitted.centroidHz}")
        assertTrue(fitted.uncertainty < early.uncertainty)
    }

    @Test
    fun tenTokensOfOnePlaceCannotFitABoundary() {
        val calibration = TalkerCalibration()
        val sOnly = anchors(s = 6000f, sh = 3800f).filter { it.place == SibilantPlace.S } +
            anchors(s = 6100f, sh = 3800f).filter { it.place == SibilantPlace.S }
        for (anchor in sOnly) calibration.add(anchor)
        assertEquals(10, calibration.anchors.size)
        assertEquals(SibilantNorm.Source.PRIOR, calibration.norm(VoiceGroup.FEMALE).source)
    }

    @Test
    fun mislabelledAnchorsKeepThePrior() {
        val calibration = TalkerCalibration(anchors = anchors(s = 3800f, sh = 6000f))
        assertEquals(SibilantNorm.Source.PRIOR, calibration.norm(VoiceGroup.FEMALE).source)
    }

    @Test
    fun aLowVoicedTalkerIsNotCalledWrongOnceCalibrated() {
        val prior = TalkerCalibration().norm(VoiceGroup.FEMALE)
        val fitted = TalkerCalibration(anchors = anchors(s = 6000f, sh = 3800f)).norm(VoiceGroup.FEMALE)
        val tokenSNess = { norm: SibilantNorm -> norm.sNess(centroidHz = 6000f, peakHz = 5700f, bandRatioDb = 8f) }
        assertTrue(tokenSNess(prior) < tokenSNess(fitted))
        assertEquals(SibilantPlace.S, fitted.place(tokenSNess(fitted)))
        assertEquals(SibilantPlace.SH, fitted.place(fitted.sNess(centroidHz = 3800f, peakHz = 3300f, bandRatioDb = 24f)))
    }

    @Test
    fun tokensNearTheBoundaryAreUnclear() {
        val fitted = TalkerCalibration(anchors = anchors(s = 6000f, sh = 3800f)).norm(VoiceGroup.FEMALE)
        val middle = fitted.sNess(centroidHz = fitted.centroidHz, peakHz = fitted.peakHz, bandRatioDb = fitted.bandRatioDb)
        assertTrue(abs(middle - 0.5f) < 0.01f)
        assertNull(fitted.place(middle))
    }

    @Test
    fun anchorsThatBarelySeparateKeepTheWiderBand() {
        val close = TalkerCalibration(anchors = anchors(s = 5000f, sh = 4800f, spread = 1500f))
        val norm = close.norm(VoiceGroup.FEMALE)
        assertEquals(SibilantNorm.Source.FITTED, norm.source)
        assertEquals(DSPThresholds.v1.sibilant.priorUncertainty, norm.uncertainty)
    }

    @Test
    fun narrowAnchorsCannotClaimImpossiblePrecision() {
        val tight = TalkerCalibration(anchors = anchors(s = 6000f, sh = 3800f, spread = 0f)).norm(VoiceGroup.FEMALE)
        assertEquals(DSPThresholds.v1.sibilant.minCentroidScaleHz, tight.centroidScaleHz)
    }

    @ParameterizedTest
    @CsvSource("2, 0.45", "3, 0.2", "4, 0.16", "5, 0.12", "9, 0.12")
    fun youngerChildrenGetWiderBands(age: Int, band: Float) {
        val norm = TalkerCalibration(ageYears = age).norm(VoiceGroup.CHILD)
        assertEquals(band, norm.uncertainty)
    }

    @Test
    fun ageOnlyWidensChildren() {
        assertEquals(
            DSPThresholds.v1.sibilant.priorUncertainty,
            TalkerCalibration(ageYears = 2).norm(VoiceGroup.FEMALE).uncertainty,
        )
    }

    @Test
    fun oldAnchorsDropOutSoTheBoundaryFollowsAGrowingChild() {
        val calibration = TalkerCalibration()
        val thresholds = DSPThresholds.v1.copy(sibilant = DSPThresholds.v1.sibilant.copy(maxAnchorsPerPlace = 3))
        for (centroid in listOf(5000f, 5100f, 5200f, 5300f)) {
            calibration.add(
                SibilantAnchor(place = SibilantPlace.S, centroidHz = centroid, peakHz = 0f, bandRatioDb = 0f, tiltDb = 0f),
                thresholds = thresholds,
            )
        }
        assertEquals(listOf(5100f, 5200f, 5300f), calibration.anchors.map { it.centroidHz })
    }

    @Test
    fun survivesEncoding() {
        val calibration = TalkerCalibration(ageYears = 6)
        calibration.add(assertNotNull(analyze(carrier(sNoise(0.16))).fricatives.firstOrNull()), place = SibilantPlace.S)
        val decoded = TalkerCalibration.fromJson(calibration.toJson())
        assertEquals(calibration, decoded)
    }

    @Test
    fun analysisUsesTheCalibration() {
        // A centroid that a female prior calls S, with a talker whose S sits far higher.
        val hiss = sNoise(0.16)
        val plain = assertNotNull(analyze(carrier(hiss)).fricatives.firstOrNull())
        val high = TalkerCalibration(anchors = anchors(s = 14000f, sh = 11000f))
        val calibrated = assertNotNull(analyze(carrier(hiss), calibration = high).fricatives.firstOrNull())
        assertEquals(SibilantPlace.S, plain.place)
        assertEquals(SibilantPlace.SH, calibrated.place)
    }
}
