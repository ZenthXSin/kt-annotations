package mindustry.entities

import arc.struct.Seq
import mindustry.gen.Entityc

class EntityGroup<T : Entityc>(
    private val type: Class<T>,
    private val spatial: Boolean,
    private val mapping: Boolean,
    private val indexer: (T, Int) -> Unit = { _, _ -> }
) {
    private val items = Seq<T>()
    private var nextIndex = 0

    fun add(item: T) { items.add(item) }
    fun addIndex(item: T): Int {
        val idx = nextIndex++
        items.add(item)
        indexer(item, idx)
        return idx
    }
    fun remove(item: T) {}
    fun removeIndex(item: T, index: Int) {}
    fun clear() { items.clear() }
    fun resize(x: Float, y: Float, w: Float, h: Float) {}
    fun updatePhysics() {}
    fun collide() {}
    fun all(): Seq<T> = items
}