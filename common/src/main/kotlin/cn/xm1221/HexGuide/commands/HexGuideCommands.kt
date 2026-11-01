package cn.xm1221.HexGuide.commands

import cn.xm1221.HexGuide.api.notes.NoteIota
import cn.xm1221.HexGuide.api.notes.NoteShareCodec
import cn.xm1221.HexGuide.api.notes.PlayerNotes
import cn.xm1221.HexGuide.demo.DemoGenerator
import cn.xm1221.HexGuide.networking.SecurityGuards
import cn.xm1221.HexGuide.networking.handler.syncNotes
import cn.xm1221.HexGuide.networking.msg.MsgNoteShareS2C
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import dev.architectury.event.events.common.CommandRegistrationEvent
import dev.architectury.platform.Platform
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import java.nio.file.Files
import java.util.UUID

/**
 * /hexguide 指令：
 * - /hexguide export <索引>          把执行者第 <索引> 节笔记导出为 JSON（<gameDir>/hexguide/notes/export_<索引>.json）
 * - /hexguide export <索引> clipboard 同内容编码成 `HG1:` 紧凑串并复制到剪贴板（跨服/无文件权限时分享）
 * - /hexguide import <文件名>        从 <gameDir>/hexguide/notes/<文件名>.json 导入一节笔记
 * - /hexguide import clipboard <串>  校验并导入一段 `HG1:` 分享串
 * - /hexguide demoexport <索引>      把第 <索引> 节笔记里能识别的图案/iota 导出为 spellplay 演示草稿
 * - /hexguide authority <玩家> <true|false>   单独配置某玩家的 export/import 权限（OP 2 级；默认开）
 *
 * 所有写操作都走**服务端**校验（权限 → 结构上限 → 速率），客户端不会也不可能直接写笔记库。
 */
object HexGuideCommands {

    private val notesDir get() = Platform.getGameFolder().resolve("hexguide").resolve("notes")

    fun register() {
        CommandRegistrationEvent.EVENT.register { dispatcher: CommandDispatcher<CommandSourceStack>, _, _ ->
            dispatcher.register(
                Commands.literal("hexguide")
                    // export <index> [clipboard]
                    .then(Commands.literal("export")
                        .requires { it.player != null }
                        .then(Commands.argument("index", IntegerArgumentType.integer(0))
                            .executes { ctx -> exportNote(ctx, clipboard = false) }
                            .then(Commands.literal("clipboard")
                                .executes { ctx -> exportNote(ctx, clipboard = true) })))
                    // import <文件名> | import clipboard <串>
                    .then(Commands.literal("import")
                        .requires { it.player != null }
                        .then(Commands.literal("clipboard")
                            .then(Commands.argument("share", StringArgumentType.greedyString())
                                .executes { ctx -> importShare(ctx) }))
                        .then(Commands.argument("file", StringArgumentType.greedyString())
                            .suggests { _, builder ->
                                try {
                                    val dir = notesDir
                                    if (Files.isDirectory(dir)) {
                                        Files.list(dir).use { stream ->
                                            stream.filter { it.fileName.toString().endsWith(".json") }
                                                .forEach { builder.suggest(it.fileName.toString()) }
                                        }
                                    }
                                } catch (e: Exception) {}
                                builder.buildFuture()
                            }
                            .executes { ctx -> importNote(ctx) }))
                    // demoexport <index>
                    .then(Commands.literal("demoexport")
                        .requires { it.player != null }
                        .then(Commands.argument("index", IntegerArgumentType.integer(0))
                            .executes { ctx -> demoExport(ctx) }))
                    // authority（OP 2）
                    .then(Commands.literal("authority")
                        .requires { it.hasPermission(2) }
                        .then(Commands.argument("player", EntityArgument.player())
                            .then(Commands.argument("allowed", BoolArgumentType.bool())
                                .executes { ctx -> setAuthority(ctx) })))
            )
        }
    }

    /** /hexguide export <索引> [clipboard]：导出第 <索引> 节为 JSON 文件，或编码成分享串复制到剪贴板 */
    private fun exportNote(ctx: CommandContext<CommandSourceStack>, clipboard: Boolean): Int {
        val player = ctx.source.player ?: return 0
        val index = IntegerArgumentType.getInteger(ctx, "index")
        val notes = PlayerNotes.get(player.serverLevel())
        if (!notes.isAllowed(player.uuid)) {
            ctx.source.sendFailure(Component.translatable("hexguide.cmd.no_authority"))
            return 0
        }
        val secs = notes.sections(player.uuid)
        if (index !in secs.indices) {
            ctx.source.sendFailure(Component.translatable("hexguide.cmd.bad_index", secs.size, (secs.size - 1).coerceAtLeast(0)))
            return 0
        }
        val sec = secs[index]

        if (clipboard) return exportShare(ctx, player, sec)

        return try {
            Files.createDirectories(notesDir)
            // 文件名 = 该节第一个 title（清洗非法字符）；无标题则回退 export_<索引>.json
            val firstTitle = sec.firstOrNull()?.title ?: ""
            val safe = sanitize(firstTitle)
            val fileName = if (safe.isEmpty()) "export_$index.json" else "$safe.json"
            val file = notesDir.resolve(fileName)
            Files.writeString(file, buildJson(sec))
            ctx.source.sendSuccess({ Component.translatable("hexguide.cmd.exported", index, sec.size, fileName) }, false)
            1
        } catch (e: Exception) {
            ctx.source.sendFailure(Component.translatable("hexguide.cmd.export_failed", e.message ?: ""))
            0
        }
    }

    /**
     * 分享串路径：服务端编码 → S2C 让客户端写剪贴板 → 聊天栏再给一条可点击复制的气泡。
     * 服务端无法直接写剪贴板（Minecraft 只允许客户端点击触发 COPY_TO_CLIPBOARD），见 [MsgNoteShareS2C]。
     */
    private fun exportShare(ctx: CommandContext<CommandSourceStack>, player: ServerPlayer, sec: List<NoteIota>): Int {
        if (SecurityGuards.rateLimited(player.uuid, "note_share_export", maxPerWindow = 6, windowMillis = 60_000L)) {
            ctx.source.sendFailure(Component.translatable("hexguide.cmd.share.rate_limited"))
            return 0
        }
        val pages = sec.map {
            NoteShareCodec.Page(it.title, it.body, it.author, it.id, it.time)
        }
        val share = NoteShareCodec.encode(pages)
        if (share == null) {
            ctx.source.sendFailure(Component.translatable("hexguide.cmd.share.too_large", NoteShareCodec.MAX_SHARE_CHARS))
            return 0
        }
        MsgNoteShareS2C(share, "hexguide.cmd.share.copied").sendToPlayer(player)
        ctx.source.sendSuccess({ Component.translatable("hexguide.cmd.share.exported", pages.size, share.length) }, false)
        return 1
    }

    /** /hexguide import clipboard <串>：服务端解码 + 校验 + 写入笔记库 + 同步 */
    private fun importShare(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.player ?: return 0
        val raw = StringArgumentType.getString(ctx, "share")
        val notes = PlayerNotes.get(player.serverLevel())
        if (!notes.isAllowed(player.uuid)) {
            ctx.source.sendFailure(Component.translatable("hexguide.cmd.no_authority"))
            return 0
        }
        if (SecurityGuards.rateLimited(player.uuid, "note_share_import", SecurityGuards.SHARE_PER_MINUTE, 60_000L)) {
            ctx.source.sendFailure(Component.translatable("hexguide.cmd.share.rate_limited"))
            return 0
        }
        if (raw.length > SecurityGuards.MAX_SHARE_CHARS) {
            ctx.source.sendFailure(Component.translatable("hexguide.cmd.share.too_long", SecurityGuards.MAX_SHARE_CHARS))
            return 0
        }

        val result = NoteShareCodec.decode(raw)
        if (result is NoteShareCodec.Result.Err) {
            ctx.source.sendFailure(Component.translatable("hexguide.cmd.share." + result.reason.name.lowercase()))
            return 0
        }
        val pages = (result as NoteShareCodec.Result.Ok).pages

        // 服务端二次校验：即便解码器已限长，这里仍按 SecurityGuards 的正式上限逐页确认
        if (pages.size > SecurityGuards.MAX_NOTE_PAGES) {
            ctx.source.sendFailure(Component.translatable("hexguide.cmd.share.too_many_pages", SecurityGuards.MAX_NOTE_PAGES))
            return 0
        }
        for (p in pages) {
            if (p.title.length > SecurityGuards.MAX_NOTE_TITLE_CHARS) {
                ctx.source.sendFailure(Component.translatable("hexguide.cmd.share.title_too_long", SecurityGuards.MAX_NOTE_TITLE_CHARS))
                return 0
            }
            if (p.body.length > SecurityGuards.MAX_NOTE_PAGE_CHARS) {
                ctx.source.sendFailure(Component.translatable("hexguide.cmd.share.body_too_long", SecurityGuards.MAX_NOTE_PAGE_CHARS))
                return 0
            }
        }

        val now = System.currentTimeMillis()
        val iotas = pages.map { p ->
            NoteIota(NoteIota.makeData(
                title = p.title,
                body = p.body,
                // 保留原作者（分享串里带的），空则记执行者
                author = p.author.ifEmpty { player.gameProfile.name },
                id = p.id.ifEmpty { UUID.randomUUID().toString() },
                time = if (p.time > 0) p.time else now,
            ))
        }
        notes.newSection(player.uuid, iotas)
        syncNotes(player, notes)
        ctx.source.sendSuccess({ Component.translatable("hexguide.cmd.share.imported", iotas.size) }, false)
        return 1
    }

    /**
     * /hexguide demoexport <索引>：把一节笔记里**能识别**的图案/iota 抽出来生成演示草稿。
     * 务实解析：`iota:<a85>`、`iota:<ns:path.json>`、`pattern{<dir>,<sig>}`；
     * 解不出的引用跳过并计数提示，不追求解析全部 Markdown。
     */
    private fun demoExport(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.player ?: return 0
        val index = IntegerArgumentType.getInteger(ctx, "index")
        val notes = PlayerNotes.get(player.serverLevel())
        if (!notes.isAllowed(player.uuid)) {
            ctx.source.sendFailure(Component.translatable("hexguide.cmd.no_authority"))
            return 0
        }
        val secs = notes.sections(player.uuid)
        if (index !in secs.indices) {
            ctx.source.sendFailure(Component.translatable("hexguide.cmd.bad_index", secs.size, (secs.size - 1).coerceAtLeast(0)))
            return 0
        }
        val extracted = DemoGenerator.extractFromNotes(secs[index], player.server)
        if (extracted.iotas.isEmpty()) {
            ctx.source.sendFailure(Component.translatable("hexguide.cmd.share.demoexport_nothing", extracted.skippedRefs))
            return 0
        }
        val title = secs[index].firstOrNull()?.title.orEmpty()
        val name = if (title.isNotEmpty()) DemoGenerator.sanitizeName(title) else DemoGenerator.defaultName()
        val ref = DemoGenerator.save(extracted.iotas, name, title = title)
        if (ref == null) {
            ctx.source.sendFailure(Component.translatable("hexguide.cmd.share.demoexport_failed"))
            return 0
        }
        ctx.source.sendSuccess(
            { Component.translatable("hexguide.cmd.share.demoexport_ok", extracted.iotas.size, name, extracted.skippedRefs) },
            false
        )
        return 1
    }

    /** /hexguide import <文件名>：从 JSON 文件导入一节笔记 */
    private fun importNote(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.player ?: return 0
        val name = StringArgumentType.getString(ctx, "file")
        val notes = PlayerNotes.get(player.serverLevel())
        if (!notes.isAllowed(player.uuid)) {
            ctx.source.sendFailure(Component.translatable("hexguide.cmd.no_authority"))
            return 0
        }
        val file = notesDir.resolve(name)
        if (!Files.exists(file)) {
            ctx.source.sendFailure(Component.translatable("hexguide.cmd.file_missing", file.toString()))
            return 0
        }
        val iotas = try {
            parseJson(Files.readString(file))
        } catch (e: Exception) {
            null
        }
        if (iotas == null || iotas.isEmpty()) {
            ctx.source.sendFailure(Component.translatable("hexguide.cmd.file_bad", name))
            return 0
        }
        notes.newSection(player.uuid, iotas)
        syncNotes(player, notes)
        ctx.source.sendSuccess({ Component.translatable("hexguide.cmd.imported", iotas.size, name) }, false)
        return 1
    }

    /** /hexguide authority <玩家> <true|false>：单独配置某玩家权限 */
    private fun setAuthority(ctx: CommandContext<CommandSourceStack>): Int {
        val target = EntityArgument.getPlayer(ctx, "player")
        val allowed = BoolArgumentType.getBool(ctx, "allowed")
        val notes = PlayerNotes.get(target.serverLevel())
        notes.setAuthority(target.uuid, allowed)
        ctx.source.sendSuccess({
            Component.translatable("hexguide.cmd.authority", target.name.string, allowed)
        }, true)
        return 1
    }

    // ---- JSON 序列化 ----

    /** 一节 → {"version":1,"pages":[{title,body,author,id,time},...]} */
    private fun buildJson(sec: List<NoteIota>): String {
        val pages = JsonArray()
        for (iota in sec) {
            val p = JsonObject()
            p.addProperty("title", iota.title)
            p.addProperty("body", iota.body)
            p.addProperty("author", iota.author)
            p.addProperty("id", iota.id)
            p.addProperty("time", iota.time)
            pages.add(p)
        }
        val root = JsonObject()
        root.addProperty("version", 1)
        root.add("pages", pages)
        // 格式化（带换行缩进），便于阅读/编辑
        return com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root)
    }

    /** 清洗文件名非法字符（Windows: \ / : * ? " < > |），并限制长度 */
    private fun sanitize(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(64)

    /** JSON → NoteIota 列表 */
    private fun parseJson(json: String): List<NoteIota>? {
        val root = JsonParser.parseString(json).asJsonObject
        val pages = root.getAsJsonArray("pages")
        val now = System.currentTimeMillis()
        return pages.map { el ->
            val p = el.asJsonObject
            NoteIota(NoteIota.makeData(
                title = p.get("title")?.asString ?: "",
                body = p.get("body")?.asString ?: "",
                author = p.get("author")?.asString ?: "",
                id = p.get("id")?.asString ?: UUID.randomUUID().toString(),
                time = p.get("time")?.asLong ?: now,
            ))
        }
    }
}
