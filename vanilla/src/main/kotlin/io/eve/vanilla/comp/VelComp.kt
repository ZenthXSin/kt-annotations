package io.eve.vanilla.comp

import io.eve.ktannot.*

import io.eve.vanilla.gen.*
import kotlin.jvm.Transient
import arc.math.Mathf
import arc.math.geom.Vec2
import arc.util.Time
import mindustry.Vars
import mindustry.entities.EntityCollisions.SolidPred

@Component
abstract class VelComp : Posc, Entityc {
    @Import var x = 0f
    @Import var y = 0f

    @SyncLocal var vel = Vec2()

    @Transient var drag = 0f

    @MethodPriority(-1)
    override fun update() {
        if (!Vars.net.client() || isLocal()) {
            val px = x
            val py = y
            move(vel.x * Time.delta, vel.y * Time.delta)
            if (Mathf.equal(px, x)) vel.x = 0f
            if (Mathf.equal(py, y)) vel.y = 0f
            vel.scl(kotlin.math.max(1f - drag * Time.delta, 0f))
        }
    }

    fun solidity(): SolidPred? = null

    fun ignoreSolids(): Boolean = false

    fun canPass(tileX: Int, tileY: Int): Boolean {
        val s = solidity()
        return s == null || !s.solid(tileX, tileY)
    }

    fun canPassOn(): Boolean {
        return canPass(tileX(), tileY())
    }

    fun moving(): Boolean {
        return !vel.isZero(0.01f)
    }

    fun move(v: Vec2) {
        move(v.x, v.y)
    }

    fun move(cx: Float, cy: Float) {
        val check = solidity()
        if (check != null) {
            Vars.collisions.move(self(), cx, cy, check)
        } else {
            x += cx
            y += cy
        }
    }

    fun velAddNet(v: Vec2) {
        vel.add(v)
        if (isRemote()) {
            x += v.x
            y += v.y
        }
    }

    fun velAddNet(vx: Float, vy: Float) {
        vel.add(vx, vy)
        if (isRemote()) {
            x += vx
            y += vy
        }
    }
}