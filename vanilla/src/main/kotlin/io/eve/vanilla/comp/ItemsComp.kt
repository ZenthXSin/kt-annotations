package io.eve.vanilla.comp

import io.eve.ktannot.*

import io.eve.vanilla.gen.*
import kotlin.jvm.Transient
import arc.math.Mathf
import mindustry.type.Item
import mindustry.type.ItemStack

@Component
abstract class ItemsComp : Posc {
    var stack = ItemStack()
    @Transient var itemTime = 0f

    abstract fun itemCapacity(): Int

    override fun update() {
        stack.amount = Mathf.clamp(stack.amount, 0, itemCapacity())
        itemTime = Mathf.lerpDelta(itemTime, Mathf.num(hasItem()), 0.05f)
    }

    fun item(): Item = stack.item!!

    fun clearItem() {
        stack.amount = 0
    }

    fun acceptsItem(item: Item): Boolean {
        return !hasItem() || item == stack.item && stack.amount + 1 <= itemCapacity()
    }

    fun hasItem(): Boolean = stack.amount > 0

    fun addItem(item: Item) {
        addItem(item, 1)
    }

    fun addItem(item: Item, amount: Int) {
        stack.amount = if (stack.item == item) stack.amount + amount else amount
        stack.item = item
        stack.amount = Mathf.clamp(stack.amount, 0, itemCapacity())
    }

    fun maxAccepted(item: Item): Int {
        return if (stack.item != item && stack.amount > 0) 0 else itemCapacity() - stack.amount
    }
}