package tech.fonia.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import tech.fonia.designsystem.tokens.ColorToken
import tech.fonia.designsystem.tokens.FoniaCorner
import tech.fonia.designsystem.tokens.FoniaLetterTokens
import tech.fonia.designsystem.tokens.FoniaMotion
import tech.fonia.designsystem.tokens.FoniaPalette
import tech.fonia.designsystem.tokens.FoniaSpace
import tech.fonia.designsystem.tokens.FoniaSticker
import tech.fonia.designsystem.tokens.FoniaTypeScale
import java.io.File
import kotlin.math.pow

/** Tokens: the WCAG table of plan §2.2, the generated JSON in sync, and no raw colours outside FoniaTokens.kt. */
class TokensTest {
    private val root = File(System.getProperty("fonia.root") ?: "..")

    private fun lum(argb: Long): Double {
        fun ch(v: Long): Double { val c = v / 255.0; return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * ch((argb shr 16) and 0xFF) + 0.7152 * ch((argb shr 8) and 0xFF) + 0.0722 * ch(argb and 0xFF)
    }

    private fun ratio(a: Long, b: Long): Double {
        val la = lum(a); val lb = lum(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun check(fg: ColorToken, bg: ColorToken, expected: Double) =
        assertEquals("${fg.name} on ${bg.name}", expected, ratio(fg.argb, bg.argb), 0.015)

    @Test fun contrastTableOfPlan22() {
        val p = FoniaPalette
        check(p.ink, p.bg, 15.75); check(p.ink, p.surface, 16.98); check(p.ink, p.fill, 14.59)
        check(p.inkSecondary, p.bg, 5.43); check(p.inkSecondary, p.surface, 5.86); check(p.inkSecondary, p.fill, 5.03)
        check(p.surface, p.accent, 5.17)
        check(p.accent, p.surface, 5.17); check(p.accent, p.bg, 4.79); check(p.accent, p.fill, 4.44)
        check(p.accentDeep, p.surface, 12.92); check(p.accentDeep, p.bg, 11.98); check(p.accentDeep, p.fill, 11.10)
        check(p.ink, p.voice, 5.49); check(p.ink, p.parrot, 6.31); check(p.ink, p.sun, 11.76)
        check(p.surface, p.voice, 3.09); check(p.surface, p.parrot, 2.69)
        check(p.voiceDeep, p.surface, 9.30); check(p.voiceDeep, p.bg, 8.62)
        check(p.parrotDeep, p.surface, 8.34); check(p.parrotDeep, p.bg, 7.73)
        check(p.sunDeep, p.surface, 5.10); check(p.sunDeep, p.bg, 4.73)
        check(p.sunDeep, p.sun, 3.53)
        // the sticker reads at >= 3:1 on Paper through its rim and on Stage through its face
        for ((name, set) in FoniaSticker.tokens) {
            assertTrue("$name rim on bg", ratio(set[2].argb, p.bg.argb) >= 3.0)
        }
        assertTrue("Voice face on accent", ratio(0xFFFFF6E6, p.accent.argb) >= 4.8)
    }

    @Test fun fourteenRolesAndNoRed() {
        assertEquals(14, FoniaPalette.roles.size)
        for (t in FoniaPalette.roles) {
            val r = (t.argb shr 16) and 0xFF; val g = (t.argb shr 8) and 0xFF; val b = t.argb and 0xFF
            assertTrue("${t.name} looks red", !(r > 180 && g < 90 && b < 90))
        }
    }

    @Test fun generatedJsonMatchesFoniaTokensKt() {
        val json = TokensJson.render()
        val file = File(root, "spec/tokens/fonia.tokens.json")
        if (System.getProperty("fonia.writeTokens") == "true") {
            file.parentFile.mkdirs(); file.writeText(json)
        }
        if (!file.isFile) fail("missing $file: run ./gradlew :core:designsystem:testDebugUnitTest -Pfonia.writeTokens=true")
        assertEquals("spec/tokens/fonia.tokens.json is stale: rerun with -Pfonia.writeTokens=true", json, file.readText().replace("\r\n", "\n"))
    }

    @Test fun noRawColoursOutsideTheTokenFile() {
        val patterns = listOf(
            Regex("""Color\(\s*0x"""), Regex("""Color\(\s*red"""), Regex("""#[0-9A-Fa-f]{6}\b"""),
            Regex("""Color\.(Red|Green|Blue|Yellow|Magenta|Cyan|Gray|Black|White|LightGray|DarkGray)\b"""), Regex("""parseColor"""),
        )
        val dirs = listOf("design/src/main/kotlin", "app/src/main/kotlin").map { File(root, it) }
        val offenders = mutableListOf<String>()
        var scanned = 0
        for (d in dirs) d.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            scanned++
            if (f.path.replace('\\', '/').endsWith("tokens/FoniaTokens.kt")) return@forEach
            f.readLines().forEachIndexed { i, line ->
                val code = line.substringBefore("//")
                if (patterns.any { it.containsMatchIn(code) }) offenders += "${f.relativeTo(root)}:${i + 1}: ${line.trim()}"
            }
        }
        assertTrue("scanned nothing under $root", scanned > 5)
        assertTrue("raw colours:\n" + offenders.joinToString("\n"), offenders.isEmpty())
        // the one XML mirror (window background before Compose draws) must equal the token
        val xml = File(root, "app/src/main/res/values/colors.xml").readText()
        assertTrue("colors.xml fonia_bg != FoniaPalette.bg", xml.contains("#FF" + FoniaPalette.bg.hex.removePrefix("#")))
    }
}

/** Renders FoniaTokens.kt as W3C design tokens (`$value` / `$type`), deterministic, for spec/tokens. */
object TokensJson {
    private const val D = "$"

    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun fmt(v: Float): String = if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()

    private fun color(t: ColorToken): String {
        val a = (t.argb ushr 24) and 0xFF
        val hex = if (a == 0xFFL) t.hex else t.hex + a.toString(16).uppercase().padStart(2, '0')
        val desc = t.use + if (t.forbidden.isNotEmpty()) " | never: " + t.forbidden else ""
        return "{ \"${D}type\": \"color\", \"${D}value\": ${q(hex)}, \"${D}description\": ${q(desc)} }"
    }

    private fun tok(type: String, value: String, desc: String? = null) =
        "{ \"${D}type\": \"$type\", \"${D}value\": $value" + (desc?.let { ", \"${D}description\": ${q(it)}" } ?: "") + " }"

    private fun group(name: String, entries: List<Pair<String, String>>, last: Boolean = false) =
        "  ${q(name)}: {\n" + entries.joinToString(",\n") { (k, v) -> "    ${q(k)}: $v" } + "\n  }" + (if (last) "\n" else ",\n")

    fun render(): String {
        val sb = StringBuilder("{\n")
        sb.append("  \"${D}description\": \"Fonia 2 design tokens, GENERATED from core/designsystem tokens/FoniaTokens.kt by TokensTest. Do not edit.\",\n")
        sb.append(group("color", FoniaPalette.roles.map { it.name to color(it) } + listOf(
            "stickerShadow" to color(FoniaPalette.stickerShadow),
            "sheetShadow" to color(FoniaPalette.sheetShadow),
            "stickerGloss" to color(FoniaPalette.stickerGloss),
        )))
        sb.append(group("sticker", FoniaSticker.tokens.map { (name, l) ->
            name to "{ \"face\": ${color(l[0])}, \"edge\": ${color(l[1])}, \"rim\": ${color(l[2])} }"
        }))
        sb.append(group("type", listOf("fontFamily" to tok("fontFamily", "\"Allo\"")) + FoniaTypeScale.all.map { t ->
            t.name to tok(
                "typography",
                "{ \"fontFamily\": \"Allo\", \"fontSize\": \"${fmt(t.sizeSp)}sp\", \"fontWeight\": ${fmt(t.wght)}, \"lineHeight\": \"${fmt(t.lineHeightSp)}sp\", \"ROND\": ${fmt(t.rond)} }",
                t.use,
            )
        } + listOf(
            "rondGrown" to tok("number", fmt(FoniaTypeScale.ROND_GROWN)),
            "heroLetter" to tok("dimension", "\"${fmt(FoniaTypeScale.heroLetter.value)}dp\""),
        )))
        val sp = listOf(
            "xxs" to FoniaSpace.xxs, "xs" to FoniaSpace.xs, "s" to FoniaSpace.s, "m" to FoniaSpace.m, "l" to FoniaSpace.l,
            "xl" to FoniaSpace.xl, "xxl" to FoniaSpace.xxl, "pageKid" to FoniaSpace.pageKid, "pageGrown" to FoniaSpace.pageGrown,
        )
        sb.append(group("space", sp.map { (n, v) -> n to tok("dimension", "\"${fmt(v.value)}dp\"") }))
        val co = listOf("field" to FoniaCorner.field, "chip" to FoniaCorner.chip, "card" to FoniaCorner.card, "sheet" to FoniaCorner.sheet)
        sb.append(group("corner", co.map { (n, v) -> n to tok("dimension", "\"${fmt(v.value)}dp\"") } + listOf("button" to tok("string", "\"capsule\""))))
        sb.append(group("motion", FoniaMotion.springs.map { s ->
            s.name to tok("spring", "{ \"dampingRatio\": ${fmt(s.dampingRatio)}, \"stiffness\": ${fmt(s.stiffness)} }", s.use)
        } + listOf(
            "quick" to tok("duration", "\"${FoniaMotion.QUICK_MS}ms\""),
            "standard" to tok("duration", "\"${FoniaMotion.STANDARD_MS}ms\""),
            "slow" to tok("duration", "\"${FoniaMotion.SLOW_MS}ms\""),
            "out" to tok("cubicBezier", "[" + FoniaMotion.outEasing.joinToString(", ") { fmt(it) } + "]"),
            "stagger" to tok("duration", "\"${FoniaMotion.STAGGER_MS}ms\""),
            "staggerMax" to tok("duration", "\"${FoniaMotion.STAGGER_MAX_MS}ms\""),
            "particlesMax" to tok("number", "${FoniaMotion.PARTICLES_MAX}"),
            "trembleFps" to tok("number", "${FoniaMotion.TREMBLE_FPS}"),
        )))
        val L = FoniaLetterTokens
        val lt = listOf(
            "wghtMin" to L.WGHT_MIN, "wghtMax" to L.WGHT_MAX, "scaleMin" to L.SCALE_MIN, "scaleMax" to L.SCALE_MAX, "scalePop" to L.SCALE_POP,
            "rimMinEm" to L.RIM_MIN_EM, "rimMaxEm" to L.RIM_MAX_EM, "edgeMinEm" to L.EDGE_MIN_EM, "edgeMaxEm" to L.EDGE_MAX_EM,
            "shadowDxEm" to L.SHADOW_DX_EM, "shadowDyEm" to L.SHADOW_DY_EM, "deflateRate" to L.DEFLATE_RATE,
            "rondSharp" to L.ROND_SHARP, "rondRound" to L.ROND_ROUND, "energyScale" to L.ENERGY_SCALE,
        )
        sb.append(group("letter", lt.map { (n, v) -> n to tok("number", fmt(v)) }, last = true))
        sb.append("}\n")
        return sb.toString()
    }
}
