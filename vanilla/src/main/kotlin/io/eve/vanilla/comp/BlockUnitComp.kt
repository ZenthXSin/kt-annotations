package io.eve.vanilla.comp

import io.eve.ktannot.*
import io.eve.vanilla.gen.*
import arc.graphics.g2d.TextureRegion
import mindustry.game.Team

@Component
abstract class BlockUnitComp : Unitc {
    @Import var team = Team.derelict
    @Import var maxHealth = 0f
    @Import var health = 0f
    @Import var hitSize = 0f
    @Import var x = 0f
    @Import var y = 0f

    @ReadOnly @Transient var tile: mindustry.gen.Building? = null
    @Transient var ammo = 0f

    fun tile(tile: mindustry.gen.Building) {
        this.tile = tile
        maxHealth = tile.block.health
        health = tile.health
        hitSize = tile.block.size * 8f * 0.7f
        set(tile)
    }

    override fun add() {
        if (tile == null) {
            throw RuntimeException("Do not add BlockUnit entities to the game, they will simply crash. Internal use only.")
        }
    }

    override fun update() {
        if (tile != null) {
            team = tile!!.team
        }
    }

    @Replace
    override fun icon(): TextureRegion {
        return tile!!.block.uiIcon
    }

    @Replace
    override fun ammof(): Float = ammo

    override fun killed() {
        tile!!.kill()
    }

    @Replace
    fun damage(v: Float, b: Boolean) {
        tile!!.damage(v, b)
    }

    @Replace
    fun dead(): Boolean = tile == null || tile!!.dead()

    @Replace
    fun isValid(): Boolean = tile != null && tile!!.isValid()

    @Replace
    fun isAdded(): Boolean = tile != null && tile!!.isValid()

    @Replace
    fun team(team: Team) {
        if (tile != null && this.team != team) {
            this.team = team
            if (tile!!.team != team) {
                tile!!.changeTeam(team)
            }
        }
    }
}