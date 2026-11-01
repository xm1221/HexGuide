package cn.xm1221.HexGuide.casting.actions

import at.petrak.hexcasting.api.casting.castables.ConstMediaAction
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.getBool
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.IotaType
import cn.xm1221.HexGuide.compat.inline.IotaInlineCodec
import cn.xm1221.HexGuide.networking.msg.MsgIotaSyncS2C
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.Style
import net.minecraft.server.level.ServerPlayer

/**
 * copy：把栈顶 Iota 转换为文本复制到剪贴板，并在聊天栏给出可点击的引用。
 * - bool=false：复制内联引用（iota:base85），不保存
 * - bool=true：复制 SNBT 原文，并把该 Iota 以 JSON 形式保存到 <gameDir>/hexguide/iotas/，
 *   之后可用 iota:hash.json 引用在任何地方内嵌显示；同时广播给所有玩家同步。
 */
class OpTextCopy: ConstMediaAction {
    override val argc: Int
        get() = 2

    override fun execute(
        args: List<Iota>,
        env: CastingEnvironment
    ): List<Iota> {
        val caster = env.castingEntity
        val iota = args.get(0)
        val bool = args.getBool(1,argc)
        var key = IotaInlineCodec.toPrefixed(iota)
        if(bool){
            key = IotaType.serialize(iota).toString()
        }
        val event = ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD,key)
        val event2 = HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable("hexguide.copy.hover"))
        val text = iota.display().copy().withStyle(Style.EMPTY.withClickEvent(event).withHoverEvent(event2))
        if(caster is ServerPlayer){
            caster.sendSystemMessage(text)
            // 仅 bool=true 时保存：以 JSON 形式保存到 <gameDir>/<ns>/iotas/，并提示资源引用
            if (bool) {
                val savedRef = IotaInlineCodec.saveToGameDir(iota)
                if (savedRef != null) {
                    val ref = "iota:$savedRef.json"
                    val saveMsg = Component.translatable("hexguide.copy.saved", ref)
                        .withStyle(Style.EMPTY.withClickEvent(ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, ref)))
                    caster.sendSystemMessage(saveMsg)
                    // 同步：服务器把该 iota 广播给所有玩家（含施法者——施法者客户端也需要文件才能内联渲染）
                    // 各玩家客户端把同一 ref 保存到自己的 <gameDir>/hexguide/iotas/，之后 iota:<ref>.json 都能加载
                    val syncMsg = MsgIotaSyncS2C(savedRef, IotaType.serialize(iota))
                    for (p in caster.server.playerList.players) {
                        syncMsg.sendToPlayer(p)
                    }
                }
            }
        }
        return listOf()
    }
}
