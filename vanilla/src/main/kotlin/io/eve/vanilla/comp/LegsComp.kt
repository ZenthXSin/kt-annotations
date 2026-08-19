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
abstract class LegsComp : Posc, Rotc, Hitboxc, Unitc {
    @Import var x = 0f
    @Import var y = 0f
    @Import var rotation = 0f
    @Import var type: UnitType? = null
    @Import var team = Team.derelict
    @Import var hitSize = 0f

    @Transient var legMoveSpace = 0f
    @Transient var legOffset = 0f
    @Transient var legLength = 0f
    @Transient var legSwingTime = 0f
    @Transient var legGroup = 0
    @Transient var baseRotation = 0f
    @Transient var legCount = 0
    @Transient var flipBool = false
    @Transient var walkExtend = 0f
    @Transient var walkScl = 1f
    @Transient var walkTime = 0f

    override fun add() {
        baseRotation = rotation
    }

    override fun update() {
        // Simplified leg movement logic
        walkTime += deltaLen() * walkScl
        if (moving()) {
            val rot = Angles.angle(x, y, lastX, lastY)
            baseRotation = Angles.moveToward(baseRotation, rot, type!!.legSwingSpeed * Time.delta)
        }
    }
}