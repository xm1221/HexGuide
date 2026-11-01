package cn.xm1221.HexGuide.networking.msg

import net.minecraft.network.FriendlyByteBuf

/**
 * 客户端→服务端：紫水晶笔保存笔记。
 * 服务端处理：每页消耗副手一张纸 → 生成携带该页 NoteIota 的"笔记残页"。
 * 不写入 PlayerNotes；条目只能通过 note/import 图案加入笔记库。
 */
class MsgNotesSaveC2S(
    val title: String,
    val pages: List<String>
) : HexGuideMessageC2S {
    companion object : HexGuideMessageCompanion<MsgNotesSaveC2S> {
        override val type = MsgNotesSaveC2S::class.java

        override fun decode(buf: FriendlyByteBuf) = MsgNotesSaveC2S(
            title = buf.readUtf(64),
            pages = run {
                val out = mutableListOf<String>()
                val n = buf.readVarInt()
                repeat(n) { out.add(buf.readUtf(2048)) }
                out
            }
        )

        override fun MsgNotesSaveC2S.encode(buf: FriendlyByteBuf) {
            buf.writeUtf(title)
            buf.writeVarInt(pages.size)
            for (p in pages) buf.writeUtf(p)
        }
    }
}
