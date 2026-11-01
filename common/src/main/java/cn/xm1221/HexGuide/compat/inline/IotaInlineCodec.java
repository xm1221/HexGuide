package cn.xm1221.HexGuide.compat.inline;

import cn.xm1221.HexGuide.HexGuide;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.IotaType;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.architectury.platform.Platform;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterOutputStream;

/**
 * iota: 内联引用的编码/解码与文件存取。
 *
 * 与 Inline 模组 API **完全解耦**：本类不实现 InlineData，也不引用 com.samsthenerd.inline.*，
 * 因此 Inline 未安装时仍可安全加载——copy 图案、演示生成器、演示页解析、S2C 同步落盘都走这里。
 * 真正注册进 Inline 的部分（IotaInlineData / IotaMatcher / IotaInlineRender）只在
 * Platform.isModLoaded("inline") 成立时才会被类加载（见 HexGuideClient / HexGuide）。
 */
public final class IotaInlineCodec {

    private IotaInlineCodec() {}

    public static final ResourceLocation RENDERER_ID = new ResourceLocation(HexGuide.MODID, "iota");
    private static final int A85_BASE = 33;

    // ─── 引用格式：iota:<a85> 或 iota:<ns:path.json> ──────────

    /** 剥离 **n / 颜色后缀后的引用本体与显示属性 */
    public static final class Ref {
        /** 去掉后缀的引用本体（.json 引用保留扩展名） */
        public final String core;
        /** 强制渲染颜色（ARGB）；-1 = 继承当前文本样式颜色 */
        public final int forcedColor;
        /** 强制换行（**n） */
        public final boolean wrap;

        Ref(String core, int forcedColor, boolean wrap) {
            this.core = core;
            this.forcedColor = forcedColor;
            this.wrap = wrap;
        }
    }

    /**
     * 拆分引用后缀（会从引用中剥离，不影响解码/资源名）：
     * - *b → 强制黑色；*w → 强制白色；*RRGGBB → 强制指定 RGB
     * - **n → 强制换行显示
     */
    public static Ref splitSuffixes(String raw) {
        boolean wrap = raw.endsWith("**n");
        if (wrap) raw = raw.substring(0, raw.length() - 3); // 剥离 **n（3 字符）
        int forced = parseForcedColor(raw);
        if (forced != -1) raw = stripColorSuffix(raw);
        return new Ref(raw, forced, wrap);
    }

    /** 解析颜色后缀；无后缀返回 -1 */
    private static int parseForcedColor(String s) {
        if (s.endsWith("*b")) return 0xFF000000;
        if (s.endsWith("*w")) return 0xFFFFFFFF;
        if (s.length() >= 7 && s.charAt(s.length() - 7) == '*') {
            try {
                return 0xFF000000 | Integer.parseInt(s.substring(s.length() - 6), 16);
            } catch (Exception ignored) {}
        }
        return -1;
    }

    /** 剥离颜色后缀 */
    private static String stripColorSuffix(String s) {
        if (s.endsWith("*b") || s.endsWith("*w")) return s.substring(0, s.length() - 2);
        if (s.length() >= 7 && s.charAt(s.length() - 7) == '*') return s.substring(0, s.length() - 7);
        return s;
    }

    /**
     * 把 iota:&lt;raw&gt; 的 raw 部分解析成 Iota（不含 Inline 依赖）。
     * 以 .json 结尾走资源/游戏目录文件，否则按 Ascii85 解码。失败返回 null。
     */
    @Nullable
    public static Iota resolve(String raw) {
        Ref ref = splitSuffixes(raw);
        if (ref.core.endsWith(".json")) return loadFromResource(ref.core);
        return decodeA85ToIota(ref.core);
    }

    // ─── 编解码 ────────────────────────────────────────────────

    /** a85 NBT → 解压 → IotaType.deserialize；失败返回 null */
    @Nullable
    public static Iota decodeA85ToIota(String raw) {
        try {
            byte[] comp = decodeA85(raw);
            var out = new ByteArrayOutputStream();
            var infl = new InflaterOutputStream(out);
            infl.write(comp);
            infl.flush();
            infl.close();
            var tag = NbtIo.read(new DataInputStream(new ByteArrayInputStream(out.toByteArray())));
            if (tag != null) return IotaType.deserialize((CompoundTag) tag, null);
        } catch (Exception ignored) {}
        return null;
    }

    /** Iota → NBT → deflate → Ascii85 */
    public static String encode(Iota iota) {
        var tag = IotaType.serialize(iota);
        var baos = new ByteArrayOutputStream();
        try { NbtIo.write(tag, new DataOutputStream(baos)); } catch (IOException ignored) {}
        byte[] b = baos.toByteArray();
        try {
            var o = new ByteArrayOutputStream();
            var d = new DeflaterOutputStream(o);
            d.write(b);
            d.flush();
            d.close();
            b = o.toByteArray();
        } catch (Exception ignored) {}
        return encodeA85(b);
    }

    /** 完整内联引用：iota:&lt;a85&gt; */
    public static String toPrefixed(Iota iota) { return "iota:" + encode(iota); }

    // ─── 资源加载 ──────────────────────────────────────────────

    /** 加载 Iota：优先资源管理器 assets/&lt;ns&gt;/iotas/&lt;path&gt;.json，回退游戏目录 &lt;gameDir&gt;/&lt;ns&gt;/iotas/&lt;path&gt;.json */
    @Nullable
    private static Iota loadFromResource(String ref) {
        try {
            // 无冒号 → 默认命名空间 hexguide（短引用 iota:name.json）
            int colon = ref.lastIndexOf(':');
            String ns, path;
            if (colon > 0) {
                ns = ref.substring(0, colon);
                path = ref.substring(colon + 1);
            } else {
                ns = HexGuide.MODID;
                path = ref;
            }
            if (!path.endsWith(".json")) path += ".json";

            // 1) 资源管理器（模组资源/资源包）
            ResourceLocation rl = new ResourceLocation(ns, "iotas/" + path);
            var mgr = Minecraft.getInstance().getResourceManager();
            Optional<Resource> opt = mgr.getResource(rl);
            if (opt.isPresent()) {
                try (var reader = new InputStreamReader(opt.get().open())) {
                    return deserializeJson(JsonParser.parseReader(reader));
                }
            }

            // 2) 游戏目录（运行时自动保存的文件）
            Path file = Platform.getGameFolder().resolve(ns).resolve("iotas").resolve(path);
            if (Files.exists(file)) {
                return deserializeJson(JsonParser.parseString(Files.readString(file)));
            }
            HexGuide.LOGGER.warn("[IotaInlineData] 找不到 iota 资源: assets/{}/iotas/{} 或游戏目录 {}", ns, path, file);
            return null;
        } catch (Exception e) {
            HexGuide.LOGGER.warn("[IotaInlineData] 加载 iota 资源异常: {}", ref, e);
            return null;
        }
    }

    /** 解析 iota 资源文件：{"nbt":"<nbt字符串>"} 或直接 NBT JSON */
    private static Iota deserializeJson(JsonElement elem) throws CommandSyntaxException {
        if (elem != null && elem.isJsonObject() && elem.getAsJsonObject().has("nbt")) {
            String nbt = elem.getAsJsonObject().get("nbt").getAsString();
            return IotaType.deserialize(TagParser.parseTag(nbt), null);
        }
        return IotaType.deserialize(TagParser.parseTag(elem.toString()), null);
    }

    /** 按 "ns:path"（可带 .json）加载 Iota 并返回其 display Component；失败返回原引用文本 */
    public static net.minecraft.network.chat.Component displayFromRef(String ref) {
        Iota iota = loadFromResource(ref);
        return iota != null ? iota.display() : net.minecraft.network.chat.Component.literal(ref);
    }

    /**
     * 服务端专用的 iota 资源加载：只查服务端数据包（[server] 非空时）与游戏目录，
     * **不碰客户端** `Minecraft.getInstance()`，因此可以在专用服务器上安全调用。
     *
     * @param server 服务端实例；null 时只查游戏目录（例如 `/hexguide demoexport` 之外的非会话上下文会传 null）
     */
    @Nullable
    public static Iota loadFromServer(@Nullable net.minecraft.server.MinecraftServer server, String ref) {
        try {
            Ref parsed = splitSuffixes(ref);
            String core = parsed.core;
            int colon = core.lastIndexOf(':');
            String ns, path;
            if (colon > 0) {
                ns = core.substring(0, colon);
                path = core.substring(colon + 1);
            } else {
                ns = HexGuide.MODID;
                path = core;
            }
            if (!path.endsWith(".json")) path += ".json";

            if (server != null) {
                ResourceLocation rl = new ResourceLocation(ns, "iotas/" + path);
                var opt = server.getResourceManager().getResource(rl);
                if (opt.isPresent()) {
                    try (var reader = new InputStreamReader(opt.get().open())) {
                        return deserializeJson(JsonParser.parseReader(reader));
                    }
                }
            }

            Path file = Platform.getGameFolder().resolve(ns).resolve("iotas").resolve(path);
            if (Files.exists(file)) {
                return deserializeJson(JsonParser.parseString(Files.readString(file)));
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    // ─── 保存到游戏目录 ────────────────────────────────────────

    /**
     * 将 Iota 以 JSON 形式自动保存到 &lt;gameDir&gt;/&lt;ns&gt;/iotas/&lt;hash&gt;[-&lt;counter&gt;].json。
     * 返回短资源名 "hash[-counter]"（无命名空间，完整引用为 iota:hash.json，可放进书本 116px 页面不被换行截断）。
     * 失败返回 null。
     */
    @Nullable
    public static String saveToGameDir(Iota iota) {
        try {
            String tagStr = IotaType.serialize(iota).toString();
            String ns = HexGuide.MODID;
            String hash = shortHash(tagStr);

            Path dir = Platform.getGameFolder().resolve(ns).resolve("iotas");
            Files.createDirectories(dir);

            // 短名 hash.json；若已存在同名文件，追加 -1、-2...
            // 注意：不能用 iota 类型键拼文件名（ResourceLocation 含 ':'，Windows 文件名非法）
            String name = hash;
            Path file = dir.resolve(name + ".json");
            int counter = 0;
            while (Files.exists(file) && counter < 100) {
                counter++;
                name = hash + "-" + counter;
                file = dir.resolve(name + ".json");
            }
            Files.writeString(file, saveJson(tagStr));

            return name;
        } catch (Exception ignored) { return null; }
    }

    /** 包装为 {"nbt":"<nbt字符串>"}，Gson 自动转义 */
    private static String saveJson(String tagStr) {
        JsonObject obj = new JsonObject();
        obj.add("nbt", new JsonPrimitive(tagStr));
        return obj.toString();
    }

    /**
     * 按指定 ref 把 iota NBT 保存到 &lt;gameDir&gt;/&lt;ns&gt;/iotas/&lt;ref&gt;.json。
     * 用于同步（MsgIotaSyncS2C）：所有玩家以同一 ref 保存，保证 iota:&lt;ref&gt;.json 在各端可加载。
     */
    public static void saveToGameDirRef(String ref, CompoundTag iotaNbt) {
        try {
            Path dir = Platform.getGameFolder().resolve(HexGuide.MODID).resolve("iotas");
            Files.createDirectories(dir);
            String tagStr = iotaNbt.toString();
            Files.writeString(dir.resolve(ref + ".json"), saveJson(tagStr));
        } catch (Exception ignored) {}
    }

    /** 序列化文本的 SHA-256 前 6 位十六进制 */
    private static String shortHash(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 3; i++) sb.append(String.format("%02x", d[i] & 0xFF));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode() & 0xFFFFFF);
        }
    }

    // ─── Ascii85 (!-u, z=0000) ──────────────────────────────────

    static String encodeA85(byte[] d) {
        var sb = new StringBuilder();
        int i = 0;
        while (i + 4 <= d.length) {
            long v = ((d[i++] & 0xFFL) << 24) | ((d[i++] & 0xFFL) << 16)
                   | ((d[i++] & 0xFFL) << 8) | (d[i++] & 0xFFL);
            if (v == 0) { sb.append('z'); continue; }
            sb.append((char)(A85_BASE + v / 52200625)); v %= 52200625;
            sb.append((char)(A85_BASE + v / 614125));   v %= 614125;
            sb.append((char)(A85_BASE + v / 7225));     v %= 7225;
            sb.append((char)(A85_BASE + v / 85));       v %= 85;
            sb.append((char)(A85_BASE + v));
        }
        if (i < d.length) {
            long v = 0; int rem = d.length - i;
            for (int j = i; j < d.length; j++) v = (v << 8) | (d[j] & 0xFF);
            v <<= (4 - rem) * 8;
            for (int j = 0; j <= rem; j++) { sb.append((char)(A85_BASE + v / 52200625)); v = (v % 52200625) * 85; }
        }
        return sb.toString();
    }

    static byte[] decodeA85(String s) {
        var out = new ByteArrayOutputStream();
        int[] g = new int[5]; int gi = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == 'z') { out.write(new byte[4], 0, 4); gi = 0; continue; }
            g[gi++] = c - A85_BASE;
            if (gi == 5) {
                long v = ((((g[0] * 85L + g[1]) * 85 + g[2]) * 85 + g[3]) * 85 + g[4]);
                out.write((int)(v >> 24)); out.write((int)(v >> 16));
                out.write((int)(v >> 8)); out.write((int)(v & 0xFF));
                gi = 0;
            }
        }
        if (gi > 0) {
            for (int j = gi; j < 5; j++) g[j] = 84;
            long v = ((((g[0] * 85L + g[1]) * 85 + g[2]) * 85 + g[3]) * 85 + g[4]);
            for (int j = 0; j < gi - 1; j++) out.write((int)(v >> (24 - 8 * j)) & 0xFF);
        }
        return out.toByteArray();
    }
}
