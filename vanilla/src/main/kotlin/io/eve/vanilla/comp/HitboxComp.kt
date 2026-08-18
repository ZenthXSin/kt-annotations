package io.eve.vanilla.comp

import io.eve.ktannot.*
import io.eve.vanilla.gen.*
import arc.func.Cons
import arc.math.Mathf
import arc.math.geom.QuadTree
import arc.math.geom.QuadTree.QuadTreeObject
import arc.math.geom.Rect
import mindustry.entities.Sized
import kotlin.math.min

@Component
abstract class HitboxComp : Posc, Sized, QuadTreeObject {
    @Import var x = 0f
    @Import var y = 0f

    @Transient var lastX = 0f
    @Transient var lastY = 0f
    @Transient var deltaX = 0f
    @Transient var deltaY = 0f
    @Transient var _hitSizeValue = 0f

    override fun update() {}

    override fun add() {
        updateLastPosition()
    }

    override fun afterRead() {
        updateLastPosition()
    }

    override fun hitSize(): Float = _hitSizeValue

    fun hitSize(value: Float) { _hitSizeValue = value }

    fun getCollisions(consumer: Cons<QuadTree<QuadTreeObject>>?) {}

    fun updateLastPosition() {
        deltaX = x - lastX
        deltaY = y - lastY
        lastX = x
        lastY = y
    }

    fun collision(other: Hitboxc, x: Float, y: Float) {}

    fun deltaLen(): Float = Mathf.len(deltaX, deltaY)

    fun deltaAngle(): Float = Mathf.angle(deltaX, deltaY)

    fun collides(other: Hitboxc): Boolean = true

    override fun hitbox(rect: Rect) {
        rect.setCentered(x, y, hitSize, hitSize)
    }

    fun hitboxTile(rect: Rect) {
        val size = min(hitSize * 0.66f, 7.8f)
        rect.setCentered(x, y, size, size)
    }
}