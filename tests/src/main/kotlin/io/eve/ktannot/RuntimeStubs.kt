package io.eve.ktannot.gen

// ---- 运行时支持类:生成代码依赖的最小网络/图集/IO 栈 ----

/** 生成的 *c 接口标记注解 */
@Target(AnnotationTarget.CLASS)
annotation class EntityInterface

/** 最小字节缓冲(对标 arc ByteBuf 的 putFloat/getFloat) */
class ByteBuf {
    fun putFloat(v: Float) {}
    fun getFloat(): Float = 0f
    fun putInt(v: Int) {}
    fun getInt(): Int = 0
    fun putLong(v: Long) {}
    fun getLong(): Long = 0
    fun putShort(v: Short) {}
    fun getShort(): Short = 0
    fun putBool(b: Boolean) {}
    fun getBool(): Boolean = false
    fun putByte(v: Byte) {}
    fun getByte(): Byte = 0
}

/** 简单字节读写游标(对标 arc.util.io Reads/Writes 的最小实现) */
class Writes {
    private val buf = mutableListOf<Any?>()
    fun i(v: Int) { buf.add(v) }
    fun f(v: Float) { buf.add(v) }
    fun bool(b: Boolean) { buf.add(b) }
    fun l(v: Long) { buf.add(v) }
    fun s(v: Short) { buf.add(v) }
    fun b(v: Byte) { buf.add(v) }
    fun str(v: String) { buf.add(v) }
    fun obj(v: Any?) { buf.add(v) }
    fun written(): List<Any?> = buf.toList()
}

class Reads {
    private val buf = mutableListOf<Any?>()
    private var pos = 0
    fun feed(values: List<Any?>) { buf.clear(); buf.addAll(values); pos = 0 }
    private fun next(): Any? { val v = buf[pos]; pos++; return v }
    fun i(): Int = next() as Int
    fun f(): Float = next() as Float
    fun bool(): Boolean = next() as Boolean
    fun l(): Long = next() as Long
    fun s(): Short = next() as Short
    fun b(): Byte = next() as Byte
    fun str(): String = next() as String
    fun obj(): Any? = next()
    fun playerObj(): Player = next() as Player
    fun b(length: Int): ByteArray = ByteArray(length)
}

/** 网络连接 */
class NetConnection(val player: Player? = null, val kicked: Boolean = false) {
    fun send(packet: Packet, reliable: Boolean) {}
}

/** 网络核心 */
object Net {
    fun isServer(): Boolean = false
    fun isClient(): Boolean = false
    fun active(): Boolean = false
    fun player(): Player = Player()
    fun send(packet: Packet, reliable: Boolean) {}
    fun sendExcept(except: NetConnection, packet: Packet, reliable: Boolean) {}
    fun registerPacket(factory: () -> Packet) {}
}

class Player

/** 生成代码引用的顶层 net(player) 访问器(对标原版全局) */
val net: Net = Net
fun player(): Player = Player()

abstract class Packet {
    companion object {
        val NODATA = ByteArray(0)
    }
    var DATA: ByteArray = NODATA
    abstract fun write(WRITE: Writes)
    abstract fun read(READ: Reads, LENGTH: Int)
    abstract fun handled()
    open fun handleClient() {}
    open fun handleServer(con: NetConnection) {}

    protected val BAIS = ByteBufferHolder()
    protected val READ = Reads()
    fun setReadInput(values: List<Any?>) { READ.feed(values) }

    class ByteBufferHolder {
        fun setBytes(data: ByteArray) {}
        fun readInt(): Int = 0
        fun readFloat(): Float = 0f
        fun readBoolean(): Boolean = false
        fun readLong(): Long = 0
        fun readShort(): Short = 0
        fun readByte(): Byte = 0
        fun readString(): String = ""
    }
}

/** 图集(模拟 Core.atlas,返回 arc 桩 TextureRegion) */
object Core {
    object atlas {
        fun find(name: String): arc.graphics.g2d.TextureRegion = arc.graphics.g2d.TextureRegion(name)
        fun find(name: String, fallback: String): arc.graphics.g2d.TextureRegion = arc.graphics.g2d.TextureRegion(name)
        fun drawable(name: String): arc.scene.style.Drawable = arc.scene.style.Drawable(name)
    }
}

// 生成代码里会引用 Seq(全部语句表)
object GlobalStub {
    fun noop() {}
}