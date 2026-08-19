package io.eve.vanilla.comp

import io.eve.ktannot.*
import io.eve.vanilla.gen.*
import arc.math.Angles
import arc.math.Mathf
import arc.util.Time
import kotlin.jvm.Transient
import mindustry.game.Team
import mindustry.type.UnitType

@Component
abstract class MechComp : Posc, Rotc, Hitboxc, Unitc {
    @Import var x = 0f
    @Import var y = 0f
    @Import var rotation = 0f
    @Import var type: UnitType? = null
    @Import var team = Team.derelict
    @Import var hitSize = 0f

    @Transient var baseRotation = 0f
    @Transient var walkExtend = 0f
    @Transient var walkScl = 1f
    @Transient var walkTime = 0f

    override fun add() {
        baseRotation = rotation
    }

    override fun update() {
        walkTime += deltaLen() * walkScl
        if (moving()) {
            val rot = Angles.angle(x, y, lastX, lastY)
            baseRotation = Angles.moveToward(baseRotation, rot, type!!.mechSwingSpeed * Time.delta)
        }
    }
}