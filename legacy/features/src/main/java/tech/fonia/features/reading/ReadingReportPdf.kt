package tech.fonia.features.reading

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import java.io.File
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.max

/**
 * Draws a [ReadingReport] as an A4 PDF with Android's `PdfDocument`, on the phone. Nothing is sent anywhere: the
 * file sits in the cache's `export` folder (the one `ProfileExport` shares from, emptied when the app starts) until
 * the parent shares it through the system's share sheet.
 */
object ReadingReportPdf {
    private const val WIDTH = 595
    private const val HEIGHT = 842
    private const val LEFT = 44f
    private const val RIGHT = WIDTH - 44f
    private const val TOP = 46f
    private const val BOTTOM = HEIGHT - 74f
    private const val CONTENT = RIGHT - LEFT

    private val ink = Color.rgb(0x10, 0x2E, 0x52)
    private val inkSoft = Color.rgb(0x54, 0x65, 0x77)
    private val accent = Color.rgb(0x00, 0x82, 0x40)
    private val line = Color.rgb(0xDD, 0xE3, 0xDC)
    private val cloud = Color.rgb(0xEB, 0xF2, 0xEB)
    private val sheet = Color.WHITE

    /** The folder the share sheet reads from, inside the cache folder that `fonia_export_paths.xml` opens. */
    fun folder(context: Context): File = File(context.cacheDir, "export/reading")

    /** Writes the PDF to the export folder (emptied first) and returns it with its page count. */
    fun write(context: Context, report: ReadingReport): Pair<File, Int> {
        val folder = folder(context)
        folder.deleteRecursively()
        folder.mkdirs()
        val file = File(folder, "fonia-citire-${report.createdOn}.pdf")
        val pages = write(report, Fonts.from(context.assets), file)
        return file to pages
    }

    /** Draws every page into [file]; returns how many there are. */
    fun write(report: ReadingReport, fonts: Fonts, file: File): Int {
        val pages = Pages(fonts, report)
        try {
            pages.cover()
            pages.reading()
            pages.otherParts()
            pages.annex()
            pages.finish()
            file.outputStream().use { pages.document.writeTo(it) }
            return pages.count
        } finally {
            pages.document.close()
        }
    }

    /** Every page as a picture [widthPx] wide, for the preview before sharing. */
    fun render(file: File, widthPx: Int): List<Bitmap> =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                (0 until renderer.pageCount).map { index ->
                    renderer.openPage(index).use { page ->
                        val bitmap = Bitmap.createBitmap(widthPx, widthPx * page.height / page.width, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap
                    }
                }
            }
        }

    /** The share sheet with the PDF, as `ProfileExport.share` hands over the JSON. */
    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.export", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(send, "Raport de citire")
        if (context !is android.app.Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(chooser)
        } catch (_: ActivityNotFoundException) {
        }
    }

    /** Baloo 2 from the app's assets, as on screen; the system face if the file is missing. */
    class Fonts(val regular: Typeface, val semibold: Typeface, val bold: Typeface) {
        companion object {
            fun from(assets: AssetManager): Fonts {
                fun load(weight: Int, fallback: Typeface) = runCatching {
                    Typeface.Builder(assets, "type/Baloo2-wght.ttf").setFontVariationSettings("'wght' $weight").setWeight(weight).build()
                }.getOrNull() ?: fallback
                return Fonts(
                    regular = load(500, Typeface.DEFAULT),
                    semibold = load(600, Typeface.DEFAULT_BOLD),
                    bold = load(700, Typeface.DEFAULT_BOLD),
                )
            }
        }
    }

    private class Pages(private val fonts: Fonts, private val report: ReadingReport) {
        val document = PdfDocument()
        var count = 0
            private set
        private var page: PdfDocument.Page? = null
        private var y = TOP

        private val canvas: Canvas get() = page?.canvas ?: start().canvas

        fun paint(size: Float, face: Typeface = fonts.regular, color: Int = ink) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = face
            textSize = size
            this.color = color
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

        fun finish() = finishPage()

        private fun newPage() {
            start()
        }

        /** Makes room for [height] points, starting a page if there isn't any. */
        private fun room(height: Float) {
            if (page == null || y + height > BOTTOM) newPage()
        }

        private fun layout(text: String, paint: TextPaint, width: Float): StaticLayout =
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(1.5f, 1.12f)
                .setIncludePad(false)
                .build()

        private fun draw(layout: StaticLayout, x: Float, top: Float) {
            canvas.save()
            canvas.translate(x, top)
            layout.draw(canvas)
            canvas.restore()
        }

        private fun paragraph(text: String, paint: TextPaint = body, after: Float = 7f, x: Float = LEFT, width: Float = CONTENT) {
            val laid = layout(text, paint, width)
            room(laid.height.toFloat())
            draw(laid, x, y)
            y += laid.height + after
        }

        private fun section(title: String) {
            room(48f)
            y += 8f
            val laid = layout(title, heading, CONTENT)
            draw(laid, LEFT, y)
            y += laid.height + 3f
            canvas.drawRect(LEFT, y, LEFT + 28f, y + 2f, Paint().apply { color = accent })
            y += 9f
        }

        /** Ruled lines for the adult's handwriting, kept on one page with what they are for. */
        private fun writingLines(prompt: String, lines: Int = 3) {
            room(layout(prompt, small, CONTENT).height + lines * 18f + 14f)
            paragraph(prompt, small, after = 2f)
            val rule = Paint().apply {
                color = line
                strokeWidth = 0.8f
            }
            repeat(lines) {
                room(20f)
                y += 18f
                canvas.drawLine(LEFT, y, RIGHT, y, rule)
            }
            y += 10f
        }

        private fun footer(canvas: Canvas) {
            val rule = Paint().apply {
                color = line
                strokeWidth = 0.8f
            }
            var top = BOTTOM + 16f
            canvas.drawLine(LEFT, top, RIGHT, top, rule)
            top += 7f
            val pageNumber = paint(8f, color = inkSoft).apply { textAlign = Paint.Align.RIGHT }
            canvas.drawText("Pagina $count", RIGHT, top + 8f, pageNumber)
            val disclaimer = layout(report.disclaimer, paint(8.5f, fonts.semibold), CONTENT - 60f)
            canvas.save()
            canvas.translate(LEFT, top)
            disclaimer.draw(canvas)
            canvas.restore()
            val made = layout(report.madeBy, paint(7.5f, color = inkSoft), CONTENT - 60f)
            canvas.save()
            canvas.translate(LEFT, top + disclaimer.height + 3f)
            made.draw(canvas)
            canvas.restore()
        }

        /** Title, the sentence about diagnosis in a box, and part 1. */
        fun cover() {
            room(120f)
            draw(layout("Fonia", paint(10f, fonts.bold, accent), CONTENT), LEFT, y)
            y += 16f
            val title = layout(report.title, paint(21f, fonts.bold), CONTENT)
            draw(title, LEFT, y)
            y += title.height + 2f
            paragraph(report.subtitle, paint(10.5f, color = inkSoft), after = 12f)

            val box = layout(report.disclaimer, paint(11f, fonts.bold), CONTENT - 24f)
            val purpose = layout(report.purpose, paint(9f, color = inkSoft), CONTENT - 24f)
            val boxHeight = box.height + purpose.height + 26f
            room(boxHeight)
            canvas.drawRoundRect(RectF(LEFT, y, RIGHT, y + boxHeight), 10f, 10f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cloud })
            draw(box, LEFT + 12f, y + 10f)
            draw(purpose, LEFT + 12f, y + 14f + box.height)
            y += boxHeight + 10f

            section("1. ${report.parts[0].title}")
            val keyWidth = 110f
            for ((name, value) in report.identification) {
                val key = layout(name, small, keyWidth)
                val shown = layout(value, strong, CONTENT - keyWidth)
                val height = max(key.height, shown.height).toFloat()
                room(height + 6f)
                draw(key, LEFT, y + 1f)
                draw(shown, LEFT + keyWidth, y)
                y += height + 6f
            }
        }

        /** Part 2: how the probe works, the curves, the band, every probe, and lines for what the phone can't see. */
        fun reading() {
            val part = report.parts[1]
            section("2. ${part.title}")
            paragraph(report.readingIntro)
            if (report.kinds.isNotEmpty()) {
                paragraph("Curba pe 6 luni, la fiecare probă (cuvinte corecte pe minut):", strong, after = 6f)
                val gap = 14f
                val panelWidth = (CONTENT - gap) / 2
                val panelHeight = 128f
                report.kinds.chunked(2).forEach { row ->
                    room(panelHeight + 8f)
                    row.forEachIndexed { index, kind ->
                        curvePanel(kind, LEFT + index * (panelWidth + gap), y, panelWidth, panelHeight)
                    }
                    y += panelHeight + 10f
                }
                paragraph("Reper din studiu (orientativ), la ultima probă din fiecare fel:", strong, after = 4f)
                for (line in report.bandLines) paragraph("• $line", after = 3f)
                paragraph(report.bandSource, small, after = 10f)
                table(report.readingTable, listOf(0.13f, 0.15f, 0.12f, 0.15f, 0.12f, 0.08f, 0.11f, 0.14f))
            } else {
                paragraph("Nicio probă de citire în ultimele 6 luni.", strong)
            }
            part.forTheAdult?.let { writingLines(it) }
        }

        /** Parts 3 to 6. */
        fun otherParts() {
            for (part in report.parts.drop(2)) {
                section("${part.number}. ${part.title}")
                for (text in part.lines) paragraph(text, after = 4f)
                part.forTheAdult?.let { writingLines(it, lines = 3) }
            }
        }

        /** The page that maps everything onto Anexa 1, with room for the teacher's notes. */
        fun annex() {
            newPage()
            section(report.annexTitle)
            paragraph(report.annexIntro, small, after = 8f)
            table(
                listOf(listOf("Itemul din fișă", "Ce arată Fonia", "Observațiile învățătorului")) +
                    report.annex.map { listOf(it.item, it.fromFonia, "") },
                listOf(0.24f, 0.46f, 0.30f),
                minRowHeight = 52f,
            )
        }

        /** A table whose first row is the header; it repeats the header on a new page. */
        private fun table(rows: List<List<String>>, widths: List<Float>, minRowHeight: Float = 0f) {
            val padding = 5f
            val columns = widths.map { it * CONTENT }
            val headerPaint = paint(8f, fonts.semibold)
            val cellPaint = paint(8.5f)
            val border = Paint().apply {
                color = line
                strokeWidth = 0.8f
                style = Paint.Style.STROKE
            }
            val fill = Paint().apply { color = cloud }
            fun laidRow(cells: List<String>, header: Boolean) = cells.mapIndexed { index, text ->
                layout(text, if (header) headerPaint else cellPaint, columns[index] - 2 * padding)
            }
            fun drawRow(cells: List<StaticLayout>, header: Boolean, height: Float) {
                var x = LEFT
                if (header) canvas.drawRect(LEFT, y, RIGHT, y + height, fill)
                cells.forEachIndexed { index, cell ->
                    draw(cell, x + padding, y + padding)
                    canvas.drawRect(x, y, x + columns[index], y + height, border)
                    x += columns[index]
                }
                y += height
            }
            val header = laidRow(rows.first(), header = true)
            val headerHeight = header.maxOf { it.height } + 2 * padding
            room(headerHeight + 24f)
            drawRow(header, true, headerHeight)
            for (row in rows.drop(1)) {
                val cells = laidRow(row, header = false)
                val height = max(cells.maxOf { it.height } + 2 * padding, minRowHeight)
                if (y + height > BOTTOM) {
                    newPage()
                    drawRow(laidRow(rows.first(), header = true), true, headerHeight)
                }
                drawRow(cells, false, height)
            }
            y += 10f
        }

        /** One kind's curve over the period: the child's own probes, nothing else on the plot. */
        private fun curvePanel(kind: ProbeKind, x: Float, top: Float, width: Float, height: Float) {
            val c = canvas
            c.drawRoundRect(RectF(x, top, x + width, top + height), 8f, 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cloud })
            c.drawText(kind.title, x + 10f, top + 15f, paint(8.5f, fonts.semibold))
            val points = report.curve(kind)
            val values = points.map { it.score.wcpmShown }
            val ceiling = max(60, (ceil((values.max() + 10) / 20.0) * 20).toInt())
            val plotLeft = x + 26f
            val plotRight = x + width - 14f
            val plotTop = top + 26f
            val plotBottom = top + height - 20f
            val grid = Paint().apply {
                color = line
                strokeWidth = 0.6f
            }
            val axis = paint(7f, color = inkSoft).apply { textAlign = Paint.Align.RIGHT }
            var tick = 0
            while (tick <= ceiling) {
                val ty = plotBottom - (plotBottom - plotTop) * tick / ceiling
                c.drawLine(plotLeft, ty, plotRight, ty, grid)
                c.drawText("$tick", plotLeft - 4f, ty + 2.5f, axis)
                tick += 20
            }
            val days = max(1L, ChronoUnit.DAYS.between(report.from, report.createdOn))
            fun px(record: ProbeRecord): Float =
                if (points.size == 1 && report.from == report.createdOn) {
                    (plotLeft + plotRight) / 2
                } else {
                    plotLeft + (plotRight - plotLeft) * ChronoUnit.DAYS.between(report.from, record.day) / days
                }
            fun py(value: Int): Float = plotBottom - (plotBottom - plotTop) * value / ceiling
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = accent
                strokeWidth = 1.8f
                style = Paint.Style.STROKE
            }
            val path = Path()
            points.forEachIndexed { index, record ->
                val px = px(record)
                val py = py(values[index])
                if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            c.drawPath(path, stroke)
            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
            val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = sheet }
            val label = paint(7.5f, fonts.semibold).apply { textAlign = Paint.Align.CENTER }
            points.forEachIndexed { index, record ->
                val px = px(record)
                val py = py(values[index])
                c.drawCircle(px, py, 3.2f, dot)
                c.drawCircle(px, py, 1.4f, ring)
                if (points.size <= 8 || index == 0 || index == points.lastIndex) c.drawText("${values[index]}", px, py - 6f, label)
            }
            val dates = paint(7f, color = inkSoft)
            c.drawText(ReadingReport.shortDate(report.from), plotLeft, top + height - 7f, dates)
            c.drawText(ReadingReport.shortDate(report.createdOn), plotRight, top + height - 7f, dates.apply { textAlign = Paint.Align.RIGHT })
        }
    }
}
