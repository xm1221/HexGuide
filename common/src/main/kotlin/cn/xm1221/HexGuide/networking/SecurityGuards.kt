package cn.xm1221.HexGuide.networking

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceLocation
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * C2S 包的务实加固（计划 §2.5 的「务实子集」）。
 *
 * 只做**低成本、无会话状态**的校验：每玩家速率限制、iota 类型白名单、NBT/字符串长度上限。
 * 不引入「该玩家当前打开了哪个演示页」这类会话状态（需要额外协议往返），
 * 因此它只是**部分缓解**：能挡住重放/刷包/离谱 NBT/带副作用的 iota 类型，
 * 但挡不住一个自己构造合法 NBT 的客户端执行任意已注册图案。见 README「安全说明」。
 */
object SecurityGuards {

    /** 分享串（笔记剪贴板分享）的硬长度上限，与 [cn.xm1221.HexGuide.api.notes.NoteShareCodec] 保持一致。 */
    const val MAX_SHARE_CHARS = 8192

    /** 分享串每分钟最多尝试解码几次（含失败尝试，防止拿畸形串刷服务端）。 */
    const val SHARE_PER_MINUTE = 10

    // ---------------- 速率限制 ----------------

    private class Window(start: Long, var count: Int) {
        val start: Long = start
    }

    /** key = "<玩家UUID>|<桶名>"，value = 固定窗口计数。 */
    private val windows = ConcurrentHashMap<String, Window>()

    /** 超过上限时返回 true（调用方应直接丢弃该包）。 */
    fun rateLimited(
        player: UUID,
        bucket: String,
        maxPerWindow: Int,
        windowMillis: Long = 1000L,
    ): Boolean {
        val key = "$player|$bucket"
        val now = System.currentTimeMillis()
        val w = windows.compute(key) { _, old ->
            when {
                old == null || now - old.start >= windowMillis -> Window(now, 1)
                else -> old.apply { count++ }
            }
        } ?: return true
        return w.count > maxPerWindow
    }

    /** 玩家下线时清理，避免 UUID 键无限累积。 */
    fun forget(player: UUID) {
        val prefix = "$player|"
        windows.keys.removeIf { it.startsWith(prefix) }
    }

    // ---------------- 长度 / 结构上限 ----------------

    /** 单个图案签名（角度字符串）上限；正常签名远小于此。 */
    const val MAX_PATTERN_SIGNATURE = 256

    /** 单次同步允许的图案数量上限。 */
    const val MAX_SYNC_PATTERNS = 64

    /** 单页笔记文本上限（字符数，与编辑器侧限制一致）。 */
    const val MAX_NOTE_PAGE_CHARS = 2048

    /** 单次保存允许的页数上限。 */
    const val MAX_NOTE_PAGES = 64

    /** 笔记标题上限。 */
    const val MAX_NOTE_TITLE_CHARS = 64

    /** 单次 iota 的 NBT 体积近似上限。 */
    const val MAX_IOTA_NBT_BYTES = 64 * 1024

    /** 整份演示栈 NBT 的体积近似上限。 */
    const val MAX_IMAGE_NBT_BYTES = 256 * 1024

    /**
     * NBT 体积的廉价近似：用 SNBT 字符串长度代替真实字节数。
     * 目的只是拒绝明显离谱的包，不做精确计量。
     */
    fun isReasonableNbt(tag: CompoundTag?, maxBytes: Int): Boolean =
        tag != null && tag.toString().length <= maxBytes

    /**
     * 笔记 iota（NoteIota payload）的结构上限：标题/正文长度 + 整体体积。
     * 笔记库是服务端长期存档，不设上限会被单个客户端撑大。
     */
    fun isReasonableNoteTag(tag: CompoundTag?): Boolean {
        if (!isReasonableNbt(tag, MAX_IOTA_NBT_BYTES)) return false
        val title = tag!!.getString("title")
        val body = tag.getString("body")
        return title.length <= MAX_NOTE_TITLE_CHARS && body.length <= MAX_NOTE_PAGE_CHARS
    }

    /**
     * 笔记库的总量上限（每玩家）：防止用合法的小包慢慢把服务端存档撑爆。
     * 到限后新写入被丢弃；读取不受影响。
     */
    const val MAX_NOTE_SECTIONS = 256
    const val MAX_SECTION_IOTAS = 64

    // ---------------- 图案签名 ----------------

    /**
     * 图案签名只允许 HexPattern 的角度字符（a/q/w/e/d/s）。
     * 长度与字符集都合法时再交给 [at.petrak.hexcasting.api.casting.math.HexPattern.fromAngles] 解析。
     */
    fun isValidSignatureChars(sig: String): Boolean =
        sig.isNotEmpty() && sig.length <= MAX_PATTERN_SIGNATURE && sig.all { it in "aqweds" }

    // ---------------- iota 类型白名单 ----------------

    private fun hexId(name: String) = ResourceLocation("hexcasting", name)

    /**
     * 允许客户端提交给服务端演示 VM 的 iota 类型：
     * null / 数字 / 布尔 / 向量 / 垃圾 / 图案 / 列表。
     *
     * 明确**排除**：
     * - `entity`：可指向任意实体，演示不需要；
     * - `continuation`：可伪造执行帧；
     * - 所有附属模组注册的自定义类型（可能带副作用，且本项目无法为其背书）。
     */
    private val ALLOWED_TYPES: Set<ResourceLocation> = setOf(
        hexId("null"),
        hexId("double"),
        hexId("boolean"),
        hexId("vec3"),
        hexId("garbage"),
        hexId("pattern"),
        hexId("list"),
    )

    /** 列表 iota 的递归深度上限。 */
    private const val MAX_LIST_DEPTH = 8

    /** 列表 iota 的元素个数上限。 */
    private const val MAX_LIST_ELEMENTS = 256

    /** 与 Hex Casting 的 [at.petrak.hexcasting.common.lib.hex.HexIotaTypes] 中两个 key 保持一致。 */
    private const val KEY_TYPE = "hexcasting:type"
    private const val KEY_DATA = "hexcasting:data"

    /**
     * 递归检查序列化 iota NBT 是否在白名单内且结构可控。
     * 客户端提交的 iota 一律先过这一关，再交给 IotaType.deserialize。
     */
    fun isAllowedIotaTag(tag: Tag?, depth: Int = 0): Boolean {
        if (tag !is CompoundTag || depth > MAX_LIST_DEPTH) return false
        val typeId = tag.getString(KEY_TYPE)
        if (!ResourceLocation.isValidResourceLocation(typeId)) return false
        val id = ResourceLocation(typeId)
        if (id !in ALLOWED_TYPES) return false

        val data = tag.get(KEY_DATA) ?: return false
        if (id == hexId("list")) {
            if (data !is ListTag || data.size > MAX_LIST_ELEMENTS) return false
            for (i in 0 until data.size) {
                if (!isAllowedIotaTag(data.get(i), depth + 1)) return false
            }
        }
        return true
    }

    /**
     * 检查客户端上传的 CastingImage NBT 里的栈与括号内容是否都在 iota 白名单内。
     * 客户端可以「自己造一个栈」上传，若里面有 entity/continuation 就能伪造演示外的东西，
     * 所以这里按 CastingImage 的 NBT 布局逐项校验（key 见 Hex Casting 的 CastingImage）。
     */
    fun isReasonableCastImage(tag: CompoundTag?, maxBytes: Int): Boolean {
        if (!isReasonableNbt(tag, maxBytes)) return false
        val stack = tag!!.getList(KEY_STACK, Tag.TAG_COMPOUND.toInt())
        if (stack.size > MAX_SYNC_PATTERNS) return false
        for (i in 0 until stack.size) {
            if (!isAllowedIotaTag(stack.getCompound(i))) return false
        }
        // 括号收集中的 iota：ParenthesizedIota.iotas 本身是「一个 ListIota 的序列化」，
        // 不是 iota 列表，所以整体过一次白名单（list 会递归检查每个元素）。
        // 缺省（客户端没写该键）视为空括号，允许通过。
        val parenIotas = tag.getCompound(KEY_PARENTHESIZED).get(KEY_IOTAS)
        if (parenIotas != null && !isAllowedIotaTag(parenIotas)) return false
        return true
    }

    /** 与 Hex Casting 的 CastingImage NBT key 保持一致。 */
    private const val KEY_STACK = "stack"
    private const val KEY_PARENTHESIZED = "parenthesized"
    private const val KEY_IOTAS = "iotas"
}
