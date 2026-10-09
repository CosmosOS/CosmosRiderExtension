package com.cosmosos.rider.kernelviews

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseEvent
import java.util.Locale
import javax.swing.JComponent
import javax.swing.Scrollable
import javax.swing.ToolTipManager
import kotlin.math.ceil
import kotlin.math.max

/**
 * The RAT drawn as a colored grid, one cell per page (or per power-of-two
 * bucket of pages on large heaps), each cell painted in the page type that
 * owns most of it; a stats line on top and a per-type legend underneath.
 * Hovering a cell lists the page types inside it.
 */
class MemoryMapComponent : JComponent(), Scrollable {

    private data class TypeInfo(val id: Int, val name: String, val color: Color)

    private val typeInfo = listOf(
        TypeInfo(PageTypes.EMPTY, "Empty", Color(0x1e2229)),
        TypeInfo(PageTypes.GC_HEAP, "GCHeap", Color(0x4ec9b0)),
        TypeInfo(PageTypes.HEAP_SMALL, "HeapSmall", Color(0x569cd6)),
        TypeInfo(PageTypes.HEAP_MEDIUM, "HeapMedium", Color(0xe6a32e)),
        TypeInfo(PageTypes.HEAP_LARGE, "HeapLarge", Color(0xa374d5)),
        TypeInfo(PageTypes.UNMANAGED, "Unmanaged", Color(0xce9178)),
        TypeInfo(PageTypes.PAGE_DIRECTORY, "PageDirectory", Color(0xdcdcaa)),
        TypeInfo(PageTypes.PAGE_ALLOCATOR, "PageAllocator", Color(0xb5cea8)),
        TypeInfo(PageTypes.SMT, "SMT", Color(0xf48771)),
        TypeInfo(PageTypes.EXTENSION, "Extension", Color(0x6e6e6e))
    )
    private val colorByType = typeInfo.associate { it.id to it.color }
    private val unknownColor = Color(0xff0066)
    private val noDataColor = Color(0x1a1d20)

    private var stats: MemoryStats? = null
    private var extents: List<PageExtent>? = null
    private var error: String? = null
    private var message: String? = "Waiting for memory snapshot…"

    // Per-cell page counts by type, and the dominant type per cell.
    private var cellCounts: Array<MutableMap<Int, Int>?> = emptyArray()
    private var dominant: IntArray = IntArray(0)
    private var cols = 0
    private var rows = 0
    private var cellPx = 0
    private var pagesPerCell = 1
    private var builtForWidth = -1

    init {
        ToolTipManager.sharedInstance().registerComponent(this)
        // The grid's height depends on its width; re-flow when it changes.
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                if (width != builtForWidth) revalidate()
            }
        })
    }

    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = JBUI.scale(16)

    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = visibleRect.height

    override fun getScrollableTracksViewportWidth() = true

    override fun getScrollableTracksViewportHeight() = false

    fun update(stats: MemoryStats?, extents: List<PageExtent>?, error: String?, message: String?) {
        this.stats = stats
        this.extents = extents
        this.error = error
        this.message = message
        builtForWidth = -1
        revalidate()
        repaint()
    }

    private val pad get() = JBUI.scale(6)
    private val lineHeight get() = getFontMetrics(font).height

    private fun gridTop() = pad + lineHeight * 2 + pad

    // Smallest power-of-two pages-per-cell keeping the grid under ~16k cells,
    // then as many columns as fit at >= 6px per cell.
    private fun layoutGrid(width: Int) {
        if (builtForWidth == width) return
        builtForWidth = width
        val s = stats
        val ext = extents
        val total = s?.totalPageCount ?: 0L
        if (s == null || ext == null || total <= 0) {
            cols = 0; rows = 0; cellCounts = emptyArray(); dominant = IntArray(0)
            return
        }
        val containerW = max(1, width - 2 * pad - 2)
        var ppc = 1L
        while (ceil(total.toDouble() / ppc) > 16000) ppc *= 2
        pagesPerCell = ppc.toInt()
        val cells = ceil(total.toDouble() / ppc).toInt()
        var c = max(8, containerW / JBUI.scale(6))
        if (c > cells) c = cells
        cols = max(1, c)
        rows = ceil(cells.toDouble() / cols).toInt()
        cellPx = max(2, containerW / cols)

        val totalCells = cols * rows
        val counts = arrayOfNulls<MutableMap<Int, Int>>(totalCells)
        for (e in ext) {
            val end = e.start.toLong() + e.length
            var p = e.start.toLong()
            while (p < end) {
                val cell = (p / ppc).toInt()
                if (cell >= totalCells) break
                val cellEnd = minOf(end, (cell + 1) * ppc)
                val span = (cellEnd - p).toInt()
                val map = counts[cell] ?: HashMap<Int, Int>().also { counts[cell] = it }
                map[e.type] = (map[e.type] ?: 0) + span
                p = cellEnd
            }
        }
        cellCounts = counts
        dominant = IntArray(totalCells) { i -> counts[i]?.maxByOrNull { it.value }?.key ?: -1 }
    }

    override fun getPreferredSize(): Dimension {
        val width = if (width > 0) width else parent?.width?.takeIf { it > 0 } ?: JBUI.scale(300)
        layoutGrid(width)
        val legendRows = stats?.typeCounts?.count { it.value > 0 }?.coerceAtLeast(1) ?: 0
        val height = gridTop() + rows * cellPx + pad + legendRows * lineHeight + pad + (if (error != null) lineHeight else 0)
        return Dimension(width, max(height, JBUI.scale(60)))
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g2.color = UIUtil.getPanelBackground()
        g2.fillRect(0, 0, width, height)
        g2.font = font

        val s = stats
        if (s == null || s.totalPageCount <= 0) {
            g2.color = UIUtil.getContextHelpForeground()
            g2.drawString(message ?: "Memory snapshot not initialized yet.", pad, pad + g2.fontMetrics.ascent)
            return
        }

        layoutGrid(width)
        val fm = g2.fontMetrics
        val f = KernelSnapshots::formatBytes
        val total = s.totalPageCount
        val free = s.freePageCount
        val used = total - free
        g2.color = UIUtil.getLabelForeground()
        g2.drawString(
            "$total pages × ${s.pageSize} B = ${f(total * s.pageSize)}  |  used $used (${f(used * s.pageSize)})  |  free $free (${f(free * s.pageSize)})",
            pad, pad + fm.ascent
        )
        g2.color = UIUtil.getContextHelpForeground()
        g2.drawString(
            "cell = $pagesPerCell page${if (pagesPerCell == 1) "" else "s"}, grid $cols × $rows",
            pad, pad + lineHeight + fm.ascent
        )

        val top = gridTop()
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val idx = r * cols + c
                val type = dominant.getOrElse(idx) { -1 }
                g2.color = if (type < 0) noDataColor else colorByType[type] ?: unknownColor
                g2.fillRect(pad + c * cellPx, top + r * cellPx, cellPx, cellPx)
            }
        }
        g2.color = JBColor.border()
        g2.drawRect(pad - 1, top - 1, cols * cellPx + 1, rows * cellPx + 1)

        // Legend: types present, largest first.
        var y = top + rows * cellPx + pad
        val entries = typeInfo
            .map { it to (s.typeCounts[it.id] ?: 0L) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
        val swatch = JBUI.scale(10)
        if (entries.isEmpty()) {
            g2.color = UIUtil.getContextHelpForeground()
            g2.drawString("(no pages yet)", pad, y + fm.ascent)
            y += lineHeight
        }
        for ((info, count) in entries) {
            g2.color = info.color
            g2.fillRect(pad, y + (lineHeight - swatch) / 2, swatch, swatch)
            g2.color = JBColor.border()
            g2.drawRect(pad, y + (lineHeight - swatch) / 2, swatch, swatch)
            g2.color = UIUtil.getLabelForeground()
            g2.drawString(info.name, pad + swatch + JBUI.scale(8), y + fm.ascent)
            val pct = count * 100.0 / total
            val pctText = if (pct >= 10) String.format(Locale.ROOT, "%.0f%%", pct) else String.format(Locale.ROOT, "%.1f%%", pct)
            val countText = String.format(Locale.ROOT, "%,d  %s", count, pctText)
            g2.color = UIUtil.getContextHelpForeground()
            g2.drawString(countText, width - pad - fm.stringWidth(countText), y + fm.ascent)
            y += lineHeight
        }
        error?.let {
            g2.color = JBColor.RED
            g2.drawString(it, pad, y + fm.ascent)
        }
    }

    override fun getToolTipText(event: MouseEvent): String? {
        val s = stats ?: return null
        if (cols == 0 || cellPx == 0) return null
        val c = (event.x - pad) / cellPx
        val r = (event.y - gridTop()) / cellPx
        if (event.x < pad || event.y < gridTop() || c !in 0 until cols || r !in 0 until rows) return null
        val idx = r * cols + c
        val pageStart = idx.toLong() * pagesPerCell
        val pageEnd = minOf(pageStart + pagesPerCell, s.totalPageCount)
        val lines = mutableListOf("Pages [$pageStart..$pageEnd)")
        val counts = cellCounts.getOrNull(idx)
        if (counts == null) {
            lines += "(no data)"
        } else {
            for ((type, count) in counts.entries.sortedByDescending { it.value }) {
                lines += "${PageTypes.names[type] ?: "Type=$type"}: $count"
            }
        }
        return lines.joinToString("<br/>", "<html>", "</html>")
    }
}
