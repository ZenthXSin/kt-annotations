package io.eve.vanilla.comp

import io.eve.ktannot.*
import io.eve.vanilla.gen.*
import kotlin.jvm.Transient
import mindustry.game.Team
import mindustry.gen.Unit
import mindustry.type.UnitType

/** A unit that depends on a unit's existence; if that unit is removed, it despawns. */
@Component
abstract class UnitTetherComp : Unitc {
    @Import var type: UnitType? = null
    @Import var team = Team.derelict

    //spawner unit cannot be read directly for technical reasons.
    @Transient var spawner: Unit? = null
    var spawnerUnitId = -1

    override fun afterRead() {
        if (spawnerUnitId != -1) spawner = mindustry.gen.Groups.unit.getByID(spawnerUnitId)
        spawnerUnitId = -1
    }

    override fun afterSync() {
        if (spawnerUnitId != -1) spawner = mindustry.gen.Groups.unit.getByID(spawnerUnitId)
        spawnerUnitId = -1
    }

    override fun update() {
        if (spawner == null || !spawner!!.isValid() || spawner!!.team != team) {
            mindustry.gen.Call.unitDespawn(self())
        } else {
            spawnerUnitId = spawner!!.id
        }
    }
}