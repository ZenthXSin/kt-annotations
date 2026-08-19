package io.eve.vanilla.comp

import io.eve.ktannot.*
import io.eve.vanilla.gen.*
import arc.math.Angles
import arc.math.Mathf
import arc.util.Time
import kotlin.jvm.Transient
import mindustry.content.Blocks
import mindustry.entities.EntityCollisions
import mindustry.entities.EntityCollisions.SolidPred
import mindustry.game.Team
import mindustry.type.UnitType
import mindustry.world.Tile
import mindustry.world.blocks.environment.Floor

@Component
abstract class CrawlComp : Posc, Rotc, Hitboxc, Unitc {
    @Import var x = 0f
    @Import var y = 0f
    @Import var speedMultiplier = 0f
    @Import var rotation = 0f
    @Import var hitSize = 0f
    @Import var type: UnitType? = null
    @Import var team = Team.derelict

    @Transient var lastDeepFloor: Floor? = null
    @Transient var lastCrawlSlowdown = 1f
    @Transient var segmentRot = 0f
    @Transient var crawlTime: Float = Mathf.random(100f)

    @Replace
    override fun solidity(): SolidPred? {
        return if (ignoreSolids()) null else EntityCollisions::legsSolid
    }

    @Replace
    override fun floorSpeedMultiplier(): Float {
        val on = if (isFlying()) Blocks.air.asFloor() else floorOn()
        return (kotlin.math.pow(if (on.isDeep()) 0.45f else on.speedMultiplier, type!!.floorMultiplier).toFloat()) * speedMultiplier * lastCrawlSlowdown
    }

    override fun add() {
        segmentRot = rotation
    }

    @Replace
    override fun drownFloor(): Floor? = lastDeepFloor

    override fun update() {
        if (moving()) {
            segmentRot = Angles.moveToward(segmentRot, rotation, type!!.segmentRotSpeed * Time.delta)
            val radius = (kotlin.math.max(0.0, (hitSize / 8f * 2f).toDouble())).toInt()
            var count = 0
            var solids = 0
            var deeps = 0
            lastDeepFloor = null

            for (cx in -radius..radius) {
                for (cy in -radius..radius) {
                    if (cx * cx + cy * cy <= radius) {
                        count++
                        val t = mindustry.Vars.world.tileWorld(x + cx * 8f, y + cy * 8f)
                        if (t != null) {
                            if (t.solid()) solids++
                            if (t.floor().isDeep()) {
                                deeps++
                                lastDeepFloor = t.floor()
                            }
                            if (t.build != null && t.build!!.team != team) {
                                t.build!!.damage(team, type!!.crushDamage * Time.delta * mindustry.Vars.state!!.rules!!.unitDamage(team))
                            }
                            if (Mathf.chanceDelta(0.025f)) {
                                mindustry.content.Fx.crawlDust.at(t.worldx(), t.worldy(), t.getFloorColor())
                            }
                        } else {
                            solids++
                        }
                    }
                }
            }

            if (deeps.toFloat() / count < 0.75f) {
                lastDeepFloor = null
            }

            lastCrawlSlowdown = Mathf.lerpDelta(1f, type!!.crawlSlowdown, Mathf.clamp(solids.toFloat() / count / type!!.crawlSlowdownFrac))
        }
        segmentRot = Angles.clampRange(segmentRot, rotation, type!!.segmentMaxRot)
        crawlTime += deltaLen()
    }
}