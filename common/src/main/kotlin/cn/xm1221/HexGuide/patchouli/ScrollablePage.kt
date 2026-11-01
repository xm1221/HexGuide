package cn.xm1221.HexGuide.patchouli

import vazkii.patchouli.client.book.BookPage
import vazkii.patchouli.client.book.gui.GuiBookEntry

/**
 * 让 BookPage 能吃到鼠标滚轮。
 *
 * Patchouli 的 `GuiBook.mouseScrolled` 无条件翻书页、不转发给页面（BookPage 也没有滚轮回调），
 * 所以目录页/笔记页的「滚轮滚动」需要 MixinGuiBook 在 HEAD 先问一遍当前显示中的页面。
 * 页面在 onDisplayed 里注册、onHidden 里注销；返回 false 时保持原版翻页行为。
 */
interface ScrollablePage {
    /** 滚轮：坐标为页面相对坐标，scroll>0 上滚。返回 true 表示已消费（不再翻书页）。 */
    fun onScroll(mouseX: Double, mouseY: Double, scroll: Double): Boolean
}

object ScrollablePages {
    private val active: MutableSet<ScrollablePage> =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap())

    fun register(page: ScrollablePage) { active.add(page) }
    fun unregister(page: ScrollablePage) { active.remove(page) }

    /**
     * 由 MixinGuiBook.mouseScrolled 调用：交给「正显示在这个 GuiBookEntry 上」的页面。
     * @return true 表示页面已消费，调用方应取消原版翻页
     */
    @JvmStatic
    fun dispatch(gui: GuiBookEntry, bookLeft: Int, bookTop: Int,
                 mouseX: Double, mouseY: Double, scroll: Double): Boolean {
        for (page in active.toList()) {
            if (page !is BookPage || page.parent !== gui) continue
            if (page.onScroll(mouseX - bookLeft - page.left, mouseY - bookTop - page.top, scroll)) return true
        }
        return false
    }
}
