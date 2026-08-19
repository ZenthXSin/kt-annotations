package io.eve.vanilla.comp

import io.eve.ktannot.*
import io.eve.vanilla.gen.*
import mindustry.world.blocks.power.PowerGraph

@Component
abstract class PowerGraphUpdaterComp : Entityc {
    @Transient var graph = PowerGraph()

    override fun update() {
        graph.update()
    }
}