package io.eve.vanilla.comp

import io.eve.ktannot.*
import io.eve.vanilla.gen.*
import kotlin.jvm.Transient

@Component
abstract class FireComp : Timedc, Posc, Syncc, Drawc {
    companion object {
        const val frames = 40
        const val duration = 90
        private const val spreadDelay = 22f
        private const val fireballDelay = 40f
        private val ticksPerFrame = duration.toFloat() / frames
        private const val warmupDuration = 20f
        private const val damageDelay = 40f
        private const val tileDamage = 1.8f
        private const val unitDamage = 3f
        val regions = arrayOfNulls<arc.graphics.g2d.TextureRegion>(frames)
    }

    @Import var time = 0f
    @Import var lifetime = 0f
    @Import var x = 0f
    @Import var y = 0f

    var tile: mindustry.world.Tile? = null

    @Transient private var puddleFlammability: Float = 0f
    @Transient private var damageTimer: Float = arc.math.Mathf.random(40f)
    @Transient private var spreadTimer: Float = arc.math.Mathf.random(spreadDelay)
    @Transient private var fireballTimer: Float = arc.math.Mathf.random(fireballDelay)
    @Transient private var warmup: Float = 0f
    @Transient private var animation: Float = arc.math.Mathf.random(frames - 1)

    override fun update() {
        animation += arc.util.Time.delta / ticksPerFrame
        warmup += arc.util.Time.delta
        animation %= frames

        if (!mindustry.Vars.headless) {
            mindustry.Vars.control.sound.loop(mindustry.content.Sounds.loopFire, this, 0.07f)
        }

        val speedMultiplier = 1f + kotlin.math.max(
            mindustry.Vars.state.envAttrs.get(mindustry.world.meta.Attribute.water) * 10f, 0
        )
        time = arc.math.Mathf.clamp(time + arc.util.Time.delta * speedMultiplier, 0, lifetime)

        if (mindustry.Vars.net.client()) {
            return
        }

        if (time >= lifetime || tile == null || lifetime.isNaN()) {
            remove()
            return
        }

        val entity = tile!!.build
        val damage = entity != null

        val flammability = tile!!.getFlammability() + puddleFlammability

        if (!damage && flammability <= 0) {
            time += arc.util.Time.delta * 8
        }

        if (damage) {
            lifetime += arc.math.Mathf.clamp(flammability / 8f, 0f, 0.6f) * arc.util.Time.delta
        }

        if (flammability > 1f && (spreadTimer += arc.util.Time.delta * arc.math.Mathf.clamp(
                flammability / 5f, 0.3f, 2f
            )) >= spreadDelay
        ) {
            spreadTimer = 0f
            val p = arc.math.geom.Geometry.d4[arc.math.Mathf.random(3)]
            val other = mindustry.Vars.world.tile(tile!!.x + p.x, tile!!.y + p.y)
            mindustry.entities.Fires.create(other)
        }

        if (flammability > 0 && (fireballTimer += arc.util.Time.delta * arc.math.Mathf.clamp(
                flammability / 10f, 0f, 0.5f
            )) >= fireballDelay
        ) {
            fireballTimer = 0f
            mindustry.content.Bullets.fireball.createNet(
                mindustry.game.Team.derelict, x, y, arc.math.Mathf.random(360f), -1f, 1f, 1f
            )
        }

        if ((damageTimer += arc.util.Time.delta) >= damageDelay) {
            damageTimer = 0f
            val p = mindustry.entities.Puddles.get(tile)
            puddleFlammability = if (p != null) p.getFlammability() / 3f else 0f

            if (damage) {
                entity!!.damage(tileDamage)
            }
            mindustry.entities.Damage.damageUnits(
                null,
                tile!!.worldx(),
                tile!!.worldy(),
                mindustry.Vars.tilesize,
                unitDamage,
                { unit -> !unit.isFlying() && !unit.isImmune(mindustry.content.StatusEffects.burning) },
                { unit -> unit.apply(mindustry.content.StatusEffects.burning, 60 * 5) }
            )
        }
    }

    override fun draw() {
        if (regions[0] == null) {
            for (i in 0 until frames) {
                regions[i] = arc.Core.atlas.find("fire$i")
            }
        }

        arc.graphics.g2d.Draw.color(1f, 1f, 1f, arc.math.Mathf.clamp(warmup / warmupDuration))
        arc.graphics.g2d.Draw.z(mindustry.graphics.Layer.effect)
        arc.graphics.g2d.Draw.rect(
            regions[kotlin.math.min(animation.toInt(), regions.size - 1)],
            x + arc.math.Mathf.randomSeedRange(y.toInt(), 2),
            y + arc.math.Mathf.randomSeedRange(x.toInt(), 2)
        )
        arc.graphics.g2d.Draw.reset()

        mindustry.graphics.Drawf.light(
            x, y, 50f + arc.math.Mathf.absin(5f, 5f),
            mindustry.graphics.Pal.lightFlame, 0.6f * arc.math.Mathf.clamp(warmup / warmupDuration)
        )
    }

    @Replace
    override fun clipSize(): Float {
        return 25f
    }

    override fun remove() {
        mindustry.content.Fx.fireRemove.at(x, y, animation)
        mindustry.entities.Fires.remove(tile)
    }

    override fun afterRead() {
        mindustry.entities.Fires.register(self())
    }

    override fun afterSync() {
        mindustry.entities.Fires.register(self())
    }
}