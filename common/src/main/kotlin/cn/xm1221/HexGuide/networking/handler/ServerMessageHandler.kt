package cn.xm1221.HexGuide.networking.handler

import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import at.petrak.hexcasting.api.casting.eval.vm.CastingVM
import at.petrak.hexcasting.api.casting.iota.IotaType
import at.petrak.hexcasting.api.casting.iota.PatternIota
import at.petrak.hexcasting.api.casting.math.HexDir
import at.petrak.hexcasting.api.casting.math.HexPattern
import at.petrak.hexcasting.api.mod.HexTags
import at.petrak.hexcasting.common.lib.hex.HexActions
import at.petrak.hexcasting.xplat.IXplatAbstractions
import cn.xm1221.HexGuide.HexGuide
import cn.xm1221.HexGuide.api.notes.NoteIota
import cn.xm1221.HexGuide.api.notes.PlayerNotes
import cn.xm1221.HexGuide.networking.SecurityGuards
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.architectury.networking.NetworkManager.PacketContext
import cn.xm1221.HexGuide.networking.msg.*
import cn.xm1221.HexGuide.registry.HexGuideItems
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.packs.PackType
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack

/**
 * 读取全局 pattern_vector 默认 origin 表：
 * data/hexguide/pattern_vector/ 目录下所有 .json，按文件名顺序加载并合并（后加载覆盖先加载）。
 * 每个文件可记录多个 action：{"<action id>": {"origin": [q, r]}, ...}
 */
private fun readPatternVector(player: ServerPlayer): String? {
    return try {
        val rm = player.server.resourceManager
        val merged = JsonObject()
        // 手动遍历 packs 枚举（listResources 在服务端 MultiPackResourceManager 下可能枚举不到，
        // 但 getResource 能读到——底层 API 更可靠）
        val found = ArrayList<ResourceLocation>()
        for (pack in rm.listPacks()) {
            for (ns in pack.getNamespaces(PackType.SERVER_DATA)) {
                if (ns == HexGuide.MODID) {
                    // 1.20.1 的 PackResources.listResources 是回调式（ResourceOutput）
                    pack.listResources(PackType.SERVER_DATA, ns, "pattern_vector") { rl, _ ->
                        if (rl.path.endsWith(".json")) found.add(rl)
                    }
                }
            }
        }
        HexGuide.LOGGER.debug("[Spellplay] pattern_vector 枚举到 {} 个文件: {}", found.size, found.map { it.path })
        for (rl in found.sortedBy { it.path }) { // 按文件名顺序：后加载覆盖先加载的同名 action
            val json = rm.getResource(rl)
                .map { it.open().use { r -> r.readBytes().toString(Charsets.UTF_8) } }
                .orElse(null) ?: continue
            try {
                val obj = JsonParser.parseString(json).asJsonObject
                for ((k, v) in obj.entrySet()) merged.add(k, v)
            } catch (e: Exception) {
                HexGuide.LOGGER.warn("[Spellplay] 解析 pattern_vector 文件失败: {}", rl, e)
            }
        }
        if (merged.size() > 0) merged.toString() else null
    } catch (e: Exception) {
        HexGuide.LOGGER.warn("[Spellplay] readPatternVector 异常", e)
        null
    }
}

fun HexGuideMessageC2S.applyOnServer(ctx: PacketContext) = ctx.queue {
    when (this) {
        // 把写模式记录的图案推入法杖栈（不执行），让法杖栈与本地栈同步
        is MsgBookSyncStackC2S -> {
            val player = ctx.player as? ServerPlayer ?: return@queue
            if (SecurityGuards.rateLimited(player.uuid, "sync_stack", maxPerWindow = 8)) return@queue
            if (patterns.size > SecurityGuards.MAX_SYNC_PATTERNS) return@queue
            for (tag in patterns) {
                if (!SecurityGuards.isReasonableNbt(tag, SecurityGuards.MAX_IOTA_NBT_BYTES)) return@queue
                if (!SecurityGuards.isAllowedIotaTag(tag)) return@queue
            }
            val vm = IXplatAbstractions.INSTANCE.getStaffcastVM(player, InteractionHand.MAIN_HAND)
            val newStack = ArrayList(vm.image.stack)
            for (tag in patterns) {
                try {
                    newStack.add(IotaType.deserialize(tag, player.serverLevel()) ?: continue)
                } catch (e: Exception) {
                    // 忽略无法解析的图案
                }
            }
            IXplatAbstractions.INSTANCE.setStaffcastImage(player, vm.image.copy(stack = newStack))
        }

        // 演示"真执行"：上传的本地 CastingImage + 图案 → 新 VM 运行（不碰玩家法杖栈）→ 结果传回
        is MsgBookExecDemoC2S -> {
            val player = ctx.player as? ServerPlayer ?: return@queue
            if (SecurityGuards.rateLimited(player.uuid, "exec_demo", maxPerWindow = 12)) return@queue
            if (!SecurityGuards.isValidSignatureChars(sig)) return@queue
            if (!SecurityGuards.isReasonableNbt(image, SecurityGuards.MAX_IMAGE_NBT_BYTES)) return@queue
            val world = player.serverLevel()
            val image = try {
                CastingImage.loadFromNbt(image, world)
            } catch (e: Exception) {
                CastingImage()
            }
            val pattern = try {
                HexPattern.fromAngles(sig, HexDir.valueOf(startDir))
            } catch (e: Exception) {
                return@queue
            }
            val env = DemoCastEnv(player, InteractionHand.MAIN_HAND)
            val vm = CastingVM(image, env)
            val result = vm.queueExecuteAndWrapIota(PatternIota(pattern), world)
            MsgBookExecDemoS2C(vm.image.serializeToNbt(), result.resolutionType.name)
                .sendToPlayer(player)
        }

        // push_in：把指定 iota 交给服务端按原版逻辑执行（escapeNext → 转义；parenCount>0 → 进括号；否则压主栈）
        is MsgBookPushIotaC2S -> {
            val player = ctx.player as? ServerPlayer ?: return@queue
            if (SecurityGuards.rateLimited(player.uuid, "push_iota", maxPerWindow = 16)) return@queue
            if (!SecurityGuards.isReasonableNbt(iotaNbt, SecurityGuards.MAX_IOTA_NBT_BYTES)) return@queue
            if (!SecurityGuards.isAllowedIotaTag(iotaNbt)) return@queue
            if (!SecurityGuards.isReasonableNbt(image, SecurityGuards.MAX_IMAGE_NBT_BYTES)) return@queue
            val world = player.serverLevel()
            val image = try {
                CastingImage.loadFromNbt(image, world)
            } catch (e: Exception) {
                CastingImage()
            }
            val iota = try {
                IotaType.deserialize(iotaNbt, world) ?: return@queue
            } catch (e: Exception) {
                return@queue
            }
            val env = DemoCastEnv(player, InteractionHand.MAIN_HAND)
            val vm = CastingVM(image, env)
            val result = vm.queueExecuteAndWrapIota(iota, world)
            MsgBookExecDemoS2C(vm.image.serializeToNbt(), result.resolutionType.name)
                .sendToPlayer(player)
        }

        // 读取演示配置 data/<ns>/spellplays/<name>.json + 全局 pattern_vector（默认 origin 表）并传回客户端
        is MsgBookLoadSpellplayC2S -> {
            val player = ctx.player as? ServerPlayer ?: return@queue
            if (SecurityGuards.rateLimited(player.uuid, "load_spellplay", maxPerWindow = 8)) return@queue
            val rl = ResourceLocation(ns, "spellplays/$name.json")
            try {
                val opt = player.server.resourceManager.getResource(rl)
                val pvJson = readPatternVector(player)
                if (opt.isPresent) {
                    val json = opt.get().open().use { it.readBytes().toString(Charsets.UTF_8) }
                    MsgBookLoadSpellplayS2C(ns, name, json, pvJson).sendToPlayer(player)
                } else {
                    MsgBookLoadSpellplayS2C(ns, name, null, pvJson).sendToPlayer(player)
                }
            } catch (e: Exception) {
                MsgBookLoadSpellplayS2C(ns, name, null, null).sendToPlayer(player)
            }
        }

        // 返回创造标签页应排除的图案 id（服务端 tag 数据完整；客户端 Forge 静态 registry 查不到 tag）
        is MsgRequestExcludedPatternsC2S -> {
            val player = ctx.player as? ServerPlayer ?: return@queue
            // 用 registry.getTag 而非 getHolder(key).is(tag)：getHolder 会创建 STAND_ALONE holder，
            // 而 bindTags 跳过 STAND_ALONE，导致 holder 无 tag 且污染注册表
            val greatTag = HexActions.REGISTRY.getTag(HexTags.Actions.REQUIRES_ENLIGHTENMENT)
            val perWorldTag = HexActions.REGISTRY.getTag(HexTags.Actions.PER_WORLD_PATTERN)
            val excluded = HexActions.REGISTRY.entrySet()
                .filter { (key, _) ->
                    val inGreat = greatTag.isPresent && greatTag.get().stream().anyMatch { it.`is`(key) }
                    val inPerWorld = perWorldTag.isPresent && perWorldTag.get().stream().anyMatch { it.`is`(key) }
                    inGreat || inPerWorld
                }
                .map { (key, _) -> key.location().toString() }
            MsgExcludedPatternsS2C(excluded).sendToPlayer(player)
        }

        // 追加 NoteIota 到某节末尾（编辑/导入）→ 回发全量
        is MsgNotesAppendC2S -> {
            val player = ctx.player as? ServerPlayer ?: return@queue
            if (SecurityGuards.rateLimited(player.uuid, "notes_append", maxPerWindow = 8)) return@queue
            val notes = PlayerNotes.get(player.serverLevel())
            val iota = NoteIota.TYPE.deserialize(iota, player.serverLevel())
            if (iota != null) {
                notes.appendIota(player.uuid, sectionIndex, iota)
                syncNotes(player, notes)
            }
        }

        // 新建一节（整节 = NoteIota 列表）→ 回发全量
        is MsgNotesNewSectionC2S -> {
            val player = ctx.player as? ServerPlayer ?: return@queue
            if (SecurityGuards.rateLimited(player.uuid, "notes_new", maxPerWindow = 4)) return@queue
            val notes = PlayerNotes.get(player.serverLevel())
            val iotas = iotas.mapNotNull { NoteIota.TYPE.deserialize(it, player.serverLevel()) }
            notes.newSection(player.uuid, iotas)
            syncNotes(player, notes)
        }

        // 紫水晶笔保存：每页消耗副手一张纸 → 生成"笔记残页"（携带该页 NoteIota）。
        // 只产出物品，不写笔记库——条目只能通过 note/import 添加。
        is MsgNotesSaveC2S -> {
            val player = ctx.player as? ServerPlayer ?: return@queue
            if (SecurityGuards.rateLimited(player.uuid, "notes_save", maxPerWindow = 4)) return@queue
            if (title.length > SecurityGuards.MAX_NOTE_TITLE_CHARS) return@queue
            if (pages.isEmpty() || pages.size > SecurityGuards.MAX_NOTE_PAGES) return@queue
            if (pages.any { it.length > SecurityGuards.MAX_NOTE_PAGE_CHARS }) return@queue
            val off = player.offhandItem
            if (!off.`is`(net.minecraft.world.item.Items.PAPER)) return@queue
            if (off.count < pages.size) {
                player.displayClientMessage(Component.translatable("hexguide.notes.need_more_paper"), true)
                return@queue
            }
            val now = System.currentTimeMillis()
            for ((i, body) in pages.withIndex()) {
                val iota = NoteIota(NoteIota.makeData(
                    title = if (i == 0) title else "",
                    body = body,
                    author = player.gameProfile.name,
                    id = java.util.UUID.randomUUID().toString(),
                    time = now,
                ))
                // 副手一张纸 → 笔记残页（携带该页 NoteIota）
                off.shrink(1)
                val scrap = ItemStack(HexGuideItems.NOTE_SCRAP.value)
                cn.xm1221.HexGuide.items.NoteScrapItem.setNote(scrap, iota)
                if (!player.addItem(scrap)) player.drop(scrap, false)
            }
        }

        else -> {}
    }
}

/** 把某玩家的全部笔记下发到其客户端 */
fun syncNotes(player: ServerPlayer, notes: PlayerNotes) {
    val sections = notes.sections(player.uuid).map { sec -> sec.map { it.serialize() as CompoundTag } }
    MsgNotesSyncS2C(player.uuid, sections).sendToPlayer(player)
}
