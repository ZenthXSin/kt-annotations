package io.eve.vanilla.comp

import io.eve.ktannot.*
import io.eve.vanilla.gen.*
import arc.func.Cons
import arc.graphics.g2d.Draw
import arc.math.Mathf
import arc.math.geom.Geometry
import arc.math.geom.Point2
import arc.math.geom.Rect
import arc.util.Time
import mindustry.Vars
import mindustry.content.Blocks
import mindustry.content.Fx
import mindustry.entities.Puddles
import mindustry.gen.*
import mindustry.graphics.Layer
import mindustry.type.Liquid
import mindustry.world.Tile
import mindustry.gen.Groups

import mindustry.Vars.*
import mindustry.entities.Puddles.*

@Component
abstract class PuddleComp : Posc, Puddlec, Drawc, Syncc {
    companion object {
        private val rect = Rect()
        private val rect2 = Rect()

        private var paramPuddle: Puddle? = null
        private val unitCons = Cons<Unit> { unit ->
            if (unit.isGrounded() && !unit.type.hovering) {
                unit.hitbox(rect2)
                if (rect.overlaps(rect2)) {
                    unit.apply(paramPuddle!!.liquid.effect, 60 * 2)
                    if (unit.vel.len2() > 0.1f * 0.1f) {
                        Fx.ripple.at(unit.x, unit.y, unit.type.rippleScale, paramPuddle!!.liquid.color)
                    }
                }
            }
        }
    }

    @Import var id = 0
    @Import var x = 0f
    @Import var y = 0f
    @Import var added = false

    @Transient var accepting: Float = 0f
    @Transient var updateTime: Float = 0f
    @Transient var lastRipple: Float = Time.time + Mathf.random(40f)
    @Transient var effectTime: Float = Mathf.random(50f)
    var amount = 0f
    var tile: Tile? = null
    var liquid: Liquid? = null

    fun getFlammability(): Float {
        return liquid!!.flammability * amount
    }

    override fun update() {
        if (liquid == null || tile == null) {
            remove()
            return
        }

        val addSpeed = if (accepting > 0) 3f else 0f

        amount -= Time.delta * (1f - liquid!!.viscosity) / (5f + addSpeed)
        amount += accepting
        amount = Math.min(amount, maxLiquid)
        accepting = 0f

        if (amount >= maxLiquid / 1.5f) {
            var deposited = Math.min((amount - maxLiquid / 1.5f) / 4f, 0.3f * Time.delta)
            var targets = 0
            for (point in Geometry.d4) {
                val other = world.tile(tile!!.x + point.x, tile!!.y + point.y)
                if (other != null && (other.block() === Blocks.air || liquid!!.moveThroughBlocks)) {
                    targets++
                    Puddles.deposit(other, tile, liquid!!, deposited, false)
                }
            }
            amount -= deposited * targets
        }

        if (liquid!!.capPuddles) {
            amount = Mathf.clamp(amount, 0, maxLiquid)
        }

        if (amount <= 0f) {
            remove()
            return
        }

        if (Puddles.get(tile!!) !== self() && added) {
            //force removal without pool free
            Groups.all.remove(self())
            Groups.draw.remove(self())
            Groups.puddle.remove(self())
            added = false
            return
        }

        //effects-only code
        if (amount >= maxLiquid / 2f && updateTime <= 0f) {
            paramPuddle = self()

            Units.nearby(rect.setSize(Mathf.clamp(amount / (maxLiquid / 1.5f)) * 10f).setCenter(x, y), unitCons)

            if (liquid!!.temperature > 0.7f && tile!!.build != null && Mathf.chance(0.5)) {
                Fires.create(tile!!)
            }

            updateTime = 40f

            if (tile!!.build != null) {
                tile!!.build.puddleOn(self())
            }
        }

        if (!headless && liquid!!.particleEffect !== Fx.none) {
            effectTime += Time.delta
            if (effectTime >= liquid!!.particleSpacing) {
                val size = Mathf.clamp(amount / (maxLiquid / 1.5f)) * 4f
                liquid!!.particleEffect.at(x + Mathf.range(size), y + Mathf.range(size))
                effectTime = 0f
            }
        }

        updateTime -= Time.delta

        liquid!!.update(self())
    }

    override fun draw() {
        Draw.z(Layer.debris - 1)

        liquid!!.drawPuddle(self())
    }

    @Replace
    fun clipSize(): Float = 50f //high for light drawing

    override fun remove() {
        Puddles.remove(tile!!)
    }

    override fun afterRead() {
        Puddles.register(self())
    }

    override fun afterSync() {
        if (liquid != null) {
            Puddles.register(self())
        }
    }
}