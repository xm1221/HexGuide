package cn.xm1221.HexGuide.api.notes

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData
import java.util.UUID

/**
 * 玩家笔记库（服务端存档，WorldSavedData）。
 * 结构：玩家 UUID → 节的列表；每节 = 一条笔记 = NoteIota 列表（一个 NoteIota 一页）。
 *
 * 存档挂在**主世界 / overworld** 的 DimensionDataStorage 上，跨维度共享同一份笔记。
 * 旧版按维度落盘的 `dimensions/<dim>/data/hexguide_notes.dat` **不会**自动合并，需玩家自行
 * `/hexguide export` 后再 import，或手动挪到 `world/data/hexguide_notes.dat`。
 */
class PlayerNotes : SavedData() {

    /** 玩家 → 节的列表；每节 = NoteIota 列表 */
    private val notes: MutableMap<UUID, MutableList<MutableList<NoteIota>>> = mutableMapOf()

    /** 玩家 → export/import 指令权限（缺省 = 允许，默认开） */
    private val authority: MutableMap<UUID, Boolean> = mutableMapOf()

    /** 设置某玩家 export/import 指令权限 */
    fun setAuthority(uuid: UUID, allowed: Boolean) {
        authority[uuid] = allowed
        setDirty()
    }

    /** 某玩家是否允许 export/import（默认开） */
    fun isAllowed(uuid: UUID): Boolean = authority[uuid] ?: true

    /** 获取某玩家的所有节（空则建空列表） */
    fun sections(uuid: UUID): MutableList<MutableList<NoteIota>> =
        notes.getOrPut(uuid) { mutableListOf() }

    /**
     * 追加一个 NoteIota 到某玩家的第 sectionIndex 节末尾。
     * 索引越界、或已到总量上限时忽略（上限见 SecurityGuards.MAX_*，防止单客户端撑爆存档）。
     */
    fun appendIota(uuid: UUID, sectionIndex: Int, iota: NoteIota) {
        val secs = sections(uuid)
        if (sectionIndex in secs.indices &&
            secs[sectionIndex].size < cn.xm1221.HexGuide.networking.SecurityGuards.MAX_SECTION_IOTAS
        ) {
            secs[sectionIndex].add(iota)
            setDirty()
        }
    }

    /** 新建一节（整个节 = NoteIota 列表，可为空建空节）；到上限则忽略 */
    fun newSection(uuid: UUID, iotas: List<NoteIota>) {
        val secs = sections(uuid)
        if (secs.size >= cn.xm1221.HexGuide.networking.SecurityGuards.MAX_NOTE_SECTIONS) return
        secs.add(iotas.take(cn.xm1221.HexGuide.networking.SecurityGuards.MAX_SECTION_IOTAS).toMutableList())
        setDirty()
    }

    // ---- 序列化（1.20.1：SavedData 不 override load，由 Factory 的 deserializer 负责） ----

    override fun save(tag: CompoundTag): CompoundTag {
        val players = ListTag()
        for ((uuid, secs) in notes) {
            val p = CompoundTag()
            p.putUUID("uuid", uuid)
            val sectionsTag = ListTag()
            for (sec in secs) {
                val secTag = ListTag()
                for (iota in sec) {
                    secTag.add(iota.serialize())
                }
                sectionsTag.add(secTag)
            }
            p.put("sections", sectionsTag)
            players.add(p)
        }
        tag.put("players", players)

        // 权限表
        val authTag = ListTag()
        for ((uuid, allowed) in authority) {
            val a = CompoundTag()
            a.putUUID("uuid", uuid)
            a.putBoolean("allowed", allowed)
            authTag.add(a)
        }
        tag.put("authority", authTag)
        return tag
    }

    private fun readFrom(tag: CompoundTag) {
        notes.clear()
        authority.clear()
        val players = tag.getList("players", Tag.TAG_COMPOUND.toInt())
        for (i in 0 until players.size) {
            val p = players.getCompound(i)
            val uuid = p.getUUID("uuid")
            val secs = mutableListOf<MutableList<NoteIota>>()
            val sectionsTag = p.getList("sections", Tag.TAG_LIST.toInt())
            for (j in 0 until sectionsTag.size) {
                val secTag = sectionsTag.getList(j)
                val sec = mutableListOf<NoteIota>()
                for (k in 0 until secTag.size) {
                    val t = secTag.get(k)
                    val iota = NoteIota.TYPE.deserialize(t, null)
                    if (iota != null) sec.add(iota)
                }
                secs.add(sec)
            }
            notes[uuid] = secs
        }

        // 权限表
        val authTag = tag.getList("authority", Tag.TAG_COMPOUND.toInt())
        for (i in 0 until authTag.size) {
            val a = authTag.getCompound(i)
            authority[a.getUUID("uuid")] = a.getBoolean("allowed")
        }
    }

    companion object {
        const val NAME = "hexguide_notes"

        private fun load(tag: CompoundTag): PlayerNotes =
            PlayerNotes().also { it.readFrom(tag) }

        private fun newData(): PlayerNotes = PlayerNotes()

        /** 始终挂主世界 dataStorage，避免跨维度丢笔记。 */
        fun get(level: ServerLevel): PlayerNotes {
            val storage = level.server.overworld().dataStorage
            return storage.computeIfAbsent(::load, ::newData, NAME)
        }
    }
}
