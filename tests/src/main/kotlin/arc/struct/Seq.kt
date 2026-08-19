package arc.struct

class Seq<T>(vararg items: T) {
    private val list = mutableListOf(*items)
    fun add(item: T) { list.add(item) }
    fun clear() { list.clear() }
    fun size(): Int = list.size
    fun get(i: Int): T = list[i]
    operator fun iterator(): Iterator<T> = list.iterator()

    companion object {
        fun <T> with(vararg items: T): Seq<T> = Seq(*items)
    }
}
