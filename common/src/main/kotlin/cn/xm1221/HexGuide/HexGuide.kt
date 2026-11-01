package cn.xm1221.HexGuide

import cn.xm1221.HexGuide.config.HexGuideServerConfig
import cn.xm1221.HexGuide.networking.HexGuideNetworking
import cn.xm1221.HexGuide.networking.SecurityGuards
import cn.xm1221.HexGuide.registry.HexGuideActions
import cn.xm1221.HexGuide.registry.HexGuideCreativeTab
import cn.xm1221.HexGuide.registry.HexGuideIotaTypes
import cn.xm1221.HexGuide.registry.HexGuideItems
import dev.architectury.event.events.common.PlayerEvent
import dev.architectury.platform.Platform
import net.minecraft.resources.ResourceLocation
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

object HexGuide {
    const val MODID = "hexguide"

    @JvmField
    val LOGGER: Logger = LogManager.getLogger(MODID)

    @JvmStatic
    fun id(path: String) = ResourceLocation(MODID, path)

    fun init() {
        HexGuideServerConfig.init()
        initRegistries(
            HexGuideActions,
            HexGuideCreativeTab,
            HexGuideIotaTypes,
            HexGuideItems
        )
        HexGuideNetworking.init()
        // Inline 软兼容：未安装时跳过，Iota 内联渲染不可用
        if (Platform.isModLoaded("inline")) {
            cn.xm1221.HexGuide.compat.inline.InlineHexGuide.init()
        } else {
            LOGGER.info("Inline not present; iota inline rendering disabled")
        }
        HexGuideTagFixer.init() // 互联时把 Fabric 路径的 hexcasting:action tag 补全
        cn.xm1221.HexGuide.commands.HexGuideCommands.register() // /hexguide export/import/authority
        // 玩家进服时下发其笔记（手册笔记页）；存档挂主世界，跨维一致
        PlayerEvent.PLAYER_JOIN.register { player ->
            val notes = cn.xm1221.HexGuide.api.notes.PlayerNotes.get(player.serverLevel())
            cn.xm1221.HexGuide.networking.handler.syncNotes(player, notes)
        }
        PlayerEvent.PLAYER_QUIT.register { player ->
            SecurityGuards.forget(player.uuid)
        }
    }

    fun initServer() {
        HexGuideServerConfig.initServer()
    }
}
