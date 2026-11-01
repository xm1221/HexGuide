package cn.xm1221.HexGuide.networking.handler

import cn.xm1221.HexGuide.config.HexGuideServerConfig
import cn.xm1221.HexGuide.networking.msg.*
import cn.xm1221.HexGuide.patchouli.SpellcastDemoPage
import cn.xm1221.HexGuide.registry.HexGuideCreativeTab
import dev.architectury.networking.NetworkManager.PacketContext
import net.minecraft.client.Minecraft

fun HexGuideMessageS2C.applyOnClient(ctx: PacketContext) = ctx.queue {
    when (this) {
        is MsgSyncConfigS2C -> HexGuideServerConfig.onSyncConfig(serverConfig)

        // 演示"真执行"结果：路由到当前显示中的演示页
        is MsgBookExecDemoS2C -> {
            Minecraft.getInstance().execute {
                for (page in SpellcastDemoPage.ACTIVE) page.onExecResult(image, resolutionType)
            }
        }

        // 演示配置内容：路由到对应演示页
        is MsgBookLoadSpellplayS2C -> {
            Minecraft.getInstance().execute {
                for (page in SpellcastDemoPage.ACTIVE) {
                    if (page.matches(ns, name)) page.onSpellplayLoaded(json, patternVector)
                }
            }
        }

        // 创造标签页排除列表（服务端 tag 完整）
        is MsgExcludedPatternsS2C -> {
            Minecraft.getInstance().execute {
                HexGuideCreativeTab.setExcludedPatterns(ids.toSet())
            }
        }

        // 笔记全量同步 → 更新客户端缓存（手册笔记页即热更新）
        is MsgNotesSyncS2C -> {
            Minecraft.getInstance().execute {
                cn.xm1221.HexGuide.api.notes.ClientNotes.applySync(uuid, sections)
            }
        }

        // iota 同步（OpTextCopy 保存广播）→ 以相同 ref 保存到本端游戏目录
        is MsgIotaSyncS2C -> {
            Minecraft.getInstance().execute {
                cn.xm1221.HexGuide.compat.inline.IotaInlineCodec.saveToGameDirRef(ref, iotaNbt)
            }
        }

        // 笔记分享串 → 写入系统剪贴板 + 聊天栏留一条可点击复制的消息（兜底/便于多次粘贴）
        is MsgNoteShareS2C -> {
            Minecraft.getInstance().execute {
                Minecraft.getInstance().keyboardHandler.setClipboard(shareString)
                val copyable = net.minecraft.network.chat.Component.translatable(
                    messageKey,
                    net.minecraft.network.chat.Component.literal(shareString).withStyle(
                        net.minecraft.network.chat.Style.EMPTY.withColor(0x55FFFF)
                            .withClickEvent(
                                net.minecraft.network.chat.ClickEvent(
                                    net.minecraft.network.chat.ClickEvent.Action.COPY_TO_CLIPBOARD, shareString
                                )
                            )
                            .withHoverEvent(
                                net.minecraft.network.chat.HoverEvent(
                                    net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT,
                                    net.minecraft.network.chat.Component.translatable("hexguide.copy.hover")
                                )
                            )
                    )
                )
                net.minecraft.client.Minecraft.getInstance().gui.chat.addMessage(copyable)
            }
        }
    }
}
