package cn.xm1221.HexGuide

import cn.xm1221.HexGuide.config.HexGuideClientConfig
import cn.xm1221.HexGuide.patchouli.PageComponentText
import cn.xm1221.HexGuide.patchouli.SpellcastDemoPage
import cn.xm1221.HexGuide.patchouli.SpellcastingPage
import cn.xm1221.HexGuide.scrying.ScryingBookOverlay
import dev.architectury.platform.Platform
import me.shedaniel.autoconfig.AutoConfig
import net.minecraft.client.gui.screens.Screen
import net.minecraft.resources.ResourceLocation
import vazkii.patchouli.client.book.ClientBookRegistry

object HexGuideClient {
    fun init() {
        HexGuideClientConfig.init()
        ScryingBookOverlay.registerSlateOverlay()
        if (Platform.isModLoaded("inline")) {
            // 延迟解析 Inline 类：仅在装了 Inline 时才触达这些引用
            registerInlineClient()
        }

        // 注册自定义 Patchouli 页面类型 hexguide:component_text
        ClientBookRegistry.INSTANCE.pageTypes[ResourceLocation(HexGuide.MODID, "component_text")] =
            PageComponentText::class.java
        // hexguide:spellcasting —— 页面内嵌可交互法阵绘制区
        ClientBookRegistry.INSTANCE.pageTypes[ResourceLocation(HexGuide.MODID, "spellcasting")] =
            SpellcastingPage::class.java
        // hexguide:spellcast_demo —— 手册内演示图案绘制与栈变化
        ClientBookRegistry.INSTANCE.pageTypes[ResourceLocation(HexGuide.MODID, "spellcast_demo")] =
            SpellcastDemoPage::class.java
        // hexguide:note_page —— 笔记显示页（当前节第 N 个 NoteIota）
        ClientBookRegistry.INSTANCE.pageTypes[ResourceLocation(HexGuide.MODID, "note_page")] =
            cn.xm1221.HexGuide.patchouli.NotePage::class.java
        // hexguide:note_index —— 笔记目录页（列出节并跳转）
        ClientBookRegistry.INSTANCE.pageTypes[ResourceLocation(HexGuide.MODID, "note_index")] =
            cn.xm1221.HexGuide.patchouli.NoteIndex::class.java
    }

    private fun registerInlineClient() {
        val api = com.samsthenerd.inline.api.client.InlineClientAPI.INSTANCE
        api.addMatcher(cn.xm1221.HexGuide.compat.inline.IotaMatcher.INSTANCE)
        api.addRenderer(cn.xm1221.HexGuide.compat.inline.IotaInlineRender())
    }

    fun getConfigScreen(parent: Screen): Screen {
        return AutoConfig.getConfigScreen(HexGuideClientConfig.GlobalConfig::class.java, parent).get()
    }
}
