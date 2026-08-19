package arc.util.pooling

object Pools {
    fun obtain(type: Class<*>): Pool.Poolable = TODO()
    fun free(obj: Pool.Poolable) {}
}