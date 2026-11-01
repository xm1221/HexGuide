package cn.xm1221.HexGuide.api.notes

import net.minecraft.nbt.CompoundTag
import java.util.UUID

/**
 * 客户端笔记缓存（单例）。
 * 存放【当前玩家】自己的笔记副本（服务端全量下发），并维护【当前节】索引——
 * 显示条目 `hexguide:notes` 用 Patchouli 原生多页（~256 个 note_page）：
 * 页码 pageNum → 当前节第 N 个 NoteIota。
 */
object ClientNotes {
    /** 已加载数据的玩家 UUID（null = 尚未收到） */
    var loadedUuid: UUID? = null
        private set

    private var mySections: MutableList<MutableList<NoteIota>> = mutableListOf()

    /** 当前节索引（目录点击/新建后切换） */
    var currentSection: Int = 0
        private set

    /** 服务端全量下发 → 替换本地缓存 */
    fun applySync(uuid: UUID, sections: List<List<CompoundTag>>) {
        loadedUuid = uuid
        mySections = sections.map { sec ->
            sec.mapNotNull { NoteIota.TYPE.deserialize(it, null) }.toMutableList()
        }.toMutableList()
        if (currentSection >= mySections.size) currentSection = (mySections.size - 1).coerceAtLeast(0)
        if (currentSection < 0) currentSection = 0
    }

    /** 所有节（只读） */
    fun sections(): List<List<NoteIota>> = mySections

    fun sectionCount(): Int = mySections.size

    /** 当前节的 NoteIota 列表（页） */
    fun current(): List<NoteIota> =
        if (mySections.isEmpty()) emptyList()
        else mySections[currentSection.coerceIn(0, mySections.size - 1)]

    /** 当前节第 index 个 NoteIota（越界 null） */
    fun page(index: Int): NoteIota? = current().getOrNull(index)

    fun setCurrent(index: Int) {
        currentSection = index.coerceIn(0, (mySections.size - 1).coerceAtLeast(0))
    }
}
