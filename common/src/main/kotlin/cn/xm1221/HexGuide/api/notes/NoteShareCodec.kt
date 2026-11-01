package cn.xm1221.HexGuide.api.notes

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * 笔记节的「紧凑分享串」编解码（F7）。
 *
 * 格式：`HG1:` + Ascii85( DEFLATE( JSON ) )，JSON 与 `/hexguide export` 写出的文件**同一套 schema**：
 * `{"version":1,"pages":[{"title","body","author","id","time"}, ...]}`
 *
 * 设计约束：
 * - **不引用任何 Minecraft 类**（纯 JVM），因此可以在 `common/src/test` 里直接做单元测试；
 * - 解码有**硬上限**：串长、解压字节数、页数、标题/正文字符数，全部在解压/解析之前或之中检查，
 *   防止「压缩炸弹」与超大文本撑爆客户端/服务端；
 * - 与 [cn.xm1221.HexGuide.compat.inline.IotaInlineCodec] 使用同一套 Ascii85 字母表
 *   （`!`..`u` 与 `z` 表示 4 个零字节），但实现独立，避免把 Minecraft 依赖带进单元测试。
 */
object NoteShareCodec {

    /** 分享串前缀（版本号交给 JSON 的 `version` 字段，前缀只用来快速识别） */
    const val PREFIX = "HG1:"

    /** 分享串硬上限（字符）。超过直接拒绝，不做截断。 */
    const val MAX_SHARE_CHARS = 8192

    /** 解压后的 JSON 字节上限（防止压缩炸弹）。 */
    const val MAX_INFLATED_BYTES = 128 * 1024

    /** 单节页数上限。 */
    const val MAX_PAGES = 64

    /** 标题 / 正文长度上限（与服务端 SecurityGuards 的费用一致）。 */
    const val MAX_TITLE_CHARS = 64
    const val MAX_BODY_CHARS = 2048

    /** 分享用的一页笔记（纯数据，不依赖 NoteIota） */
    data class Page(
        val title: String = "",
        val body: String = "",
        val author: String = "",
        val id: String = "",
        val time: Long = 0L,
    )

    /** 解码失败的原因（对应 lang 键 `hexguide.cmd.share.error.<name.lowercase>`） */
    enum class Reason { BAD_PREFIX, TOO_LONG, CORRUPT, EMPTY, TOO_MANY_PAGES, TITLE_TOO_LONG, BODY_TOO_LONG }

    sealed interface Result {
        data class Ok(val pages: List<Page>) : Result
        data class Err(val reason: Reason) : Result
    }

    private val GSON = GsonBuilder().disableHtmlEscaping().create()

    // ─── 编码 ────────────────────────────────────────────────

    /**
     * 编码一节笔记。
     * @return 分享串；超过 [MAX_SHARE_CHARS] 或页数/长度不合规时返回 null（由调用方提示玩家）
     */
    fun encode(pages: List<Page>): String? {
        if (pages.isEmpty() || pages.size > MAX_PAGES) return null
        if (pages.any { it.title.length > MAX_TITLE_CHARS || it.body.length > MAX_BODY_CHARS }) return null

        val root = JsonObject()
        root.addProperty("version", 1)
        val arr = JsonArray()
        for (p in pages) {
            val o = JsonObject()
            o.addProperty("title", p.title)
            o.addProperty("body", p.body)
            o.addProperty("author", p.author)
            o.addProperty("id", p.id)
            o.addProperty("time", p.time)
            arr.add(o)
        }
        root.add("pages", arr)

        val json = GSON.toJson(root).toByteArray(Charsets.UTF_8)
        val packed = deflate(json) ?: return null
        val encoded = PREFIX + encodeA85(packed)
        return if (encoded.length <= MAX_SHARE_CHARS) encoded else null
    }

    // ─── 解码 ────────────────────────────────────────────────

    /**
     * 解码分享串。任何不合规输入都返回 [Result.Err]，**不抛异常**。
     * 服务端在写入笔记库前必须调用本方法（客户端不可直接写）。
     */
    fun decode(raw: String): Result {
        val text = raw.trim()
        if (!text.startsWith(PREFIX)) return Result.Err(Reason.BAD_PREFIX)
        if (text.length > MAX_SHARE_CHARS) return Result.Err(Reason.TOO_LONG)

        val body = text.substring(PREFIX.length)
        if (body.isEmpty()) return Result.Err(Reason.EMPTY)

        val packed = try {
            decodeA85(body)
        } catch (e: Exception) {
            return Result.Err(Reason.CORRUPT)
        }
        val json = inflate(packed, MAX_INFLATED_BYTES) ?: return Result.Err(Reason.CORRUPT)

        val root = try {
            JsonParser.parseString(json.toString(Charsets.UTF_8)).asJsonObject
        } catch (e: Exception) {
            return Result.Err(Reason.CORRUPT)
        }
        if (!root.has("version") || root.get("version").asInt != 1) return Result.Err(Reason.CORRUPT)
        val arr = root.getAsJsonArray("pages") ?: return Result.Err(Reason.CORRUPT)
        if (arr.size() > MAX_PAGES) return Result.Err(Reason.TOO_MANY_PAGES)
        if (arr.isEmpty) return Result.Err(Reason.EMPTY)

        val pages = ArrayList<Page>(arr.size())
        for (el in arr) {
            val o = try { el.asJsonObject } catch (e: Exception) { return Result.Err(Reason.CORRUPT) }
            val page = Page(
                title = o.stringOrEmpty("title"),
                body = o.stringOrEmpty("body"),
                author = o.stringOrEmpty("author"),
                id = o.stringOrEmpty("id"),
                time = try { if (o.has("time")) o.get("time").asLong else 0L } catch (e: Exception) { 0L },
            )
            if (page.title.length > MAX_TITLE_CHARS) return Result.Err(Reason.TITLE_TOO_LONG)
            if (page.body.length > MAX_BODY_CHARS) return Result.Err(Reason.BODY_TOO_LONG)
            pages.add(page)
        }
        return Result.Ok(pages)
    }

    private fun JsonObject.stringOrEmpty(key: String): String =
        try { if (has(key)) get(key).asString else "" } catch (e: Exception) { "" }

    // ─── DEFLATE ─────────────────────────────────────────────

    private fun deflate(data: ByteArray): ByteArray? = try {
        val d = Deflater(Deflater.BEST_COMPRESSION)
        d.setInput(data)
        d.finish()
        val out = ByteArrayOutputStream(data.size / 2 + 32)
        val buf = ByteArray(4096)
        while (!d.finished()) {
            val n = d.deflate(buf)
            if (n == 0 && d.needsInput()) break
            out.write(buf, 0, n)
        }
        d.end()
        out.toByteArray()
    } catch (e: Exception) {
        null
    }

    /** 解压并限制输出字节数；超限返回 null */
    private fun inflate(data: ByteArray, limit: Int): ByteArray? {
        val inf = Inflater()
        inf.setInput(data)
        val out = ByteArrayOutputStream(data.size * 2 + 64)
        val buf = ByteArray(4096)
        return try {
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0) {
                    if (inf.needsInput() || inf.needsDictionary()) return null
                }
                out.write(buf, 0, n)
                if (out.size() > limit) return null
            }
            out.toByteArray()
        } catch (e: DataFormatException) {
            null
        } catch (e: Exception) {
            null
        } finally {
            inf.end()
        }
    }

    // ─── Ascii85（字母表同 IotaInlineCodec）───────────────────

    private const val A85_BASE = 33

    internal fun encodeA85(d: ByteArray): String {
        val sb = StringBuilder(d.size + d.size / 4 + 4)
        var i = 0
        while (i + 4 <= d.size) {
            var v = ((d[i++].toLong() and 0xFF) shl 24) or
                ((d[i++].toLong() and 0xFF) shl 16) or
                ((d[i++].toLong() and 0xFF) shl 8) or
                (d[i++].toLong() and 0xFF)
            if (v == 0L) { sb.append('z'); continue }
            sb.append((A85_BASE + v / 52200625).toInt().toChar()); v %= 52200625
            sb.append((A85_BASE + v / 614125).toInt().toChar()); v %= 614125
            sb.append((A85_BASE + v / 7225).toInt().toChar()); v %= 7225
            sb.append((A85_BASE + v / 85).toInt().toChar()); v %= 85
            sb.append((A85_BASE + v).toInt().toChar())
        }
        if (i < d.size) {
            var v = 0L
            val rem = d.size - i
            for (j in i until d.size) v = (v shl 8) or (d[j].toLong() and 0xFF)
            v = v shl ((4 - rem) * 8)
            for (j in 0..rem) {
                sb.append((A85_BASE + v / 52200625).toInt().toChar())
                v = (v % 52200625) * 85
            }
        }
        return sb.toString()
    }

    internal fun decodeA85(s: String): ByteArray {
        val out = ByteArrayOutputStream(s.length * 4 / 5 + 8)
        val g = IntArray(5)
        var gi = 0
        for (c in s) {
            if (c == 'z') {
                if (gi != 0) throw IllegalArgumentException("z inside group")
                out.write(ByteArray(4))
                continue
            }
            val digit = c.code - A85_BASE
            if (digit < 0 || digit > 84) throw IllegalArgumentException("bad ascii85 char")
            g[gi++] = digit
            if (gi == 5) {
                var v = 0L
                for (k in 0 until 5) v = v * 85 + g[k]
                out.write((v shr 24).toInt()); out.write((v shr 16).toInt())
                out.write((v shr 8).toInt()); out.write(v.toInt())
                gi = 0
            }
        }
        if (gi > 0) {
            for (j in gi until 5) g[j] = 84
            var v = 0L
            for (k in 0 until 5) v = v * 85 + g[k]
            for (j in 0 until gi - 1) out.write((v shr (24 - 8 * j)).toInt() and 0xFF)
        }
        return out.toByteArray()
    }
}
