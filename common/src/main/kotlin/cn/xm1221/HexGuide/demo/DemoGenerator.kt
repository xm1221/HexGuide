package cn.xm1221.HexGuide.demo

import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.ListIota
import at.petrak.hexcasting.api.casting.iota.PatternIota
import at.petrak.hexcasting.api.casting.math.HexDir
import at.petrak.hexcasting.common.lib.hex.HexActions
import cn.xm1221.HexGuide.HexGuide
import cn.xm1221.HexGuide.api.notes.NoteIota
import cn.xm1221.HexGuide.compat.inline.IotaInlineCodec
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.architectury.platform.Platform
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ThreadLocalRandom

/**
 * 从 [List]&lt;[Iota]&gt; 生成演示动画配置文件（即 `data/&lt;ns&gt;/spellplays/&lt;name&gt;.json` 的内容）。
 *
 * 规则：
 * - [PatternIota] → 图案步骤，类型默认 `execute`（网格绘制 + 服务端执行；可传 [stepType] 覆盖，如 `push`）
 *   - 图案在注册表中有对应 action → **优先按 action 保存**（不写 pattern/start_dir/origin，方向用注册表，
 *     默认起点由演示页从 `data/hexguide/pattern_vector.json` 查该 action）
 *   - 无对应 action（自定义签名）→ 写 `pattern` 签名 + `start_dir`（无 origin）
 * - 其他 Iota（Double/Vec3/Null/List/Entity…）→ `push` 步骤，以内联 `iota:&lt;a85&gt;` 编码压入本地栈
 *
 * 生成的 JSON 自带缩进换行（Gson pretty printing），顶层字段与 `SPELLPLAYS.md` 一致：
 * `title` / `interval` / `clear_before` / `start_dir` / `steps[]`（不发明新格式）。
 */
object DemoGenerator {

    private val GSON = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    /** 文件名清洗后允许的最大长度（Windows 路径安全 + 便于阅读） */
    private const val MAX_NAME = 48

    /**
     * 生成演示配置 JSON 字符串（自带换行）。
     *
     * @param iotas 演示序列：每个 PatternIota 生成一个图案步骤，其余生成 push 步骤
     * @param stepType 图案步骤的类型，默认 `execute`
     * @param interval 全局每步间隔（tick）
     * @param clearBefore 每一步之前是否清空画布（网格，不清栈）
     * @param startDir 全局起始朝向（仅用于 JSON 顶层字段；每步用图案自身方向或 action 注册表方向）
     * @param title 全局大标题（未播放时显示）
     * @return 可直接写入 `data/&lt;ns&gt;/spellplays/&lt;name&gt;.json` 的 JSON 字符串
     */
    fun generate(
        iotas: List<Iota>,
        title: String = "",
        stepType: String = "execute",
        interval: Int = 30,
        clearBefore: Boolean = true,
        startDir: HexDir = HexDir.NORTH_EAST,
    ): String {
        val root = JsonObject()
        if (title.isNotEmpty()) root.addProperty("title", title)
        root.addProperty("interval", interval)
        root.addProperty("clear_before", clearBefore)
        root.addProperty("start_dir", startDir.name)

        val steps = JsonArray()
        for (iota in iotas) {
            val s = JsonObject()
            when (iota) {
                is PatternIota -> {
                    val pat = iota.pattern
                    s.addProperty("type", stepType) // 图案步骤，默认 execute
                    // 优先按 action 保存：图案在注册表有对应 action 时写 action（方向/默认起点由注册表与 pattern_vector 决定）
                    val actionId = HexActions.REGISTRY.entrySet()
                        .firstOrNull { it.value.prototype() == pat }
                        ?.key?.location()
                    if (actionId != null) {
                        s.addProperty("action", actionId.toString())
                    } else {
                        // 无注册 action（自定义签名）→ 写签名 + 方向
                        s.addProperty("pattern", pat.anglesSignature())
                        s.addProperty("start_dir", pat.startDir.name)
                    }
                    // 不显式添加 origin：演示页用 pattern_vector（action）或默认 [-1, 2]
                }
                else -> {
                    // 非图案 → push 步骤，内联 iota:<a85> 编码
                    s.addProperty("type", "push")
                    s.addProperty("push", IotaInlineCodec.toPrefixed(iota))
                }
            }
            steps.add(s)
        }
        root.add("steps", steps)
        return GSON.toJson(root)
    }

    // ─── 笔记正文里的图案提取（务实版，不追求完整 Markdown 解析） ────────

    /** 正文里的 `iota:<a85>` 或 `iota:<ns:path.json>` 引用（后缀清洗复用 IotaInlineCodec） */
    private val INLINE_REF = Regex("""iota:([!-~]+)""")
    private val PATTERN_LITERAL = Regex("""pattern[{(]([^)}]*)[)}]""")

    /**
     * 服务端可用的引用解析：`.json` 走服务端资源管理器（数据包）或游戏目录，
     * Ascii85 走纯本地解码。**不要**用 [IotaInlineCodec.resolve]——它会访问客户端
     * `Minecraft.getInstance()`，在专用服务器上会崩。
     */
    private fun resolveServerSafe(raw: String, server: net.minecraft.server.MinecraftServer?): Iota? {
        val core = IotaInlineCodec.splitSuffixes(raw).core
        if (core.endsWith(".json")) {
            return IotaInlineCodec.loadFromServer(server, core)
        }
        // 正文里 a85 后面常紧跟标点（也属于 a85 字符集），最多回退 4 个字符再试
        var candidate = core
        repeat(5) {
            val iota = IotaInlineCodec.decodeA85ToIota(candidate)
            if (iota != null) return iota
            if (candidate.isEmpty()) return null
            candidate = candidate.substring(0, candidate.length - 1)
        }
        return null
    }

    /**
     * 从一整节的 NoteIota 里尽量抽取可演示的 iota 序列（用于 `/hexguide demoexport <索引>`）。
     *
     * 识别顺序（能解多少解多少，解不出的直接跳过）：
     * 1. 正文中的 `iota:<a85>` / `iota:<ns:path.json>` 引用 → 解析成 ListIota 时展开为多步，否则单步
     * 2. 正文中的 `pattern{<dir>,<sig>}` / `pattern[<dir>,<sig>]` 字面量 → PatternIota 步骤
     *
     * 注意：本方法在**服务端**执行（指令），因此绝不走 `Minecraft.getInstance()` 那条客户端资源路径，
     * 见 [resolveServerSafe]。解不出的引用计入 [ExtractResult.skippedRefs] 由指令提示玩家。
     *
     * @return 抽取到的 iota 序列与跳过的引用数
     */
    fun extractFromNotes(notes: List<NoteIota>, server: net.minecraft.server.MinecraftServer? = null): ExtractResult {
        val out = ArrayList<Iota>()
        var skipped = 0
        for (note in notes) {
            val text = (note.title + "\n" + note.body)
            for (m in INLINE_REF.findAll(text)) {
                val iota = resolveServerSafe(m.groupValues[1], server)
                if (iota == null) { skipped++; continue }
                if (iota is ListIota) iota.list.forEach { out.add(it) } else out.add(iota)
            }
            for (m in PATTERN_LITERAL.findAll(text)) {
                val parts = m.groupValues[1].split(',')
                if (parts.size < 2) { skipped++; continue }
                val pat = try {
                    val dir = HexDir.valueOf(parts[0].trim().uppercase())
                    at.petrak.hexcasting.api.casting.math.HexPattern.fromAngles(parts[1].trim(), dir)
                } catch (e: Exception) { null }
                if (pat == null) skipped++ else out.add(PatternIota(pat))
            }
        }
        return ExtractResult(out, skipped)
    }

    data class ExtractResult(val iotas: List<Iota>, val skippedRefs: Int)

    // ─── 保存 ────────────────────────────────────────────────

    /**
     * 生成演示配置并保存为文件：`&lt;游戏目录&gt;/&lt;ns&gt;/spellplays/&lt;name&gt;.json`。
     *
     * 保存位置与 [IotaInlineCodec.saveToGameDir] 一致（游戏目录，不写入 mods jar 数据包），
     * 生成的文件可手动放入数据包 `data/&lt;ns&gt;/spellplays/` 供手册演示页使用。
     *
     * @param iotas 演示序列
     * @param name 文件名（不含 .json；会自动清洗，空则用 demo_&lt;时间戳&gt;）
     * @param ns 命名空间，默认 hexguide
     * @return 页面引用字符串 `ns:name`（供 `"demo": "ns:name"` 使用）；失败返回 null
     */
    fun save(
        iotas: List<Iota>,
        name: String,
        ns: String = HexGuide.MODID,
        title: String = "",
        stepType: String = "execute",
        interval: Int = 30,
        clearBefore: Boolean = true,
        startDir: HexDir = HexDir.NORTH_EAST,
    ): String? {
        return try {
            val json = generate(iotas, title, stepType, interval, clearBefore, startDir)
            val file = spellplaysDir(ns).resolve("${sanitizeName(name)}.json")
            Files.writeString(file, json)
            HexGuide.LOGGER.info("[DemoGenerator] 演示配置已保存: {}", file)
            "$ns:${file.fileName.toString().removeSuffix(".json")}"
        } catch (e: Exception) {
            HexGuide.LOGGER.error("[DemoGenerator] 保存演示配置失败", e)
            null
        }
    }

    /** `<gameDir>/<ns>/spellplays`（不存在则创建） */
    fun spellplaysDir(ns: String = HexGuide.MODID): Path {
        val dir = Platform.getGameFolder().resolve(ns).resolve("spellplays")
        Files.createDirectories(dir)
        return dir
    }

    /**
     * 把任意标题清洗成安全的文件名（去掉 Windows 非法字符，限制长度）。
     * 空串 → `demo_<时间戳>`，供调用方直接当 name 用。
     */
    fun sanitizeName(raw: String): String {
        val cleaned = raw
            .replace(Regex("""[\\/:*?"<>|\s]+"""), "_")
            .trim('_', '.')
            .take(MAX_NAME)
        return cleaned.ifEmpty { "demo_${System.currentTimeMillis()}" }
    }

    /** 生成一个不易撞车的默认名（demo_时间戳_随机） */
    fun defaultName(): String =
        "demo_${System.currentTimeMillis()}_${ThreadLocalRandom.current().nextInt(1000, 9999)}"
}
