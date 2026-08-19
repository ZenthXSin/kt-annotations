package arc.struct

class IntMap<V> {
    private val map = mutableMapOf<Int, V>()
    fun put(key: Int, value: V) { map[key] = value }
    fun get(key: Int): V? = map[key]
    fun size(): Int = map.size
}