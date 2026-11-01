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
 * 笔记显示页（hexguide:note_page）。
 *
 * Patchouli 原生方案：条目 JSON 里预置约 256 个 `note_page`，用书本自带翻页 / 书签。
 * 页码（0-based [pageNum]）→ 当前节第 N 个 [NoteIota]。
 * 目录点某节后会 [ClientNotes.setCurrent] 并跳到该条目第 0 页，再靠原生翻页浏览本节。
 *
 * 空节 / 未同步时在空白页给出友好提示；超出本节页数的空白页保持空白（256 页垫底的正常表现）。
 */
class NotePage : BookPage() {
    @Transient
    private var textRender: BookTextRenderer? = null

    @Transient
    private var currentNote: NoteIota? = null

    override fun onDisplayed(parent: GuiBookEntry, left: Int, top: Int) {
        super.onDisplayed(parent, left, top)
        refresh()
    }

    private fun refresh() {
        val note = ClientNotes.page(pageNum) // pageNum 0-based
        currentNote = note
        textRender = if (note != null) {
            // 编辑器里的换行符 \n → Patchouli 换行码 $(br)（BookTextRenderer 的裸 \n 渲染不正常）
            val body = note.body.replace("\n", "$(br)")
            BookTextRenderer(parent, Component.literal(body), 0, TEXT_Y, GuiBook.PAGE_WIDTH, 9, BODY_COLOR)
        } else null
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, pticks: Float) {
        val font = Minecraft.getInstance().font
        var note = currentNote
        if (note == null) {
            // 缓存未同步时补救一次
            if (ClientNotes.page(pageNum) != null) {
                refresh()
                note = currentNote
            }
        }
        if (note == null) {
            // 仅在「本节确实没有内容」时提示；超出本节长度的空白页保持空白
            if (ClientNotes.current().isEmpty()) {
                val msg = if (ClientNotes.loadedUuid == null) I18n.get("hexguide.notes.not_synced")
                          else I18n.get("hexguide.notes.empty_section")
                graphics.drawString(font, msg, 4, TEXT_Y, META_COLOR, false)
            }
            return
        }

        // 标题（居中）
        val title = note.title.ifEmpty { I18n.get("hexguide.notes.unnamed") }
        graphics.drawString(font, title, (GuiBook.PAGE_WIDTH - font.width(title)) / 2, 2, TITLE_COLOR, false)

        // 作者 + 原生页码（标题下方右侧，不遮挡标题）
        val meta = "${note.author} · ${pageNum + 1}"
        graphics.drawString(font, meta, GuiBook.PAGE_WIDTH - font.width(meta), META_Y, META_COLOR, false)

        // 正文
        textRender?.render(graphics, mouseX, mouseY)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, mouseButton: Int): Boolean {
        return textRender?.click(mouseX, mouseY, mouseButton) ?: false
    }

    companion object {
        const val TEXT_Y = 20
        const val META_Y = 12
        const val TITLE_COLOR = 0xFF000000.toInt()
        const val META_COLOR = 0xFF777777.toInt()
        const val BODY_COLOR = 0xFF333333.toInt()
    }
}
