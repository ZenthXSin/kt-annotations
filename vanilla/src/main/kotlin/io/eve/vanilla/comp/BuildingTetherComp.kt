package io.eve.vanilla.comp

import io.eve.ktannot.*
import io.eve.vanilla.gen.*
import mindustry.game.Team
import mindustry.gen.Building

@Component
abstract class BuildingTetherComp : Unitc {
    @Import var type: mindustry.type.UnitType? = null
    @Import var team = Team.derelict

    var building: Building? = null

    override fun update() {
        if (building == null || !building!!.isValid() || building!!.team != team) {
            mindustry.gen.Call.unitDespawn(self())
        }
    }
}