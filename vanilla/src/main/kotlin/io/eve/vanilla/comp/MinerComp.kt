package io.eve.vanilla.comp

import io.eve.ktannot.*
import io.eve.vanilla.gen.*
import kotlin.jvm.Transient
import arc.math.Mathf
import arc.util.Time
import mindustry.content.Fx
import mindustry.content.Blocks
import mindustry.gen.Call
import mindustry.input.InputHandler
import mindustry.type.Item

/**
 * 采矿组件（对标原版 MinerComp）。
 * 管理单位的采矿逻辑:目标矿点、采矿计时、物品转移。
 * 依赖:Itemsc, Posc, Teamc, Rotc, Drawc。
 */
@Component
abstract class MinerComp : Itemsc, Posc, Teamc, Rotc, Drawc {
    @Import var x = 0f
    @Import var y = 0f
    @Import var rotation = 0f
    @Import var hitSize = 0f
    @Import var type: mindustry.type.UnitType? = null

    @Transient var mineTimer = 0f

    // 简化:原版 @SyncLocal Tile mineTile,这里直接用普通字段
    var mineTile: mindustry.world.Tile? = null

    fun canMine(item: Item?): Boolean {
        if (item == null) return false
        return type!!.mineTier >= item.hardness
    }

    fun offloadImmediately(): Boolean {
        return self().isPlayer()
    }

    fun mining(): Boolean {
        return mineTile != null && !self().activelyBuilding()
    }

    fun getMineResult(tile: mindustry.world.Tile?): Item? {
        if (tile == null) return null
        val result: Item?
        if (type!!.mineFloor && tile.block() == Blocks.air) {
            result = tile.drop()
        } else if (type!!.mineWalls) {
            result = tile.wallDrop()
        } else {
            return null
        }

        return if (canMine(result)) result else null
    }

    fun validMine(tile: mindustry.world.Tile?, checkDst: Boolean): Boolean {
        if (tile == null) return false

        if (checkDst && !within(tile.worldx(), tile.worldy(), type!!.mineRange)) {
            return false
        }

        return getMineResult(tile) != null
    }

    fun validMine(tile: mindustry.world.Tile?): Boolean {
        return validMine(tile, true)
    }

    fun canMine(): Boolean {
        return type!!.mineSpeed * mindustry.Vars.state.rules.unitMineSpeed(team()) > 0 && type!!.mineTier >= 0
    }

    override fun update() {
        if (mineTile == null) return

        val core = closestCore()
        val item = getMineResult(mineTile)

        if (core != null && item != null && !acceptsItem(item) && within(core, mineTransferRange) && !offloadImmediately()) {
            val accepted = core.acceptStack(item(), stack().amount, this)
            if (accepted > 0) {
                Call.transferItemTo(
                    self(), item(), accepted,
                    mineTile!!.worldx() + Mathf.range(mindustry.Vars.tilesize / 2f),
                    mineTile!!.worldy() + Mathf.range(mindustry.Vars.tilesize / 2f), core
                )
                clearItem()
            }
        }

        if ((!mindustry.Vars.net.client() || isLocal()) && !validMine(mineTile)) {
            mineTile = null
            mineTimer = 0f
        } else if (mining() && item != null) {
            mineTimer += Time.delta * type!!.mineSpeed * mindustry.Vars.state.rules.unitMineSpeed(team())

            if (Mathf.chance(0.06 * Time.delta)) {
                Fx.pulverizeSmall.at(
                    mineTile!!.worldx() + Mathf.range(mindustry.Vars.tilesize / 2f),
                    mineTile!!.worldy() + Mathf.range(mindustry.Vars.tilesize / 2f), 0f, item.color
                )
            }

            if (mineTimer >= 50f + (if (type!!.mineHardnessScaling) item.hardness * 15f else 15f)) {
                mineTimer = 0f

                if (mindustry.Vars.state.rules.sector != null && team() == mindustry.Vars.state.rules.defaultTeam) {
                    mindustry.Vars.state.rules.sector.info.handleProduction(item, 1)
                }

                if (core != null && within(core, mineTransferRange) && core.acceptStack(item, 1, this) == 1 && offloadImmediately()) {
                    // 在转移前添加物品到库存
                    if (item() == item && !mindustry.Vars.net.client()) addItem(item)
                    Call.transferItemTo(
                        self(), item, 1,
                        mineTile!!.worldx() + Mathf.range(mindustry.Vars.tilesize / 2f),
                        mineTile!!.worldy() + Mathf.range(mindustry.Vars.tilesize / 2f), core
                    )
                } else if (acceptsItem(item)) {
                    // 这是客户端,因为物品无论如何都会同步
                    InputHandler.transferItemToUnit(
                        item,
                        mineTile!!.worldx() + Mathf.range(mindustry.Vars.tilesize / 2f),
                        mineTile!!.worldy() + Mathf.range(mindustry.Vars.tilesize / 2f),
                        this
                    )
                } else {
                    mineTile = null
                    mineTimer = 0f
                }
            }

            if (!mindustry.Vars.headless) {
                mindustry.Vars.control.sound.loop(type!!.mineSound, this, type!!.mineSoundVolume)
            }
        }
    }
}