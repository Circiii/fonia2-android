package tech.fonia.features.grownups

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tech.fonia.features.reading.ReadingReportPdf
import tech.fonia.store.Profile
import java.io.File
import java.time.LocalDate

/**
 * The PDF for the logoped drawn on a device (iOS `PracticeReportTests.pdf` and `pageLimit`): one to three pages, and
 * many sounds with long notes still stop at three. Each first page is saved next to the file as a PNG, to look at.
 */
@RunWith(AndroidJUnit4::class)
class PracticeReportPdfTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fonts = ReadingReportPdf.Fonts.from(context.assets)
    private val today = LocalDate.of(2026, 11, 18)

    private fun sound(letter: String, rung: Int, weeks: Int) = PracticeReport.SoundPart(
        letter = letter,
        title = "Sunetul $letter",
        rung = rung,
        rungs = 10,
        lesson = "În mijlocul cuvântului",
        lessonDetail = "Cuvinte cu $letter la mijloc",
        confirmedByAdult = letter == "S",
        attempts = 6,
        days = 2,
        phone = PracticeReport.Tally(good = 3, almost = 1, notYet = 1, none = 1),
        adult = PracticeReport.Tally(good = 3, almost = 1, notYet = 0, none = 2),
        compared = 3,
        agreed = 2,
        weeks = (0 until weeks).map { PracticeReport.Week(LocalDate.of(2026, 9, 7).plusWeeks(it.toLong()), (it * 3) % 7) },
    )

    private fun report(sounds: List<PracticeReport.SoundPart>, notes: List<PracticeReport.Note>) = PracticeReport(
        ageBand = Profile.AgeBand.SixToSeven,
        isChild = true,
        chosenSounds = listOf("S", "R"),
        createdOn = today,
        first = LocalDate.of(2026, 9, 7),
        last = LocalDate.of(2026, 11, 17),
        days = 21,
        sessions = 24,
        minutes = 150,
        attempts = 300,
        sounds = sounds,
        notes = notes,
    )

    private fun draw(report: PracticeReport, name: String): Int {
        val folder = PracticeReportPdf.folder(context).apply { mkdirs() }
        val file = File(folder, name)
        val pages = PracticeReportPdf.write(report, fonts, file)
        assertTrue(file.length() > 1_000)
        val shown = ReadingReportPdf.render(file, 1190)
        assertTrue(shown.size == pages)
        File(folder, name.replace(".pdf", "-1.png")).outputStream().use { shown.first().compress(Bitmap.CompressFormat.PNG, 100, it) }
        return pages
    }

    @Test
    fun aShortReportFitsInOneToThreePages() {
        val pages = draw(
            report(
                listOf(sound("S", 4, 3), sound("R", 1, 1)),
                listOf(PracticeReport.Note("12 noiembrie 2026", "A obosit la propoziții. [nume] a cerut pauză.")),
            ),
            "fonia-raport-scurt.pdf",
        )
        assertTrue("$pages", pages in 1..PracticeReportPdf.MAX_PAGES)
    }

    @Test
    fun manySoundsAndLongNotesStillStopAtThreePages() {
        val sounds = listOf("S", "Ș", "Z", "J", "Ț", "R").mapIndexed { index, letter -> sound(letter, index + 1, 12) }
        val notes = (1..28).map { PracticeReport.Note("$it noiembrie 2026", "Am lucrat la sunete și a mers bine azi. ".repeat(17).take(PracticeReport.NOTE_LENGTH) + "…") }
        val pages = draw(report(sounds, notes), "fonia-raport-lung.pdf")
        assertTrue("$pages", pages in 1..PracticeReportPdf.MAX_PAGES)
    }
}
