package io.eve.vanilla.comp

import io.eve.ktannot.*
import io.eve.vanilla.gen.*
import mindustry.entities.EntityCollisions
import mindustry.entities.EntityCollisions.SolidPred

@Component
abstract class ElevationMoveComp : Velc, Posc, Hitboxc, Unitc {
    @Import var x = 0f
    @Import var y = 0f

    @Replace
    override fun solidity(): SolidPred? {
        return if (isFlying() || ignoreSolids()) null else EntityCollisions::solid
    }
}