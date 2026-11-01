package cn.xm1221.HexGuide.networking.msg

import net.minecraft.network.FriendlyByteBuf

/**
 * 服务端→客户端：把一段文本交给**客户端**复制到系统剪贴板。
 *
 * 为什么需要这个包：Minecraft 的 `ClickEvent.COPY_TO_CLIPBOARD` 只能由**客户端的玩家点击**触发，
 * 服务端无法直接把内容塞进剪贴板。因此 `/hexguide export <索引> clipboard` 由服务端生成分享串，
 * 用本包下发，客户端收到后直接写入剪贴板，同时在聊天栏留一条可再次点击复制的消息（兜底）。
 *
 * 包体很小（≤ [cn.xm1221.HexGuide.networking.SecurityGuards.MAX_SHARE_CHARS] 字符），只含分享串与提示键。
 */
class MsgNoteShareS2C(
    val shareString: String,
    /** 客户端提示所用的 lang 键，例如 `hexguide.cmd.share.copied` */
    val messageKey: String,
) : HexGuideMessageS2C {
    companion object : HexGuideMessageCompanion<MsgNoteShareS2C> {
        override val type = MsgNoteShareS2C::class.java

        override fun decode(buf: FriendlyByteBuf) = MsgNoteShareS2C(
            shareString = buf.readUtf(cn.xm1221.HexGuide.networking.SecurityGuards.MAX_SHARE_CHARS),
            messageKey = buf.readUtf(64),
        )

        override fun MsgNoteShareS2C.encode(buf: FriendlyByteBuf) {
            buf.writeUtf(shareString)
            buf.writeUtf(messageKey)
        }
    }
}
