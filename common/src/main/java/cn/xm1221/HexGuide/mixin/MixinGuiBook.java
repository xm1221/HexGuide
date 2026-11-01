package cn.xm1221.HexGuide.mixin;

import cn.xm1221.HexGuide.patchouli.ScrollablePages;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import vazkii.patchouli.client.book.gui.GuiBook;
import vazkii.patchouli.client.book.gui.GuiBookEntry;

/**
 * 让自定义书页能消费滚轮（目前：笔记目录页列表滚动）。
 *
 * Patchouli 的 GuiBook.mouseScrolled 无条件 changePage(true/false)，页面拿不到滚轮。
 * 这里在 HEAD 先交给当前显示中的页面：若页面消费了滚轮就直接返回 true，
 * 否则走原版翻页（SHIFT 行为不变）。笔记显示页已改回原生多页，不再依赖本 Mixin。
 */
@Mixin(GuiBook.class)
public abstract class MixinGuiBook {

    @Shadow(remap = false) public int bookLeft;
    @Shadow(remap = false) public int bookTop;

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true, remap = false)
    private void hexguide$scrollPage(double mouseX, double mouseY, double scroll,
                                     CallbackInfoReturnable<Boolean> cir) {
        Screen self = (Screen) (Object) this;
        if (!(self instanceof GuiBookEntry entry)) return;
        if (ScrollablePages.dispatch(entry, bookLeft, bookTop, mouseX, mouseY, scroll)) {
            cir.setReturnValue(true);
        }
    }
}
