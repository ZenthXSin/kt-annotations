package io.eve.vanilla.comp

import io.eve.ktannot.*
import io.eve.vanilla.gen.*
import arc.math.Angles
import mindustry.gen.Unit
import mindustry.type.UnitType

@Component
abstract class TankComp : Posc, Rotc, Hitboxc, Unitc {
    @Import var x = 0f
    @Import var y = 0f
    @Import var rotation = 0f
    @Import var type: UnitType? = null
    @Import var team = mindustry.game.Team.derelict
    @Import var hitSize = 0f

    @Transient var treads = 0f
    @Transient var treadsSpeed = 0f
    @Transient var treadProgress = 0f

    override fun update() {
        treadsSpeed = Mathf.lerpDelta(treadsSpeed, if (moving()) 1f else 0f, 0.08f)
        treadProgress += treadsSpeed * deltaLen() * 0.03f
    }
}