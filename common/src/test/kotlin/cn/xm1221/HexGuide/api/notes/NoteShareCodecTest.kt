package cn.xm1221.HexGuide.api.notes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertIs

/**
 * [NoteShareCodec] 的纯 JVM 单元测试（不加载 Minecraft）。
 *
 * 覆盖：正常往返（含中文/换行/引号）、前缀错误、超长串拒绝、压缩炸弹上限、
 * 页数/标题/正文上限，以及 Ascii85 的边界（零字节组、尾部不满 4 字节）。
 */
class NoteShareCodecTest {

    private fun samplePages() = listOf(
        NoteShareCodec.Page(
            title = "二维向量与图案",
            body = "先用 $(br) 换行。\n第二行有 \"引号\" 和 \\ 反斜杠。\n\n空行也要保留。",
            author = "xm1221",
            id = "3f2504e0-4f89-11d3-9a0c-0305e82c3301",
            time = 1_700_000_000_000L,
        ),
        NoteShareCodec.Page(
            title = "Second page",
            body = "English body with unicode: 咒法 — ✅ — \u0000-ish chars are not expected here.",
            author = "someone",
            id = "abc",
            time = 0L,
        ),
    )

    @Test
    fun `round trip keeps every field`() {
        val pages = samplePages()
        val share = assertNotNull(NoteShareCodec.encode(pages), "编码不应失败")
        assertTrue(share.startsWith(NoteShareCodec.PREFIX), "分享串应带 HG1: 前缀")

        val decoded = NoteShareCodec.decode(share)
        assertIs<NoteShareCodec.Result.Ok>(decoded)
        assertEquals(pages, decoded.pages)
    }

    @Test
    fun `share string is compact for repetitive text`() {
        val body = "同样的正文重复很多次。".repeat(60) // 600 字符
        val share = assertNotNull(NoteShareCodec.encode(listOf(NoteShareCodec.Page(title = "t", body = body))))
        assertTrue(share.length < body.length, "DEFLATE 后应明显短于原文，实际 ${share.length} vs ${body.length}")
    }

    @Test
    fun `rejects bad prefix`() {
        assertEquals(
            NoteShareCodec.Result.Err(NoteShareCodec.Reason.BAD_PREFIX),
            NoteShareCodec.decode("HG2:abcdef"),
        )
        assertEquals(
            NoteShareCodec.Result.Err(NoteShareCodec.Reason.BAD_PREFIX),
            NoteShareCodec.decode("随便一段文本"),
        )
    }

    @Test
    fun `rejects over-long string before decoding`() {
        val tooLong = NoteShareCodec.PREFIX + "a".repeat(NoteShareCodec.MAX_SHARE_CHARS)
        assertEquals(
            NoteShareCodec.Result.Err(NoteShareCodec.Reason.TOO_LONG),
            NoteShareCodec.decode(tooLong),
        )
    }

    @Test
    fun `rejects corrupt payload`() {
        assertEquals(
            NoteShareCodec.Result.Err(NoteShareCodec.Reason.CORRUPT),
            NoteShareCodec.decode(NoteShareCodec.PREFIX + "!!!!!!!"),
        )
        // 合法 Ascii85 字母表、但不是 DEFLATE 流
        assertEquals(
            NoteShareCodec.Result.Err(NoteShareCodec.Reason.CORRUPT),
            NoteShareCodec.decode(NoteShareCodec.PREFIX + "zzzzzzzz"),
        )
    }

    @Test
    fun `rejects empty payload`() {
        assertEquals(
            NoteShareCodec.Result.Err(NoteShareCodec.Reason.EMPTY),
            NoteShareCodec.decode(NoteShareCodec.PREFIX),
        )
    }

    @Test
    fun `encode refuses over-long title or body`() {
        assertNull(
            NoteShareCodec.encode(listOf(NoteShareCodec.Page(title = "x".repeat(NoteShareCodec.MAX_TITLE_CHARS + 1)))),
            "超长标题应编码失败",
        )
        assertNull(
            NoteShareCodec.encode(listOf(NoteShareCodec.Page(body = "x".repeat(NoteShareCodec.MAX_BODY_CHARS + 1)))),
            "超长正文应编码失败",
        )
        assertNull(NoteShareCodec.encode(emptyList()), "空节应编码失败")
    }

    @Test
    fun `encode refuses too many pages`() {
        val pages = List(NoteShareCodec.MAX_PAGES + 1) { NoteShareCodec.Page(title = "p$it") }
        assertNull(NoteShareCodec.encode(pages))
    }

    @Test
    fun `rejects inflated payload over the bomb limit`() {
        // 直接构造一个解压后超过 MAX_INFLATED_BYTES 的合法串：大量同字节 → 压缩率极高
        val huge = "{\"version\":1,\"pages\":[],\"pad\":\"".toByteArray() +
            ByteArray(NoteShareCodec.MAX_INFLATED_BYTES * 4) { 'a'.code.toByte() } +
            "\"}".toByteArray()
        val share = NoteShareCodec.PREFIX + NoteShareCodec.encodeA85(deflateForTest(huge))
        val result = NoteShareCodec.decode(share)
        assertEquals(NoteShareCodec.Result.Err(NoteShareCodec.Reason.CORRUPT), result)
    }

    @Test
    fun `ascii85 round trip handles zero groups and tail`() {
        val cases = listOf(
            ByteArray(0),
            byteArrayOf(0, 0, 0, 0),
            byteArrayOf(1),
            byteArrayOf(1, 2),
            byteArrayOf(1, 2, 3),
            byteArrayOf(1, 2, 3, 4),
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0),
            ByteArray(37) { (it * 7 + 3).toByte() },
        )
        for (bytes in cases) {
            val text = NoteShareCodec.encodeA85(bytes)
            assertTrue(text.all { it == 'z' || it in '!'..'u' }, "只应出现 a85 字母表字符")
            assertTrue(NoteShareCodec.decodeA85(text).contentEquals(bytes), "a85 往返失败: ${bytes.toList()}")
        }
    }

    // 测试内自带一个最小 DEFLATE，避免依赖 codec 的私有实现
    private fun deflateForTest(data: ByteArray): ByteArray {
        val d = java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION)
        d.setInput(data); d.finish()
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }
}
