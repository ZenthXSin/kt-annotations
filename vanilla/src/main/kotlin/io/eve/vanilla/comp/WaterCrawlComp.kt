package io.eve.vanilla.comp

import io.eve.ktannot.*
import io.eve.vanilla.gen.*
import mindustry.entities.EntityCollisions
import mindustry.entities.EntityCollisions.SolidPred

@Component
abstract class WaterCrawlComp : Posc, Rotc, Hitboxc, Unitc {
    @Import var x = 0f
    @Import var y = 0f

    @Replace
    override fun solidity(): SolidPred? = null
}