package cn.xm1221.HexGuide.demo

import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.PatternIota
import at.petrak.hexcasting.api.casting.math.HexDir
import at.petrak.hexcasting.common.lib.hex.HexActions
import cn.xm1221.HexGuide.HexGuide
import cn.xm1221.HexGuide.compat.inline.IotaInlineCodec
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.architectury.platform.Platform
import java.nio.file.Files

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
 * 生成的 JSON 自带缩进换行（Gson pretty printing）。
 */
object DemoGenerator {

    private val GSON = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

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
                    val pat = iota.getPattern()
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

    /**
     * 生成演示配置并保存为文件：`&lt;游戏目录&gt;/&lt;ns&gt;/spellplays/&lt;name&gt;.json`。
     *
     * 保存位置与 [IotaInlineCodec.saveToGameDir] 一致（游戏目录，不写入 mods jar 数据包），
     * 生成的文件可手动放入数据包 `data/&lt;ns&gt;/spellplays/` 供手册演示页使用。
     *
     * @param iotas 演示序列
     * @param name 文件名（不含 .json）
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
            val dir = Platform.getGameFolder().resolve(ns).resolve("spellplays")
            Files.createDirectories(dir)
            Files.writeString(dir.resolve("$name.json"), json)
            HexGuide.LOGGER.info("[DemoGenerator] 演示配置已保存: {}", dir.resolve("$name.json"))
            "$ns:$name"
        } catch (e: Exception) {
            HexGuide.LOGGER.error("[DemoGenerator] 保存演示配置失败", e)
            null
        }
    }
}
