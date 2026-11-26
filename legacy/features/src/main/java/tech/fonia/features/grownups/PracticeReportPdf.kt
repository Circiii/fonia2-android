package tech.fonia.features.grownups

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.fonia.domain.FoniaContent
import tech.fonia.features.reading.ReadingReportPdf
import tech.fonia.features.reading.ReadingReportPdf.BOTTOM
import tech.fonia.features.reading.ReadingReportPdf.CONTENT
import tech.fonia.features.reading.ReadingReportPdf.HEIGHT
import tech.fonia.features.reading.ReadingReportPdf.LEFT
import tech.fonia.features.reading.ReadingReportPdf.RIGHT
import tech.fonia.features.reading.ReadingReportPdf.TOP
import tech.fonia.features.reading.ReadingReportPdf.WIDTH
import tech.fonia.store.FoniaRepository
import tech.fonia.store.practiceData
import java.io.File
import java.time.LocalDate
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/**
 * Draws a [PracticeReport] as an A4 PDF of at most three pages, on the phone, the way the reading report is drawn
 * ([ReadingReportPdf]): the same page, margins, fonts and colours. Everything that carries meaning is drawn in the
 * ink colour, so it still reads when printed in grey. The file waits in the cache's `export` folder, which the next
 * launch and "Șterge tot" empty, until the adult shares it.
 */
object PracticeReportPdf {
    const val MAX_PAGES = 3

    /** The folder the share sheet reads from, inside the cache folder that `fonia_export_paths.xml` opens. */
    fun folder(context: Context): File = File(context.cacheDir, "export/report")

    /** The report for [profileID] on [today], built from the store's copy without the name. */
    suspend fun report(repository: FoniaRepository, content: FoniaContent, profileID: UUID, today: LocalDate = LocalDate.now()): PracticeReport =
        PracticeReport.build(repository.practiceData(profileID), content, today)

    /** Whether there is a day of practice to report on, for the row in the settings. */
    suspend fun hasPractice(repository: FoniaRepository, profileID: UUID): Boolean =
        repository.sessions(profileID).any { it.endedAt != null || it.attempts > 0 }

    /**
     * Makes the PDF when it's asked for, so a note written a moment ago is in it, and opens the share sheet. With
     * nothing practised yet there is no report, and nothing happens.
     */
    suspend fun share(context: Context, repository: FoniaRepository, content: FoniaContent, profileID: UUID) {
        val report = runCatching { report(repository, content, profileID) }.getOrNull() ?: return
        if (report.isEmpty) return
        val file = withContext(Dispatchers.IO) {
            val folder = folder(context)
            folder.deleteRecursively()
            folder.mkdirs()
            File(folder, report.fileName).also { write(report, ReadingReportPdf.Fonts.from(context.assets), it) }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.export", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(send, "Raportul pentru logoped")
        if (context !is android.app.Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(chooser)
        } catch (_: ActivityNotFoundException) {
        }
    }

    /** Draws every page into [file]; returns how many there are. */
    fun write(report: PracticeReport, fonts: ReadingReportPdf.Fonts, file: File): Int {
        val pages = Pages(fonts, report)
        try {
            pages.header()
            pages.howToRead()
            pages.sounds()
            pages.notes()
            pages.finish()
            file.outputStream().use { pages.document.writeTo(it) }
            return pages.count
        } finally {
            pages.document.close()
        }
    }

    private class Pages(private val fonts: ReadingReportPdf.Fonts, private val report: PracticeReport) {
        val document = PdfDocument()
        var count = 0
            private set
        private var page: PdfDocument.Page? = null
        private var y = TOP

        private val isOpen: Boolean get() = page != null
        private val canvas: Canvas get() = page?.canvas ?: start().canvas

        private val ink = ReadingReportPdf.ink
        private val inkSoft = ReadingReportPdf.inkSoft
        private val line = ReadingReportPdf.line
        private val cloud = ReadingReportPdf.cloud

        fun paint(size: Float, face: Typeface = fonts.regular, color: Int = ink, align: Paint.Align = Paint.Align.LEFT) =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = face
                textSize = size
                this.color = color
                textAlign = align
            }

        private val body = paint(9.5f)
        private val small = paint(8f, color = inkSoft)
        private val strong = paint(9.5f, fonts.semibold)
        private val heading = paint(13f, fonts.bold)

        private fun start(): PdfDocument.Page {
            finishPage()
            count += 1
            val next = document.startPage(PdfDocument.PageInfo.Builder(WIDTH, HEIGHT, count).create())
            page = next
            y = TOP
            return next
        }

        private fun finishPage() {
            val current = page ?: return
            footer(current.canvas)
            document.finishPage(current)
            page = null
        }

        fun finish() {
            if (!isOpen && count == 0) start()
            finishPage()
        }

        /** Whether [height] points still fit, on this page or on a new one within the limit. */
        private fun fits(height: Float): Boolean = (isOpen && y + height <= BOTTOM) || count < MAX_PAGES

        /** Makes room for [height] points, starting a page if there isn't any. */
        private fun room(height: Float) {
            if (!isOpen || y + height > BOTTOM) start()
        }

        private fun layout(text: String, paint: TextPaint, width: Float): StaticLayout =
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(1.5f, 1.12f)
                .setIncludePad(false)
                .build()

        private fun draw(layout: StaticLayout, x: Float, top: Float, on: Canvas = canvas) {
            on.save()
            on.translate(x, top)
            layout.draw(on)
            on.restore()
        }

        private fun fill(rect: RectF, color: Int, radius: Float = 0f) {
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
            if (radius > 0) canvas.drawRoundRect(rect, radius, radius, fill) else canvas.drawRect(rect, fill)
        }

        private fun stroke(rect: RectF, color: Int, width: Float = 0.8f, radius: Float = 0f) {
            val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                strokeWidth = width
                style = Paint.Style.STROKE
            }
            if (radius > 0) canvas.drawRoundRect(rect, radius, radius, pen) else canvas.drawRect(rect, pen)
        }

        private fun rule(x0: Float, y0: Float, x1: Float, y1: Float, color: Int, width: Float, on: Canvas = canvas) {
            on.drawLine(x0, y0, x1, y1, Paint().apply {
                this.color = color
                strokeWidth = width
            })
        }

        private fun paragraph(text: String, paint: TextPaint = body, after: Float = 6f) {
            val laid = layout(text, paint, CONTENT)
            room(laid.height.toFloat())
            draw(laid, LEFT, y)
            y += laid.height + after
        }

        private fun section(title: String) {
            room(60f)
            y += 10f
            val laid = layout(title, heading, CONTENT)
            draw(laid, LEFT, y)
            y += laid.height + 3f
            fill(RectF(LEFT, y, LEFT + 28f, y + 2f), ink)
            y += 10f
        }

        private fun footer(on: Canvas) {
            val top = BOTTOM + 16f
            rule(LEFT, top, RIGHT, top, line, 0.8f, on)
            on.drawText(PracticeReport.page(count), RIGHT, top + 15f, paint(8f, color = inkSoft, align = Paint.Align.RIGHT))
            draw(layout(PracticeReport.FOOTER, paint(8f, color = inkSoft), CONTENT - 70f), LEFT, top + 7f, on)
        }

        /** Title, the facts as name and value, and the totals. */
        fun header() {
            room(160f)
            val title = layout(report.title, paint(21f, fonts.bold), CONTENT)
            draw(title, LEFT, y)
            y += title.height + 10f
            val keyWidth = 92f
            for ((name, value) in report.facts) {
                val key = layout(name, small, keyWidth)
                val shown = layout(value, strong, CONTENT - keyWidth)
                val height = max(key.height, shown.height).toFloat()
                room(height + 4f)
                draw(key, LEFT, y + 1f)
                draw(shown, LEFT + keyWidth, y)
                y += height + 4f
            }
            y += 6f
            val below = listOfNotNull(report.waitingLine, report.pace)
            paragraph(report.summary, paint(11.5f, fonts.bold), after = if (below.isEmpty()) 12f else 4f)
            below.forEachIndexed { index, line -> paragraph(line, after = if (index == below.lastIndex) 12f else 4f) }
        }

        /** The box with what the numbers are and aren't. */
        fun howToRead() {
            val inner = CONTENT - 28f
            val title = layout(PracticeReport.HOW_TO_READ_TITLE, paint(10f, fonts.bold), inner)
            val lines = report.readingNotes.map { layout("•  $it", paint(8.8f), inner) }
            val height = title.height + lines.sumOf { it.height + 3 } + 24f
            room(height)
            val box = RectF(LEFT, y, RIGHT, y + height)
            fill(box, cloud, radius = 10f)
            stroke(RectF(box.left + 0.4f, box.top + 0.4f, box.right - 0.4f, box.bottom - 0.4f), line, radius = 10f)
            var top = y + 11f
            draw(title, LEFT + 14f, top)
            top += title.height + 5f
            for (laid in lines) {
                draw(laid, LEFT + 14f, top)
                top += laid.height + 3f
            }
            y += height + 6f
        }

        fun sounds() {
            section(PracticeReport.SOUNDS_TITLE)
            if (report.sounds.isEmpty()) {
                paragraph(PracticeReport.NO_SOUNDS)
                return
            }
            for (sound in report.sounds) {
                if (!fits(blockHeight(sound))) {
                    closingLine(PracticeReport.MORE_SOUNDS)
                    return
                }
                soundBlock(sound)
            }
        }

        /** A last small line, only if it fits on this page. */
        private fun closingLine(text: String) {
            val laid = layout(text, small, CONTENT)
            if (!isOpen || y + laid.height > BOTTOM) return
            draw(laid, LEFT, y)
            y += laid.height + 6f
        }

        private fun chartHeight(sound: PracticeReport.SoundPart): Float = if (sound.weeks.size > 1) 86f else 0f

        private fun blockHeight(sound: PracticeReport.SoundPart): Float {
            val lines = listOfNotNull(sound.ladder, sound.stageLine, sound.confirmed, sound.moveLine, sound.tries, sound.agreement) + sound.hearing
            val textHeight = lines.sumOf { layout(it, body, CONTENT).height + 4 }
            return 22f + 16f + textHeight + 60f + chartHeight(sound) + 20f
        }

        /** One sound kept on one page: the step, the counts, the two rows of answers and the weekly bars. */
        private fun soundBlock(sound: PracticeReport.SoundPart) {
            room(blockHeight(sound))
            y += 4f
            canvas.drawText(sound.title, LEFT, y + 11f, paint(11.5f, fonts.bold))
            y += 18f
            ladderStrip(sound)
            paragraph(sound.ladder, after = 3f)
            paragraph(sound.stageLine, after = 3f)
            sound.confirmed?.let { paragraph(it, after = 3f) }
            sound.moveLine?.let { paragraph(it, after = 3f) }
            paragraph(sound.tries, strong, after = 6f)
            table(sound.rows)
            paragraph(sound.agreement, after = if (sound.hearing.isEmpty()) 8f else 3f)
            sound.hearing.forEachIndexed { index, line -> paragraph(line, after = if (index == sound.hearing.lastIndex) 8f else 3f) }
            val chart = chartHeight(sound)
            if (chart > 0) weekBars(sound, chart)
            y += 8f
            rule(LEFT, y, RIGHT, y, line, 0.6f)
            y += 6f
        }

        /** The ten rungs as boxes, filled up to the one reached. */
        private fun ladderStrip(sound: PracticeReport.SoundPart) {
            val gap = 3f
            val width = min(22f, (CONTENT - gap * (sound.rungs - 1)) / sound.rungs)
            for (index in 0 until sound.rungs) {
                val left = LEFT + index * (width + gap)
                val rect = RectF(left, y, left + width, y + 7f)
                if (index < sound.rung) fill(rect, ink, radius = 1.5f)
                stroke(rect, ink, width = 0.6f)
            }
            y += 13f
        }

        private fun table(rows: List<List<String>>) {
            val widths = listOf(0.24f, 0.16f, 0.16f, 0.16f, 0.28f).map { it * min(CONTENT, 380f) }
            val height = 17f
            room(height * rows.size + 4f)
            rows.forEachIndexed { rowIndex, row ->
                var x = LEFT
                if (rowIndex == 0) fill(RectF(x, y, x + widths.sum(), y + height), cloud)
                row.forEachIndexed { column, cell ->
                    val face = if (rowIndex == 0 || column == 0) fonts.semibold else fonts.regular
                    if (column == 0) {
                        canvas.drawText(cell, x + 5f, y + 12f, paint(8.5f, face))
                    } else {
                        canvas.drawText(cell, x + widths[column] - 6f, y + 12f, paint(8.5f, face, align = Paint.Align.RIGHT))
                    }
                    stroke(RectF(x, y, x + widths[column], y + height), line)
                    x += widths[column]
                }
                y += height
            }
            y += 6f
        }

        /** Tries per week as dark bars with their number on top, so nothing depends on colour. */
        private fun weekBars(sound: PracticeReport.SoundPart, height: Float) {
            room(height)
            draw(layout(PracticeReport.CHART_TITLE, small, CONTENT), LEFT, y)
            val top = y + 14f
            val baseline = y + height - 14f
            val plotHeight = baseline - top - 12f
            val peak = max(1, sound.weeks.maxOfOrNull { it.attempts } ?: 1)
            val slot = CONTENT / max(sound.weeks.size, 6)
            val barWidth = min(26f, slot * 0.55f)
            rule(LEFT, baseline, LEFT + slot * sound.weeks.size, baseline, inkSoft, 0.6f)
            val label = paint(7.5f, fonts.semibold, align = Paint.Align.CENTER)
            val dates = paint(7f, color = inkSoft, align = Paint.Align.CENTER)
            sound.weeks.forEachIndexed { index, week ->
                val center = LEFT + slot * (index + 0.5f)
                val barHeight = plotHeight * week.attempts / peak
                if (week.attempts > 0) fill(RectF(center - barWidth / 2, baseline - barHeight, center + barWidth / 2, baseline), ink)
                canvas.drawText("${week.attempts}", center, baseline - barHeight - 3f, label)
                canvas.drawText(PracticeReport.barDate(week.monday), center, baseline + 10f, dates)
            }
            y += height
        }

        /** Newest first. What doesn't fit on the last page is left to the JSON file, with a line that says so. */
        fun notes() {
            if (!fits(110f)) {
                closingLine(PracticeReport.MORE_NOTES)
                return
            }
            section(PracticeReport.NOTES_TITLE)
            if (report.notes.isEmpty()) {
                paragraph(PracticeReport.NO_NOTES)
                return
            }
            val ending = layout(PracticeReport.MORE_NOTES, small, CONTENT).height + 4f
            report.notes.forEachIndexed { index, note ->
                val day = layout(note.day, small, CONTENT)
                val shown = layout(note.text, body, CONTENT)
                val isLast = index == report.notes.lastIndex
                if (!fits(day.height + 2f + shown.height + 8f + (if (isLast) 0f else ending))) {
                    closingLine(PracticeReport.MORE_NOTES)
                    return
                }
                room(day.height + 2f + shown.height)
                draw(day, LEFT, y)
                y += day.height + 2f
                draw(shown, LEFT, y)
                y += shown.height + 8f
            }
        }
    }
}
