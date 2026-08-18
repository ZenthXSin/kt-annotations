package io.eve.vanilla.comp

import io.eve.ktannot.*
import arc.math.geom.*

@Component
abstract class PosComp {
    @SyncField(true) @SyncLocal var x = 0f
    @SyncField(true) @SyncLocal var y = 0f

    fun getX(): Float = x
    fun getY(): Float = y

    fun set(x: Float, y: Float) {
        this.x = x
        this.y = y
    }

    fun set(pos: Position) {
        set(pos.getX(), pos.getY())
    }

    fun trns(x: Float, y: Float) {
        set(this.x + x, this.y + y)
    }

    fun trns(pos: Position) {
        trns(pos.getX(), pos.getY())
    }

    fun tileX(): Int = mindustry.core.World.toTile(x)

    fun tileY(): Int = mindustry.core.World.toTile(y)

    fun floorOn(): mindustry.world.blocks.environment.Floor {
        val tile = tileOn()
        return if (tile == null || tile.block() != mindustry.content.Blocks.air) {
            mindustry.content.Blocks.air as mindustry.world.blocks.environment.Floor
        } else {
            tile.floor()
        }
    }

    fun blockOn(): mindustry.world.Block {
        val tile = tileOn()
        return if (tile == null) mindustry.content.Blocks.air else tile.block()
    }

    fun buildOn(): mindustry.gen.Building? {
        return mindustry.Vars.world.buildWorld(x, y)
    }

    fun tileOn(): mindustry.world.Tile? {
        return mindustry.Vars.world.tileWorld(x, y)
    }

    fun onSolid(): Boolean {
        val tile = tileOn()
        return tile == null || tile.solid()
    }
}