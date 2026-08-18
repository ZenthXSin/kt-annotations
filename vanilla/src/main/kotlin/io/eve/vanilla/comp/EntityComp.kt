package io.eve.vanilla.comp

import io.eve.ktannot.*

import io.eve.vanilla.gen.*
import kotlin.jvm.Transient
import arc.util.io.*

@Component
@BaseComponent
abstract class EntityComp {
    @Transient private var added = false
    @Transient var id: Int = mindustry.entities.EntityGroup.nextId()

    fun isAdded(): Boolean = added

    open fun update() {}

    open fun remove() {
        added = false
    }

    open fun add() {
        added = true
    }

    fun isLocal(): Boolean = (this as? Any) === (mindustry.Vars.player as? Any)

    fun isRemote(): Boolean = false

    @Suppress("UNCHECKED_CAST")
    fun <T : Entityc> self(): T = this as T

    @Suppress("UNCHECKED_CAST")
    fun <T> `as`(): T = this as T

    @InternalImpl
    abstract fun classId(): Int

    @InternalImpl
    abstract fun serialize(): Boolean

    @MethodPriority(1)
    open fun read(read: Reads) {
        afterRead()
    }

    open fun write(write: Writes) {}

    open fun beforeWrite() {}

    open fun afterRead() {}

    open fun afterReadAll() {}
}