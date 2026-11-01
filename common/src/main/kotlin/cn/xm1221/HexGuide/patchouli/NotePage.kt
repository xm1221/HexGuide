package cn.xm1221.HexGuide.patchouli

import cn.xm1221.HexGuide.api.notes.ClientNotes
import cn.xm1221.HexGuide.api.notes.NoteIota
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.resources.language.I18n
import net.minecraft.network.chat.Component
import vazkii.patchouli.client.book.BookPage
import vazkii.patchouli.client.book.gui.BookTextRenderer
import vazkii.patchouli.client.book.gui.GuiBook
import vazkii.patchouli.client.book.gui.GuiBookEntry

/**
 * 笔记显示页（hexguide:note_page）——整个笔记节只占 Patchouli 的**一页**（条目 JSON 里只有 1 个 note_page）。
 *
 * 页码不再来自 Patchouli 的 pageNum（那需要 entries/notes/notes.json 里写满假页），
 * 而是用 [ClientNotes.pageIndex]（节内偏移）：
 * - 底部 ◀ ▶ 按钮翻页（也可以滚轮，见 onScroll）
 * - 顶部右侧显示 `第 x / n 页`
 * - 空节、越界、未同步都有友好的 lang 提示，不再是一片空白
 *
 * 这样条目页数从 256 缩到 1，且笔记再多也不用翻空白页。
 */
class NotePage : BookPage(), ScrollablePage {
    @Transient
    private var textRender: BookTextRenderer? = null

    @Transient
    private var currentNote: NoteIota? = null

    override fun onDisplayed(parent: GuiBookEntry, left: Int, top: Int) {
        super.onDisplayed(parent, left, top)
        ScrollablePages.register(this)
        ClientNotes.setPage(ClientNotes.pageIndex) // 夹紧到当前节范围
        refresh()
    }

    override fun onHidden(parent: GuiBookEntry) {
        ScrollablePages.unregister(this)
        super.onHidden(parent)
    }

    private fun refresh() {
        val note = ClientNotes.currentPage()
        currentNote = note
        textRender = if (note != null) {
            // 编辑器里的换行符 \n → Patchouli 换行码 $(br)（BookTextRenderer 的裸 \n 渲染不正常）
            val body = note.body.replace("\n", "$(br)")
            BookTextRenderer(parent, Component.literal(body), 0, TEXT_Y, GuiBook.PAGE_WIDTH, 9, BODY_COLOR)
        } else null
    }

    /** 翻页：delta = -1 上一页 / +1 下一页；返回是否真的动了 */
    private fun turn(delta: Int): Boolean {
        val count = ClientNotes.pageCount()
        if (count <= 0) return false
        val target = (ClientNotes.pageIndex + delta).coerceIn(0, count - 1)
        if (target == ClientNotes.pageIndex) return false
        ClientNotes.setPage(target)
        refresh()
        return true
    }

    override fun onScroll(mouseX: Double, mouseY: Double, scroll: Double): Boolean {
        // 笔记页整页都是正文：滚轮直接当翻页用（只在本节有多页时接管）
        if (ClientNotes.pageCount() <= 1) return false
        if (mouseX < 0 || mouseY < 0 || mouseX > GuiBook.PAGE_WIDTH || mouseY > GuiBook.PAGE_HEIGHT) return false
        return turn(if (scroll < 0) 1 else -1)
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, pticks: Float) {
        val font = Minecraft.getInstance().font
        val count = ClientNotes.pageCount()

        // 空节 / 未同步：给出提示（而不是空白页）
        if (count == 0) {
            val msg = if (ClientNotes.loadedUuid == null) I18n.get("hexguide.notes.not_synced")
                      else I18n.get("hexguide.notes.empty_section")
            graphics.drawString(font, msg, 4, TEXT_Y, META_COLOR, false)
            return
        }
        val note = currentNote ?: run { refresh(); currentNote }
        if (note == null) {
            // 页码越界（节变短）：夹回范围后重试
            ClientNotes.setPage(ClientNotes.pageIndex)
            refresh()
            return
        }

        // 标题（居中）
        val title = note.title.ifEmpty { I18n.get("hexguide.notes.unnamed") }
        graphics.drawString(font, title, (GuiBook.PAGE_WIDTH - font.width(title)) / 2, 2, TITLE_COLOR, false)

        // 作者 + 页序（标题下方右侧，不遮挡标题）
        val meta = if (count > 1)
            I18n.get("hexguide.notes.page_of", ClientNotes.pageIndex + 1, count, note.author)
        else note.author
        graphics.drawString(font, meta, GuiBook.PAGE_WIDTH - font.width(meta), META_Y, META_COLOR, false)

        // 正文
        textRender?.render(graphics, mouseX, mouseY)

        // 底部翻页按钮（多页时才显示）
        if (count > 1) renderNav(graphics, font, count)
    }

    private fun renderNav(graphics: GuiGraphics, font: net.minecraft.client.gui.Font, count: Int) {
        val y = GuiBook.PAGE_HEIGHT - 12
        val prevOn = ClientNotes.pageIndex > 0
        val nextOn = ClientNotes.pageIndex < count - 1
        graphics.drawString(font, "◀", NAV_X, y, if (prevOn) NAV_ON else NAV_OFF, false)
        graphics.drawString(font, "▶", NAV_X + NAV_W, y, if (nextOn) NAV_ON else NAV_OFF, false)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, mouseButton: Int): Boolean {
        if (mouseButton == 0 && ClientNotes.pageCount() > 1) {
            val y = GuiBook.PAGE_HEIGHT - 12
            if (mouseY >= y - 2 && mouseY <= y + 10) {
                if (mouseX >= NAV_X - 2 && mouseX <= NAV_X + 10) return turn(-1)
                if (mouseX >= NAV_X + NAV_W - 2 && mouseX <= NAV_X + NAV_W + 10) return turn(1)
            }
        }
        return textRender?.click(mouseX, mouseY, mouseButton) ?: false
    }

    companion object {
        const val TEXT_Y = 20
        const val META_Y = 12
        const val NAV_X = 4
        const val NAV_W = 10
        const val TITLE_COLOR = 0xFF000000.toInt()
        const val META_COLOR = 0xFF777777.toInt()
        const val BODY_COLOR = 0xFF333333.toInt()
        const val NAV_ON = 0xFF0066CC.toInt()
        const val NAV_OFF = 0xFFAAAAAA.toInt()
    }
}
