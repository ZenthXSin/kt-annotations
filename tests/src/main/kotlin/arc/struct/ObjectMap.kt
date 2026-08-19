package arc.struct

class ObjectMap<K, V> {
    private val map = linkedMapOf<K, V>()
    fun put(key: K, value: V) { map[key] = value }
    fun get(key: K): V? = map[key]
    fun size(): Int = map.size
}