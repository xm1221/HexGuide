package cn.xm1221.HexGuide.compat.inline;

import cn.xm1221.HexGuide.HexGuide;
import com.samsthenerd.inline.api.matching.InlineMatch;
import com.samsthenerd.inline.api.matching.MatchContext;
import com.samsthenerd.inline.api.matching.MatcherInfo;
import com.samsthenerd.inline.api.matching.RegexMatcher;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Tuple;
import org.jetbrains.annotations.Nullable;

import java.util.regex.MatchResult;
import java.util.regex.Pattern;

/**
 * 匹配 iota:<a85>（Ascii85 压缩格式）或 iota:<ns:path.json>（资源文件引用）。
 */
public class IotaMatcher implements RegexMatcher {

    public static final IotaMatcher INSTANCE = new IotaMatcher();
    private static final ResourceLocation ID = new ResourceLocation(HexGuide.MODID, "iota");
    private static final MatcherInfo INFO = MatcherInfo.fromId(ID);

    /** iota: 前缀 + 任意字符 */
    private static final Pattern REGEX = Pattern.compile(
        "(?<escaped>\\\\\\\\)?iota:(?<raw>[!-~]+)",
        Pattern.CASE_INSENSITIVE);

    @Override public Pattern getRegex() { return REGEX; }

    @Override @Nullable
    public InlineMatch getMatch(MatchResult mr, MatchContext ctx) { return null; }

    @Override
    public Tuple<InlineMatch, Integer> getMatchAndGroup(MatchResult mr, MatchContext ctx) {
        if (mr.group(1) != null && !mr.group(1).isEmpty())
            return new Tuple<>(new InlineMatch.TextMatch(Component.literal("")), 1);

        String raw = mr.group(2);
        if (raw == null || raw.isEmpty()) return new Tuple<>(null, 0);

        IotaInlineData data = new IotaInlineData(raw);
        if (data.getOrDeserialize() == null) {
            if (IotaInlineCodec.splitSuffixes(raw).core.endsWith(".json")) {
                HexGuide.LOGGER.warn("[IotaInlineData] 资源 iota 解析失败: {}", raw);
            }
            return new Tuple<>(null, 0);
        }

        return new Tuple<>(new InlineMatch.DataMatch(data, data.getExtraStyle()), 0);
    }

    @Override public MatcherInfo getInfo() { return INFO; }
    @Override public ResourceLocation getId() { return ID; }
}
