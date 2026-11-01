package cn.xm1221.HexGuide.patchouli;

import at.petrak.hexcasting.api.casting.ActionRegistryEntry;
import at.petrak.hexcasting.api.casting.iota.DoubleIota;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.IotaType;
import at.petrak.hexcasting.api.casting.iota.ListIota;
import at.petrak.hexcasting.api.casting.iota.NullIota;
import at.petrak.hexcasting.api.casting.iota.PatternIota;
import at.petrak.hexcasting.api.casting.iota.Vec3Iota;
import at.petrak.hexcasting.api.casting.math.HexCoord;
import at.petrak.hexcasting.api.casting.math.HexDir;
import at.petrak.hexcasting.api.casting.math.HexPattern;
import at.petrak.hexcasting.client.gui.GuiSpellcasting;
import at.petrak.hexcasting.common.lib.HexSounds;
import at.petrak.hexcasting.common.lib.hex.HexActions;
import cn.xm1221.HexGuide.HexGuide;
import cn.xm1221.HexGuide.compat.inline.IotaInlineCodec;
import cn.xm1221.HexGuide.networking.msg.MsgBookExecDemoC2S;
import cn.xm1221.HexGuide.networking.msg.MsgBookLoadSpellplayC2S;
import cn.xm1221.HexGuide.networking.msg.MsgBookPushIotaC2S;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import org.jetbrains.annotations.Nullable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import vazkii.patchouli.api.IVariable;
import vazkii.patchouli.client.book.BookPage;
import vazkii.patchouli.client.book.gui.GuiBook;
import vazkii.patchouli.client.book.gui.GuiBookEntry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 页面类型 hexguide:spellcast_demo —— 在手册中演示"图案绘制"与"栈变化"。
 *
 * 演示由数据包中的配置文件定义（data/&lt;ns&gt;/spellplays/&lt;name&gt;.json，页面用 ns:name 引用，
 * 默认 ns = hexguide）。配置是一串有序步骤：
 * - execute（默认类型）：绘制图案（执行蓝，颜色可配置）→ 把本地 CastingImage 上传服务端
 *   运行该图案（新 VM，不碰玩家法杖栈）→ 结果传回 → 本地栈 = 结果
 * - push：绘制图案 → 把配置的自定义 iota 压入本地栈（纯本地，不发服务端）
 * - clear：清空画布（只清网格，不清栈）
 * 规则：每一步之前默认清空画布（网格）；默认颜色为执行蓝；全程有音效。
 *
 * JSON 用法：
 * {
 *   "type": "hexguide:spellcast_demo",
 *   "demo": "hexguide:text",           // 数据包 data/hexguide/spellplays/text.json
 *   "hex_size": 28
 * }
 */
public class SpellcastDemoPage extends BookPage {

    /** 当前显示中的演示页（服务端执行结果路由） */
    public static final Set<SpellcastDemoPage> ACTIVE = new HashSet<>();

    IVariable demo;
    IVariable hex_size;

    transient GuiSpellcasting spellcasting;
    transient SpellcastingButtonWidget playButton;

    /** 步骤定义 */
    static class Step {
        String type = "execute";   // execute | push | clear
        String sig = "";
        String action = "";        // 已注册图案 id（如 hexcasting:get_caster），优先于 sig
        String startDir = "NORTH_EAST";
        int color = -1;            // ARGB，-1 = 默认（执行蓝）
        CompoundTag pushIota;      // push 步骤：入栈的 iota NBT
        int interval = 0;          // 0 = 用全局 interval
        int q = 0, r = 0;          // 图案起始网格坐标（默认 0,0 = 画布中心，因 coordsOffset 已居中）
        int peekIndex = -1;        // peek 步骤：移除的栈下标（栈顶为 0）；-1 = 未指定
        List<Integer> peekIndices; // peek 步骤：多个下标
        String title = "";         // 自定义标题（空 = 默认：action 的本地化名称）
    }

    transient List<Step> steps = new ArrayList<>();
    transient int nextStep;
    transient float animTicks;
    /** 当前步执行后的等待时长（tick）；初始 0 = 第一步立即播放 */
    transient int currentWait;
    transient boolean playing = true;
    /** 配置文件的全局大标题（未播放时显示） */
    transient String demoTitle = "";
    /** 全局转义颜色（考察/内省转义的图案颜色），默认黄 */
    transient int escapedColor = 0xFFFFD93D;
    /** pattern_vector 默认 origin 表：action id → [q, r]（服务端 data/hexguide/pattern_vector.json 回传） */
    transient Map<String, int[]> patternVector = new HashMap<>();
    /** 最近加入的图案下标（用于执行结果 ERRORED 时染红） */
    transient int lastPatternIdx = -1;
    transient boolean lastStepCustomColor;

    private static final int BTN_Y = 138, BTN_H = 14;
    private static final int PLAY_X = 4, PLAY_W = 52;
    private static final int RESTART_X = 60, RESTART_W = 52;
    private static final int CANVAS_X = 0, CANVAS_Y = 22;
    private static final int CANVAS_W = GuiBook.PAGE_WIDTH;
    private static final int CANVAS_H = BTN_Y - CANVAS_Y - 10;

    private static final int STACK_W = 110, STACK_H = 56;
    private static final int STACK_X = 2, STACK_Y = CANVAS_Y + 2;

    /** 默认执行蓝（EVALUATED 的颜色近似值） */
    private static final int COLOR_EVALUATED = 0xFF_64c8ff;
    /** ERRORED 红 */
    private static final int COLOR_ERRORED = 0xFF_ff6b6b;

    @SuppressWarnings("unchecked")
    private static <X> X as(Object o) { return (X) o; }

    @Override
    public void onDisplayed(GuiBookEntry parent, int left, int top) {
        super.onDisplayed(parent, left, top);
        ACTIVE.add(this);
        if (spellcasting == null) {
            spellcasting = new GuiSpellcasting(InteractionHand.MAIN_HAND, new ArrayList<>(), List.of(), null, 1);
            // 不用 parent.width/height（经 Patchouli 继承的 MC Screen 字段，发布 remap 后不一致），改用窗口 GUI 尺寸
            var win = Minecraft.getInstance().getWindow();
            spellcasting.init(Minecraft.getInstance(), win.getGuiScaledWidth(), win.getGuiScaledHeight());
            Minecraft.getInstance().getSoundManager().stop(HexSounds.CASTING_AMBIANCE.getLocation(), null);

            BookSpellcastingAccess access = as(spellcasting);
            // 演示完全本地自足：拦截发包 + 写模式（不触发原版施法管线）
            access.setBlockSending$hexguide(true);
            access.setWriteMode$hexguide(true);
            float hs = hex_size != null ? hex_size.asNumber(28f).floatValue() : 28f;
            access.setHexSizeOverride$hexguide(hs);
            access.setCoordsOffset$hexguide(new Vec2(
                parent.bookLeft + left + CANVAS_W / 2f,
                parent.bookTop + top + CANVAS_H / 2f));

            loadDemo();
            nextStep = 0;
            animTicks = 0;
            playing = true;
        }
        this.playButton = new SpellcastingButtonWidget(PLAY_X, GuiBook.TOP_PADDING + BTN_Y, PLAY_W, BTN_H,
            playing ? "Pause" : "Play", this::togglePlay);
        parent.addWidget(this.playButton, pageNum);
        parent.addWidget(new SpellcastingButtonWidget(RESTART_X, GuiBook.TOP_PADDING + BTN_Y, RESTART_W, BTN_H,
            "Restart", this::restart), pageNum);
    }

    @Override
    public void onHidden(GuiBookEntry parent) {
        super.onHidden(parent);
        ACTIVE.remove(this);
    }

    // ─── 配置加载 ────────────────────────────────────────────

    /** 已请求的演示引用（用于服务端响应路由匹配） */
    transient String reqNs, reqName;

    /** 向服务端请求演示配置 data/&lt;ns&gt;/spellplays/&lt;name&gt;.json（配置到达后 onSpellplayLoaded 解析） */
    private void loadDemo() {
        steps = new ArrayList<>();
        String ref = demo != null ? demo.asString("") : "";
        if (ref.isEmpty()) return;
        int colon = ref.lastIndexOf(':');
        reqNs = colon > 0 ? ref.substring(0, colon) : "hexguide";
        reqName = colon > 0 ? ref.substring(colon + 1) : ref;
        new MsgBookLoadSpellplayC2S(reqNs, reqName).sendToServer();
    }

    /** 服务端响应是否属于本页 */
    public boolean matches(String ns, String name) {
        return reqNs != null && reqNs.equals(ns) && reqName != null && reqName.equals(name);
    }

    /** 服务端数据包返回的演示配置 → 解析并开始播放；patternVectorJson 为默认 origin 表（可为 null） */
    public void onSpellplayLoaded(String json, @Nullable String patternVectorJson) {
        if (json == null || spellcasting == null) return;
        try {
            patternVector = parsePatternVector(patternVectorJson);
            steps = parseSteps(json);
            nextStep = 0;
            animTicks = 0;
            // 应用全局转义颜色 + 重置执行状态
            BookSpellcastingAccess access = as(spellcasting);
            access.setDemoEscapedColor$hexguide(escapedColor);
            access.resetDemoParenState$hexguide();
        } catch (Exception e) {
            HexGuide.LOGGER.warn("[DemoPush] onSpellplayLoaded 异常", e);
        }
    }

    /** 解析 pattern_vector.json（{"<action id>": {"origin": [q, r]}, ...}）→ action → [q, r] 映射 */
    private static Map<String, int[]> parsePatternVector(@Nullable String json) {
        Map<String, int[]> out = new HashMap<>();
        if (json == null || json.isEmpty()) return out;
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                JsonElement oe = e.getValue();
                if (oe.isJsonObject() && oe.getAsJsonObject().has("origin")
                    && oe.getAsJsonObject().get("origin").isJsonArray()) {
                    JsonArray arr = oe.getAsJsonObject().getAsJsonArray("origin");
                    if (arr.size() >= 2) {
                        out.put(e.getKey(), new int[] { arr.get(0).getAsInt(), arr.get(1).getAsInt() });
                    }
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    private List<Step> parseSteps(String json) {
        List<Step> out = new ArrayList<>();
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        demoTitle = root.has("title") ? root.get("title").getAsString() : "";
        // 全局转义颜色（考察/内省转义的图案颜色），默认黄
        escapedColor = root.has("escaped_color") ? parseColor(root.get("escaped_color").getAsString())
                                                 : 0xFFFFD93D;
        int globalInterval = root.has("interval") ? root.get("interval").getAsInt() : 40;
        clearBefore = !root.has("clear_before") || root.get("clear_before").getAsBoolean();
        String globalStart = root.has("start_dir") ? root.get("start_dir").getAsString() : "NORTH_EAST";
        JsonArray arr = root.has("steps") ? root.getAsJsonArray("steps") : new JsonArray();
        // color / title 未给出时继承上一步的值（之后才是默认）
        int lastColor = -1;
        String lastTitle = "";
        for (JsonElement el : arr) {
            JsonObject o = el.getAsJsonObject();
            Step s = new Step();
            s.type = o.has("type") ? o.get("type").getAsString() : "execute";
            s.sig = o.has("pattern") ? o.get("pattern").getAsString() : "";
            s.action = o.has("action") ? o.get("action").getAsString() : "";
            s.startDir = o.has("start_dir") ? o.get("start_dir").getAsString() : globalStart;
            s.interval = o.has("interval") ? o.get("interval").getAsInt() : globalInterval;
            if (o.has("color")) {
                String colorStr = o.get("color").getAsString();
                if (colorStr.isEmpty()) {
                    s.color = -1;   // 显式空串 → 默认（执行蓝），并重置继承链
                    lastColor = -1;
                } else {
                    s.color = parseColor(colorStr);
                    lastColor = s.color; // 供后续步骤继承
                }
            } else {
                s.color = lastColor; // 继承上一步颜色
            }
            if (o.has("title")) {
                String titleStr = o.get("title").getAsString();
                if (titleStr.isEmpty()) {
                    s.title = "";   // 显式空串 → 默认（无标题，走 action 本地化），并重置继承链
                    lastTitle = "";
                } else {
                    s.title = titleStr;
                    lastTitle = titleStr; // 供后续步骤继承
                }
            } else {
                s.title = lastTitle; // 继承上一步标题
            }
            if (o.has("push")) {
                s.pushIota = resolvePushIota(o.get("push"));
            }
            // peek 步骤：单个 "index": N 或多个 "indices": [a, b, c]（栈顶为下标 0）
            if (o.has("index")) {
                s.peekIndex = o.get("index").getAsInt();
            } else if (o.has("indices")) {
                s.peekIndices = new ArrayList<>();
                for (JsonElement pe : o.getAsJsonArray("indices")) {
                    s.peekIndices.add(pe.getAsInt());
                }
            }
            // 起始网格坐标：优先 "origin": [q, r]，其次 "q"/"r"，再其次 pattern_vector 默认表（按 action），最后默认 [-1, 2]
            if (o.has("origin") && o.get("origin").isJsonArray()) {
                JsonArray oc = o.getAsJsonArray("origin");
                if (oc.size() >= 2) {
                    s.q = oc.get(0).getAsInt();
                    s.r = oc.get(1).getAsInt();
                }
            } else if (o.has("q") || o.has("r")) {
                s.q = o.has("q") ? o.get("q").getAsInt() : -1;
                s.r = o.has("r") ? o.get("r").getAsInt() : 2;
            } else if (!s.action.isEmpty()) {
                // action 存在且未显式给 origin → 查 pattern_vector（data/hexguide/pattern_vector.json）
                int[] pv = patternVector.get(s.action);
                if (pv != null) {
                    s.q = pv[0];
                    s.r = pv[1];
                } else {
                    s.q = -1;
                    s.r = 2;
                }
            } else {
                s.q = -1;
                s.r = 2;
            }
            out.add(s);
        }
        return out;
    }

    /**
     * 解析 push 的 iota：支持
     * - 字符串 "iota:&lt;a85&gt;" / "iota:&lt;ns:name.json&gt;"（OpTextCopy 储存/引用格式）
     * - 字符串 "double:&lt;数字&gt;" / "vec:{a,b,c}"（便携输入）
     * - JSON 对象 {"nbt":"&lt;SNBT&gt;"}（OpTextCopy 保存格式）或直接 iota NBT JSON
     */
    private static CompoundTag resolvePushIota(JsonElement el) {
        try {
            if (el.isJsonObject()) {
                JsonObject o = el.getAsJsonObject();
                if (o.has("nbt")) {
                    return TagParser.parseTag(o.get("nbt").getAsString());
                }
                return TagParser.parseTag(o.toString());
            }
            if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
                return resolveIotaString(el.getAsString());
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static CompoundTag resolveIotaString(String s) {
        try {
            // 空字符串 → 不入栈任何东西
            if (s.isEmpty()) return null;
            // "null" → NullIota
            if (s.equals("null")) {
                return IotaType.serialize(new NullIota());
            }
            if (s.startsWith("double:")) {
                double d = Double.parseDouble(s.substring(7).trim());
                return IotaType.serialize(new DoubleIota(d));
            }
            if (s.startsWith("vec:{")) {
                // "vec:{" 是 5 个字符，substring(5, len-1) 取出 "a,b,c"
                String inner = s.substring(5, s.length() - 1);
                String[] parts = inner.split(",");
                double x = Double.parseDouble(parts[0].trim());
                double y = Double.parseDouble(parts[1].trim());
                double z = Double.parseDouble(parts[2].trim());
                return IotaType.serialize(new Vec3Iota(new Vec3(x, y, z)));
            }
            if (s.startsWith("pattern:")) {
                // "pattern:<action id>" → 已注册图案的 PatternIota
                ActionRegistryEntry entry = HexActions.REGISTRY.get(new ResourceLocation(s.substring(8).trim()));
                if (entry != null) return IotaType.serialize(new PatternIota(entry.prototype()));
            }
            if (s.startsWith("pattern{")) {
                // "pattern{<朝向>,<笔顺>}" → 直接解析图案的 PatternIota；"pattern{" 是 8 字符
                // 用花括号而非方括号：避免在 iota:[...] 列表内与外层 [ 冲突
                String inner = s.substring(8, s.length() - 1);
                String[] parts = inner.split(",");
                HexDir dir = HexDir.valueOf(parts[0].trim().toUpperCase());
                HexPattern pat = HexPattern.fromAngles(parts[1].trim(), dir);
                return IotaType.serialize(new PatternIota(pat));
            }
            if (s.startsWith("pattern[")) {
                // 旧语法兼容（列表外仍可用）："pattern[<朝向>,<笔顺>]"；"pattern[" 是 8 字符
                String inner = s.substring(8, s.length() - 1);
                String[] parts = inner.split(",");
                HexDir dir = HexDir.valueOf(parts[0].trim().toUpperCase());
                HexPattern pat = HexPattern.fromAngles(parts[1].trim(), dir);
                return IotaType.serialize(new PatternIota(pat));
            }
            if (s.startsWith("iota:[")) {
                // "iota:[<元素>,...]" → ListIota 便携输入；"iota:[" 是 6 字符
                String inner = s.substring(6, s.length() - 1);
                List<Iota> elems = new ArrayList<>();
                for (String part : splitTopLevel(inner)) {
                    if (part.isEmpty()) continue;
                    CompoundTag t = resolveIotaString(part);
                    if (t == null) return null; // 任一元素解析失败 → 整个失败
                    elems.add(IotaType.deserialize(t, null));
                }
                return IotaType.serialize(new ListIota(elems));
            }
            if (s.startsWith("iota:")) {
                Iota iota = IotaInlineCodec.resolve(s.substring(5));
                if (iota != null) return IotaType.serialize(iota);
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * 按括号深度分割顶层逗号：避免 vec:{1,2,3} / pattern{...} / 嵌套 iota:[...]
     * 内部的逗号被误分割。三类括号全部跟踪：
     * - {} / ()：元素内部的分组符（vec、pattern）
     * - []：专属列表定界符（iota:[...] 嵌套列表）
     * 元素内部不含方括号（pattern 已用 {}），故 [] 可安全跟踪，支持嵌套列表。
     * 例：splitTopLevel("double:1, iota:[double:2, double:3], null")
     *   → ["double:1", "iota:[double:2, double:3]", "null"]
     */
    private static List<String> splitTopLevel(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '{' || c == '[' || c == '(') depth++;
            else if (c == '}' || c == ']' || c == ')') depth--;
            if (c == ',' && depth == 0) {
                out.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) out.add(cur.toString().trim());
        return out;
    }

    /** 解析颜色：支持 "#rrggbb" / "0xrrggbb" / 十进制 */
    private static int parseColor(String s) {
        try {
            s = s.trim();
            int rgb;
            if (s.startsWith("#")) rgb = (int) Long.parseLong(s.substring(1), 16);
            else if (s.startsWith("0x") || s.startsWith("0X")) rgb = (int) Long.parseLong(s.substring(2), 16);
            else rgb = Integer.parseInt(s);
            return 0xFF000000 | (rgb & 0xFFFFFF);
        } catch (Exception e) { return -1; }
    }

    /** 解析步骤图案：优先按已注册图案 id 查找（HexActions.REGISTRY），否则用 pattern 签名 */
    private static HexPattern resolvePattern(Step step) throws Exception {
        if (!step.action.isEmpty()) {
            ActionRegistryEntry entry = HexActions.REGISTRY.get(new ResourceLocation(step.action));
            if (entry != null) return entry.prototype();
        }
        HexDir dir = HexDir.valueOf(step.startDir.toUpperCase());
        return HexPattern.fromAngles(step.sig, dir);
    }

    /** 网格上绘制的图案：pattern 签名优先；未给 pattern 时退回 action 的注册表图案 */
    private static HexPattern resolveDisplayPattern(Step step) throws Exception {
        if (!step.sig.isEmpty()) {
            HexDir dir = HexDir.valueOf(step.startDir.toUpperCase());
            return HexPattern.fromAngles(step.sig, dir);
        }
        if (!step.action.isEmpty()) {
            ActionRegistryEntry entry = HexActions.REGISTRY.get(new ResourceLocation(step.action));
            if (entry != null) return entry.prototype();
        }
        throw new IllegalStateException("no display pattern");
    }

    /** 服务端执行的图案：action 的注册表图案优先；未给 action 时退回 pattern 签名 */
    private static HexPattern resolveExecPattern(Step step) throws Exception {
        if (!step.action.isEmpty()) {
            ActionRegistryEntry entry = HexActions.REGISTRY.get(new ResourceLocation(step.action));
            if (entry != null) return entry.prototype();
        }
        if (!step.sig.isEmpty()) {
            HexDir dir = HexDir.valueOf(step.startDir.toUpperCase());
            return HexPattern.fromAngles(step.sig, dir);
        }
        throw new IllegalStateException("no exec pattern");
    }

    transient boolean clearBefore = true;

    // ─── 按钮 ────────────────────────────────────────────────

    private void togglePlay() {
        playing = !playing;
        if (playButton != null) playButton.setLabel(playing ? "Pause" : "Play");
    }

    private void restart() {
        if (spellcasting != null) {
            BookSpellcastingAccess access = as(spellcasting);
            access.demoClearCanvas$hexguide();
            access.setStackClear$hexguide();
            access.resetDemoParenState$hexguide(); // 重置转义状态（考察/内省）
        }
        nextStep = 0;
        animTicks = 0;
        playing = true;
        if (playButton != null) playButton.setLabel("Pause");
    }

    // ─── 播放 ────────────────────────────────────────────────

    private void advance(float pticks) {
        if (!playing || spellcasting == null || steps.isEmpty()) return;
        if (nextStep >= steps.size()) return; // 播完
        // 先播放再等待：等待期过后立即执行当前步，执行后按该步 interval 等待
        animTicks += pticks;
        if (animTicks < currentWait) return;
        animTicks = 0;

        Step step = steps.get(nextStep);
        BookSpellcastingAccess access = as(spellcasting);
        // 每一步之前默认清空画布（网格；栈保留）
        if (clearBefore) access.demoClearCanvas$hexguide();

        switch (step.type) {
            case "push" -> doPush(access, step);
            case "push_in" -> doPushIn(access, step);
            case "clear" -> doClear(access, step);
            case "peek" -> doPeek(access, step);
            default -> doExecute(access, step);
        }
        currentWait = Math.max(1, step.interval);
        nextStep++;
    }

    /** peek：可选先绘制图案（配置了 pattern/action 则画，表示取走操作），再移除本地栈指定位置的 iota（栈顶为下标 0），越界忽略 */
    private void doPeek(BookSpellcastingAccess access, Step step) {
        // 有图案先绘制（像 push 一样可视化；无图案则只操作栈）
        if (!step.sig.isEmpty() || !step.action.isEmpty()) {
            try {
                HexPattern pat = resolveDisplayPattern(step);
                addPatternToGrid(access, step, pat);
            } catch (Exception ignored) {}
        }
        if (step.peekIndices != null && !step.peekIndices.isEmpty()) {
            // 多个：按下标从高到低移除（避免前一个移除影响后续下标）
            List<Integer> sorted = new ArrayList<>(step.peekIndices);
            sorted.sort((a, b) -> b - a);
            for (int idx : sorted) {
                access.demoPeek$hexguide(idx);
            }
        } else if (step.peekIndex >= 0) {
            access.demoPeek$hexguide(step.peekIndex);
        }
    }

    /** execute：网格绘制 pattern（无则 action）→ 上传本地 CastingImage（含转义/内省状态），服务端按原版逻辑执行 */
    private void doExecute(BookSpellcastingAccess access, Step step) {
        try {
            HexPattern display = resolveDisplayPattern(step);
            HexPattern exec = resolveExecPattern(step);
            addPatternToGrid(access, step, display);
            playSound(HexSounds.START_PATTERN);
            CompoundTag image = access.demoGetImageNbt$hexguide();
            // 服务端执行用 exec（action 优先）；发送其签名与方向
            // 转义/内省由服务端 CastingVM 原版逻辑处理，回传结果含状态与 resolutionType
            new MsgBookExecDemoC2S(exec.anglesSignature(), exec.getStartDir().name(), image).sendToServer();
        } catch (Exception ignored) {}
    }

    /** push：可选绘制 pattern（无则 action）→ 把配置的自定义 iota 压入本地栈（无图案也要入栈） */
    private void doPush(BookSpellcastingAccess access, Step step) {
        // 图案绘制与入栈分离：没配 pattern/action 时跳过绘制，但入栈不受影响
        if (!step.sig.isEmpty() || !step.action.isEmpty()) {
            try {
                HexPattern pat = resolveDisplayPattern(step);
                addPatternToGrid(access, step, pat);
                playSound(HexSounds.START_PATTERN);
            } catch (Exception ignored) {}
        }
        if (step.pushIota != null) {
            access.demoPushIota$hexguide(step.pushIota);
            playSound(HexSounds.CAST_NORMAL);
        }
    }

    /**
     * push_in：与 push 相同绘制图案，但入栈走服务端原版逻辑——
     * 上传本地 CastingImage 与 iota，由 CastingVM 按当前括号/转义状态处理：
     * escapeNext → 转义（进括号列表或压栈）；parenCount>0 → 进括号；否则压主栈。
     */
    private void doPushIn(BookSpellcastingAccess access, Step step) {
        if (!step.sig.isEmpty() || !step.action.isEmpty()) {
            try {
                HexPattern pat = resolveDisplayPattern(step);
                addPatternToGrid(access, step, pat);
                playSound(HexSounds.START_PATTERN);
            } catch (Exception ignored) {}
        }
        if (step.pushIota != null) {
            CompoundTag image = access.demoGetImageNbt$hexguide();
            new MsgBookPushIotaC2S(step.pushIota, image).sendToServer();
        }
    }

    /** clear：清空画布（网格），栈保留 */
    private void doClear(BookSpellcastingAccess access, Step step) {
        access.demoClearCanvas$hexguide();
        playSound(HexSounds.STAFF_RESET);
    }

    /** 加入网格（转义结果由服务端回传后在 onExecResult 标色） */
    private void addPatternToGrid(BookSpellcastingAccess access, Step step, HexPattern pat) {
        // 起始点用步骤配置的坐标（默认 0,0 = 画布中心）；不随步骤移动，避免超出屏幕
        HexCoord origin = new HexCoord(step.q, step.r);
        lastPatternIdx = access.patternCount$hexguide();
        lastStepCustomColor = step.color != -1;
        int color = step.color != -1 ? step.color : COLOR_EVALUATED;
        access.demoAddPattern$hexguide(pat, origin, color);
    }

    private void playSound(SoundEvent sound) {
        var mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(sound, 1f));
        }
    }

    /**
     * 当前顶部标题：
     * - 未播放（暂停/未开始/播完）→ 配置文件的全局大标题
     * - 播放中 → 当前步骤标题：配置的 title；未配置则用 action 的本地化名称（hexcasting.action.&lt;id&gt;），
     *   翻译键不存在或无 action 时不显示
     * title / demoTitle 支持语言键：若是对应语言文件里的键则翻译，否则原样显示。
     */
    private String currentTitle() {
        // 图案维持显示时才显示标题：播放中显示刚执行步骤的标题；未播放（暂停/未开始/播完）显示大标题
        if (!playing || steps.isEmpty() || nextStep == 0) {
            return i18nTitle(demoTitle);
        }
        Step s = steps.get(nextStep - 1);
        if (!s.title.isEmpty()) return i18nTitle(s.title);
        if (!s.action.isEmpty()) {
            String key = "hexcasting.action." + s.action;
            if (I18n.exists(key)) return I18n.get(key);
        }
        return "";
    }

    /** 标题文本：若是对应语言键则本地化，否则原样 */
    private static String i18nTitle(String t) {
        if (t.isEmpty()) return t;
        return I18n.exists(t) ? I18n.get(t) : t;
    }

    /** 服务端执行结果：更新本地栈显示；ESCAPED 用转义色、ERRORED 且未自定义颜色时染红 */
    public void onExecResult(CompoundTag image, String resolutionType) {
        if (spellcasting == null) return;
        BookSpellcastingAccess access = as(spellcasting);
        access.demoSetImageNbt$hexguide(image);
        if ("ERRORED".equals(resolutionType)) {
            if (!lastStepCustomColor && lastPatternIdx >= 0) {
                access.demoColor$hexguide(lastPatternIdx, COLOR_ERRORED);
            }
            playSound(HexSounds.CAST_FAILURE);
        } else if ("ESCAPED".equals(resolutionType)) {
            // 转义（考察/内省）：图案标转义色（escaped_color，可配置）
            if (!lastStepCustomColor && lastPatternIdx >= 0) {
                access.demoColor$hexguide(lastPatternIdx, escapedColor);
            }
            playSound(HexSounds.CAST_NORMAL);
        } else {
            playSound(HexSounds.CAST_SPELL);
        }
    }

    // ─── 渲染 ────────────────────────────────────────────────

    private void renderGridDots(GuiGraphics g, GuiSpellcasting sc, int sx, int sy, int sw, int sh) {
        HexCoord c0 = sc.pxToCoord(new Vec2(sx, sy));
        HexCoord c1 = sc.pxToCoord(new Vec2(sx + sw, sy + sh));
        int q0 = Math.min(c0.getQ(), c1.getQ()), q1 = Math.max(c0.getQ(), c1.getQ());
        int r0 = Math.min(c0.getR(), c1.getR()), r1 = Math.max(c0.getR(), c1.getR());
        for (int q = q0 - 1; q <= q1 + 1; q++) {
            for (int r = r0 - 1; r <= r1 + 1; r++) {
                Vec2 px = sc.coordToPx(new HexCoord(q, r));
                if (px.x >= sx && px.x < sx + sw && px.y >= sy && px.y < sy + sh) {
                    int x = Math.round(px.x), y = Math.round(px.y);
                    g.fill(x - 1, y - 1, x + 1, y + 1, 0x33_aaaaaa);
                }
            }
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float pticks) {
        if (spellcasting == null) return;

        advance(pticks);

        var font = Minecraft.getInstance().font;
        // 动态标题（手动居中；drawString 默认带阴影，需显式 false）：图案维持显示时显示步骤标题，未播放显示大标题
        String title = currentTitle();
        if (!title.isEmpty()) {
            graphics.drawString(font, title, CANVAS_W / 2 - font.width(title) / 2, 2, book.textColor, false);
        }

        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(-(parent.bookLeft + left), -(parent.bookTop + top), 0);

        int screenMouseX = mouseX + left;
        int screenMouseY = mouseY + top;

        int sx = parent.bookLeft + left + CANVAS_X;
        int sy = parent.bookTop + top + CANVAS_Y;

        renderGridDots(graphics, spellcasting, sx, sy, CANVAS_W, CANVAS_H);

        graphics.enableScissor(sx, sy, sx + CANVAS_W, sy + CANVAS_H);
        spellcasting.render(graphics, screenMouseX, screenMouseY, pticks);
        graphics.disableScissor();

        graphics.fill(sx, sy, sx + CANVAS_W, sy + 1, 0xAA_666666);
        graphics.fill(sx, sy + CANVAS_H - 1, sx + CANVAS_W, sy + CANVAS_H, 0xAA_666666);
        graphics.fill(sx, sy, sx + 1, sy + CANVAS_H, 0xAA_666666);
        graphics.fill(sx + CANVAS_W - 1, sy, sx + CANVAS_W, sy + CANVAS_H, 0xAA_666666);

        int stx = parent.bookLeft + left + STACK_X;
        int sty = parent.bookTop + top + STACK_Y;
        pose.pushPose();
        pose.translate(stx - 10, sty - 10, 0);
        graphics.enableScissor(stx, sty, stx + STACK_W, sty + STACK_H);
        spellcasting.render(graphics, screenMouseX, screenMouseY, pticks);
        graphics.disableScissor();
        pose.popPose();

        pose.popPose();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int mouseButton) {
        return false;
    }
}
