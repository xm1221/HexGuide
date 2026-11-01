package cn.xm1221.HexGuide.patchouli

import cn.xm1221.HexGuide.api.notes.ClientNotes
import cn.xm1221.HexGuide.api.notes.NoteIota
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.resources.language.I18n
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import vazkii.patchouli.client.book.BookPage
import vazkii.patchouli.client.book.gui.GuiBook
import vazkii.patchouli.client.book.gui.GuiBookEntry

/**
 * 笔记目录页（hexguide:note_index）。
 * 列出当前玩家所有节（笔记）：标题；点击某节 → 设为当前节并跳转到显示条目的原生第 0 页。
 * 显示条目仍是 Patchouli 多页（~256 note_page），之后用书本翻页浏览本节。
 * 新建笔记用【紫水晶笔】（副手纸右键）。
 *
 * 条数超过一页时可滚动：鼠标滚轮滚动（经 MixinGuiBook；滚到顶/底交还原版翻页），
 * 同时提供底部 ▲▼；底部显示 `起始-结束/总数`；点击行按「相对行 + 滚动偏移」换算绝对索引。
 */
class NoteIndex : BookPage(), ScrollablePage {
    @Transient
    private var sections: List<List<NoteIota>> = emptyList()

    /** 列表滚动偏移（首行对应的绝对索引） */
    @Transient
    private var scrollOff = 0

    override fun onDisplayed(parent: GuiBookEntry, left: Int, top: Int) {
        super.onDisplayed(parent, left, top)
        ScrollablePages.register(this)
        sections = ClientNotes.sections()
        scrollOff = clampScroll(scrollOff, sections.size)
    }

    override fun onHidden(parent: GuiBookEntry) {
        ScrollablePages.unregister(this)
        super.onHidden(parent)
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, pticks: Float) {
        val font = Minecraft.getInstance().font

        if (sections.isEmpty()) {
            graphics.drawString(font, I18n.get("hexguide.notes.empty"), 4, LIST_Y, META_COLOR, false)
            return
        }

        scrollOff = clampScroll(scrollOff, sections.size)
        // 只渲染滚动窗口内的行；相对行 i → 绝对索引 scrollOff + i
        for (row in 0 until visibleRows()) {
            val idx = scrollOff + row
            val title = sectionTitle(sections[idx])
            // 行 = [绝对索引] 标题（索引 0-based，与 note/get、note/delete 一致）
            val full = "[$idx] $title"
            val text = if (font.width(full) > GuiBook.PAGE_WIDTH - 8)
                font.splitter.plainHeadByWidth(full, GuiBook.PAGE_WIDTH - 16, Style.EMPTY) + "…"
            else full
            val color = if (idx == ClientNotes.currentSection) HIGHLIGHT_COLOR else ROW_COLOR
            graphics.drawString(font, text, ROW_X, LIST_Y + row * ROW_H, color, false)
        }

        // 底部：范围指示 + ▲▼（仅条数超出可视行时出现）
        val ny = NAV_Y
        if (sections.size > MAX_ROWS) {
            val canUp = scrollOff > 0
            val canDown = scrollOff + MAX_ROWS < sections.size
            graphics.drawString(font, if (canUp) "▲" else "△", NAV_UP_X, ny,
                if (canUp) NAV_ON else META_COLOR, false)
            graphics.drawString(font, if (canDown) "▼" else "▽", NAV_DOWN_X, ny,
                if (canDown) NAV_ON else META_COLOR, false)
        }
        val info = if (sections.size <= MAX_ROWS) I18n.get("hexguide.notes.count", sections.size)
                   else I18n.get("hexguide.notes.range",
                       scrollOff + 1, minOf(scrollOff + MAX_ROWS, sections.size), sections.size)
        graphics.drawString(font, info, NAV_INFO_X, ny, META_COLOR, false)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, mouseButton: Int): Boolean {
        if (mouseButton != 0 || sections.isEmpty()) return false
        val mouse = mouseX.toInt() to mouseY.toInt()

        // 先判 ▲▼（在列表下方，避免与行判定冲突；两块点击区不重叠）
        if (sections.size > MAX_ROWS) {
            if (parent.isAreaHovered(mouse.first, mouse.second, NAV_UP_X, NAV_Y, NAV_HIT_W, ROW_H)) {
                if (scrollOff > 0) { scrollOff = (scrollOff - MAX_ROWS).coerceAtLeast(0); return true }
                return false
            }
            if (parent.isAreaHovered(mouse.first, mouse.second, NAV_DOWN_X, NAV_Y, NAV_HIT_W, ROW_H)) {
                if (scrollOff + MAX_ROWS < sections.size) {
                    scrollOff = clampScroll(scrollOff + MAX_ROWS, sections.size); return true
                }
                return false
            }
        }

        for (row in 0 until visibleRows()) {
            if (parent.isAreaHovered(mouse.first, mouse.second, ROW_X, LIST_Y + row * ROW_H,
                    GuiBook.PAGE_WIDTH - ROW_X * 2, ROW_H)) {
                // 相对行 + 滚动偏移 = 绝对索引（此前漏了偏移，滚页后点错节）
                ClientNotes.setCurrent(scrollOff + row)
                // 跳到显示条目第 0 页（Patchouli 原生页码 = 本节第 1 个 NoteIota；之后用书本翻页）
                val notesEntry = parent.book.contents.entries.values
                    .firstOrNull { it.id.path.endsWith("/notes") }
                if (notesEntry != null) {
                    parent.navigateToEntry(notesEntry.id, 0, false)
                    return true
                }
            }
        }
        return false
    }

    /**
     * 滚轮滚动（经 MixinGuiBook → ScrollablePages 调用，坐标为页面相对坐标）。
     * 返回 true 时不再翻书页；列表装得下时返回 false，保留原版翻页手感。
     */
    override fun onScroll(mouseX: Double, mouseY: Double, scroll: Double): Boolean {
        if (sections.size <= MAX_ROWS) return false
        if (mouseX < 0 || mouseY < LIST_Y - 2 || mouseX > GuiBook.PAGE_WIDTH) return false
        val next = clampScroll(scrollOff + if (scroll < 0) MAX_ROWS else -MAX_ROWS, sections.size)
        if (next == scrollOff) return false // 已到顶/底：交还原版翻页
        scrollOff = next
        return true
    }

    private fun visibleRows(): Int = minOf(MAX_ROWS, (sections.size - scrollOff).coerceAtLeast(0))

    private fun sectionTitle(sec: List<NoteIota>): String =
        sec.firstOrNull()?.title?.ifEmpty { I18n.get("hexguide.notes.unnamed") }
            ?: I18n.get("hexguide.notes.empty_section")

    private fun clampScroll(off: Int, total: Int): Int =
        off.coerceIn(0, (total - MAX_ROWS).coerceAtLeast(0))

    companion object {
        const val ROW_X = 4
        const val LIST_Y = 10
        const val ROW_H = 12
        /** 页面内最多显示的行数（PAGE_HEIGHT≈156，底部留范围指示空间） */
        const val MAX_ROWS = (GuiBook.PAGE_HEIGHT - LIST_Y - 20) / ROW_H
        const val NAV_Y = LIST_Y + MAX_ROWS * ROW_H + 2
        /** ▲ 的绘制 x；▼ 与它相隔 NAV_HIT_W，点击区互不重叠 */
        const val NAV_UP_X = ROW_X
        const val NAV_DOWN_X = ROW_X + 10
        const val NAV_HIT_W = 10
        const val NAV_INFO_X = ROW_X + 22
        const val ROW_COLOR = 0xFF0044AA.toInt()
        const val HIGHLIGHT_COLOR = 0xFF00AA77.toInt()
        const val META_COLOR = 0xFF777777.toInt()
        const val NAV_ON = 0xFFFFCC00.toInt()
    }
}
