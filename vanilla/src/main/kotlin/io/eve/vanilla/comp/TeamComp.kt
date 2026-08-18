package io.eve.vanilla.comp

import io.eve.ktannot.*

import io.eve.vanilla.gen.*
import mindustry.game.Team
import mindustry.world.blocks.storage.CoreBlock.CoreBuild

@Component
abstract class TeamComp : Posc {
    @Import var x = 0f
    @Import var y = 0f

    var team = Team.derelict

    fun cheating(): Boolean {
        return team.rules()!!.cheat
    }

    fun inFogTo(viewer: Team): Boolean {
        return this.team != viewer && !mindustry.Vars.fogControl.isVisible(viewer, x, y)
    }

    fun core(): CoreBuild? {
        return team.core()
    }

    fun closestCore(): CoreBuild? {
        return mindustry.Vars.state!!.teams!!.closestCore(x, y, team)
    }

    fun closestEnemyCore(): CoreBuild? {
        return mindustry.Vars.state!!.teams!!.closestEnemyCore(x, y, team)
    }
}