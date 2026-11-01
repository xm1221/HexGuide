package cn.xm1221.HexGuide.compat.inline;

import at.petrak.hexcasting.api.casting.iota.Iota;
import com.samsthenerd.inline.api.InlineData;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;

/**
 * Inline 渲染用的数据包装：只负责 InlineData 接口与显示样式。
 *
 * 编解码、文件存取全部委托给 {@link IotaInlineCodec}——本类是唯一触碰 com.samsthenerd.inline.* 的地方之一，
 * 只会在 Inline 已安装时被类加载（IotaMatcher 由 HexGuideClient 在 isModLoaded("inline") 下注册）。
 */
public class IotaInlineData implements InlineData<IotaInlineData> {

    public static final ResourceLocation RENDERER_ID = IotaInlineCodec.RENDERER_ID;

    private final String raw;
    private final IotaInlineCodec.Ref ref;
    private Iota cached;

    public IotaInlineData(String raw) {
        this.raw = raw;
        this.ref = IotaInlineCodec.splitSuffixes(raw);
    }

    /** 强制渲染颜色（ARGB），-1 表示继承文本样式 */
    public int getForcedColor() { return ref.forcedColor; }

    /** 是否强制换行显示（**n 后缀） */
    public boolean isWrapNewline() { return ref.wrap; }

    public Iota getOrDeserialize() {
        if (cached == null) cached = IotaInlineCodec.resolve(raw);
        return cached;
    }

    @Override public InlineDataType<IotaInlineData> getType() { return IotaInlineDataType.INSTANCE; }
    @Override public ResourceLocation getRendererId() { return RENDERER_ID; }

    @Override public Style getExtraStyle() {
        Iota i = getOrDeserialize();
        if (i == null) return Style.EMPTY;
        String reference = "iota:" + ref.core;
        return Style.EMPTY
            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, i.display()))
            .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, reference));
    }

    @Override public Component asText(boolean w) {
        Iota i = getOrDeserialize();
        return i == null ? Component.literal("❌") : i.display();
    }

    public String getRaw() { return raw; }
}
