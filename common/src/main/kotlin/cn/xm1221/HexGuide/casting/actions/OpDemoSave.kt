package cn.xm1221.HexGuide.casting.actions

import at.petrak.hexcasting.api.casting.castables.ConstMediaAction
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.getList
import at.petrak.hexcasting.api.casting.iota.Iota
import cn.xm1221.HexGuide.api.notes.NoteIota
import cn.xm1221.HexGuide.demo.DemoGenerator
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.Style
import net.minecraft.server.level.ServerPlayer

/**
 * demo：取栈顶 ListIota → 生成并保存 spellplay 演示草稿。
 *
 * 栈（顶到底）：`[列表] demo`
 * - 列表里的 PatternIota → 图案步骤；其他 iota → `push` 步骤（见 [DemoGenerator.generate]）
 * - 若列表**第一个元素是 NoteIota**，用它的标题当演示标题与文件名（笔记 → 动画的最短路径）
 * - 否则标题为空，文件名为 `demo_<时间戳>_<随机>`
 *
 * 生成位置：`<gameDir>/hexguide/spellplays/<name>.json`，可直接拷进数据包
 * `data/<ns>/spellplays/`，在手册演示页用 `"demo": "<ns>:<name>"` 引用。
 * 施法者在聊天栏收到可点击复制路径的提示。
 */
class OpDemoSave : ConstMediaAction {

    override val argc: Int
        get() = 1

    override fun execute(args: List<Iota>, env: CastingEnvironment): List<Iota> {
        val list = args.getList(0, argc).toList()
        if (list.isEmpty()) return emptyList()

        // 列表首个元素是 NoteIota → 借它的标题当演示标题（笔记一键变动画）
        val title = (list.firstOrNull() as? NoteIota)?.title.orEmpty()
        val name = if (title.isNotEmpty()) DemoGenerator.sanitizeName(title) else DemoGenerator.defaultName()

        val ref = DemoGenerator.save(list, name, title = title) ?: return emptyList()

        val caster = env.castingEntity
        if (caster is ServerPlayer) {
            notifySaved(caster, name, ref)
        }
        return emptyList()
    }

    private fun notifySaved(player: ServerPlayer, name: String, ref: String) {
        val clickStyle = Style.EMPTY
            .withClickEvent(ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, ref))
            .withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable("hexguide.copy.hover")))
        player.sendSystemMessage(Component.translatable("hexguide.demo.saved", name).withStyle(clickStyle))
        player.sendSystemMessage(
            Component.translatable("hexguide.demo.saved_hint", name).withStyle(Style.EMPTY.withColor(0xAAAAAA))
        )
    }
}
