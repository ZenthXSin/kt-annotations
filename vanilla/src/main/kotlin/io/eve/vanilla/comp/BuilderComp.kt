package io.eve.vanilla.comp

import io.eve.ktannot.*
import io.eve.vanilla.gen.*
import kotlin.jvm.Transient
import arc.graphics.Color
import arc.graphics.g2d.Draw
import arc.graphics.g2d.Fill
import arc.graphics.g2d.Lines
import arc.math.Mathf
import arc.math.Angles
import arc.math.geom.Vec2
import arc.struct.Seq
import arc.util.Time
import mindustry.content.Blocks
import mindustry.content.Fx
import mindustry.content.Sounds
import mindustry.entities.units.BuildPlan
import mindustry.game.EventType.BuildSelectEvent
import mindustry.game.Team
import mindustry.gen.Building
import mindustry.gen.Buildingc
import mindustry.gen.Call
import mindustry.gen.Unit
import mindustry.graphics.Layer
import mindustry.graphics.Pal
import mindustry.graphics.Drawf
import mindustry.type.UnitType
import mindustry.world.Build
import mindustry.world.Tile
import mindustry.world.blocks.ConstructBlock
import mindustry.world.blocks.ConstructBlock.ConstructBuild
import mindustry.gen.Entityc

/**
 * 建造组件（对标原版 BuilderComp）。
 * 管理单位的建造队列、建造逻辑、光束渲染。
 * 依赖:Posc, Statusc, Teamc, Rotc。
 */
@Component
abstract class BuilderComp : Posc, Statusc, Teamc, Rotc {
    @Import var x = 0f
    @Import var y = 0f
    @Import var rotation = 0f
    @Import var buildSpeedMultiplier = 1f
    @Import var type: UnitType? = null
    @Import var team = Team.derelict

    var plans = Seq<*>()
    var updateBuilding = true

    @Transient private var buildCounter = 0f
    @Transient private var lastActive: BuildPlan? = null
    @Transient private var lastSize = 0
    @Transient var buildAlpha = 0f

    fun canBuild(): Boolean {
        return type!!.buildSpeed > 0 && buildSpeedMultiplier > 0
    }

    override fun update() {
        updateBuildLogic()
    }

    override fun afterRead() {
        if (plans == null || plans === null) {
            plans = Seq<*>()
        }
    }

    fun validatePlans() {
        if (plans.size > 0) {
            val it = plans.iterator()
            while (it.hasNext()) {
                val plan = it.next()
                val tile = mindustry.Vars.world.tile(plan.x, plan.y)
                val isSameDerelict = (tile != null && tile.build != null && tile.block() == plan.block && tile.build.tileX() == plan.x && tile.build.tileY() == plan.y && tile.team() == Team.derelict)
                if (tile == null || (plan.breaking && tile.block() == Blocks.air) || (!plan.breaking && ((tile.build != null && tile.build.rotation == plan.rotation && !isSameDerelict) || !plan.block.rotate) &&
                    ((tile.block() == plan.block && !isSameDerelict) ||
                        (plan.block != null && (plan.block.isOverlay() && plan.block == tile.overlay() || (plan.block.isFloor() && plan.block == tile.floor())))))) {
                    it.remove()
                }
            }
        }
    }

    fun updateBuildLogic() {
        if (type!!.buildSpeed <= 0f) return

        if (!mindustry.Vars.headless) {
            if (lastActive != null && buildAlpha <= 0.01f) {
                lastActive = null
            }
            buildAlpha = Mathf.lerpDelta(buildAlpha, if (activelyBuilding()) 1f else 0f, 0.15f)
        }

        validatePlans()

        if (!updateBuilding || !canBuild()) return

        val finalPlaceDst = if (mindustry.Vars.state.rules.infiniteResources) Float.MAX_VALUE else type!!.buildRange
        val infinite = mindustry.Vars.state.rules.infiniteResources || team().rules().infiniteResources

        buildCounter += Time.delta
        if (buildCounter.isNaN() || buildCounter.isInfinite()) buildCounter = 0f
        buildCounter = kotlin.math.min(buildCounter, 10f)

        val instant = mindustry.Vars.state.rules.instantBuild && mindustry.Vars.state.rules.infiniteResources

        val maxPerFrame = if (instant) plans.size else 10
        var count = 0

        var core = core()

        if ((core == null && !infinite)) return

        while ((buildCounter >= 1 || instant) && count++ < maxPerFrame && plans.size > 0) {
            buildCounter -= 1f

            if (plans.size > 1) {
                var total = 0
                val size = plans.size
                var bestDst = Float.MAX_VALUE
                var foundAny = false
                var bestIndex = -1
                while (total < size) {
                    val plan = buildPlan()

                    val dst = plan.dst2(this)
                    val within = dst <= finalPlaceDst * finalPlaceDst
                    if (within && !shouldSkip(plan, core)) {
                        foundAny = true
                        break
                    } else if (within && dst < bestDst) {
                        bestIndex = total
                        bestDst = dst
                    }

                    plans.removeFirst()
                    plans.addLast(plan)
                    total++
                }

                if (!foundAny && bestIndex > 0 && !within(buildPlan(), finalPlaceDst)) {
                    for (i in 0 until bestIndex) {
                        plans.addLast(plans.removeFirst())
                    }
                }
            }

            val current = buildPlan()
            val tile = current.tile()

            lastActive = current
            buildAlpha = 1f
            if (current.breaking) lastSize = tile.block().size

            if (!within(tile, finalPlaceDst)) continue

            if (!mindustry.Vars.headless) {
                mindustry.Vars.control.sound.loop(Sounds.loopBuild, tile, 1.3f)
            }

            val allowBuildCurrent = current.block != null && (mindustry.Vars.state.isEditor() || (mindustry.Vars.state.rules.waves && team == mindustry.Vars.state.rules.waveTeam && current.block.isVisible()) || (current.block.unlockedNowHost() && current.block.environmentBuildable() && current.block.isPlaceable()))

            if (tile.build !is ConstructBuild) {
                if (!current.initialized && !current.breaking && Build.validPlaceIgnoreUnits(current.block, team, current.x, current.y, current.rotation, true, true) && allowBuildCurrent) {
                    if (Build.checkNoUnitOverlap(current.block, current.x, current.y)) {
                        val hasAll = infinite || current.isRotation(team) ||
                            (tile.team() == Team.derelict && tile.block() == current.block && tile.build != null && tile.block().allowDerelictRepair && mindustry.Vars.state.rules.derelictRepair) ||
                            !mindustry.gen.Structs.contains(current.block.requirements, { i -> !core!!.items.has(i.item, kotlin.math.min(Mathf.round(i.amount * mindustry.Vars.state.rules.buildCostMultiplier), 1)) })

                        if (hasAll) {
                            Call.beginPlace(self(), current.block, team, current.x, current.y, current.rotation, if (current.block.instantBuild) current.config else null)

                            if (!mindustry.Vars.net.client() && current.block.instantBuild) {
                                if (plans.size > 0) {
                                    plans.removeFirst()
                                }
                                continue
                            }
                        } else {
                            current.stuck = true
                        }
                    } else {
                        plans.removeFirst()
                        plans.addLast(current)
                        continue
                    }
                } else if (!current.initialized && current.breaking && Build.validBreak(team, current.x, current.y)) {
                    Call.beginBreak(self(), team, current.x, current.y)
                } else {
                    plans.removeFirst()
                    continue
                }
            } else if ((tile.team() != team && tile.team() != Team.derelict) || (!current.breaking && (tile.build as ConstructBuild).current != current.block || (tile.build as ConstructBuild).tile != current.tile())) {
                plans.removeFirst()
                continue
            }

            if (tile.build is ConstructBuild && !current.initialized) {
                mindustry.game.Events.fire(BuildSelectEvent(tile, team, self(), current.breaking))
                current.initialized = true
            }

            if (tile.build !is ConstructBuild entity) {
                continue
            }

            val bs = 1f / entity.buildCost * type!!.buildSpeed * buildSpeedMultiplier * mindustry.Vars.state.rules.buildSpeed(team)

            if (current.breaking) {
                entity.deconstruct(self(), core, bs)
            } else if (allowBuildCurrent) {
                entity.construct(self(), core, bs, current.config)
            }

            current.stuck = Mathf.equal(current.progress, entity.progress)
            current.progress = entity.progress
        }
    }

    fun drawPlan(plan: BuildPlan, alpha: Float) {
        plan.animScale = 1f
        if (plan.breaking) {
            mindustry.Vars.control.input.drawBreaking(plan)
        } else {
            plan.block.drawPlan(plan, mindustry.Vars.control.input.allPlans(),
                Build.validPlace(plan.block, team, plan.x, plan.y, plan.rotation) || mindustry.Vars.control.input.planMatches(plan),
                alpha)
        }
    }

    fun drawPlanTop(plan: BuildPlan, alpha: Float) {
        if (!plan.breaking) {
            Draw.reset()
            Draw.mixcol(Color.white, 0.24f + Mathf.absin(Time.globalTime, 6f, 0.28f))
            Draw.alpha(alpha)
            plan.block.drawPlanConfigTop(plan, plans)
        }
    }

    fun shouldSkip(plan: BuildPlan, core: Building?): Boolean {
        if (mindustry.Vars.state.rules.infiniteResources || team.rules().infiniteResources || plan.breaking || core == null || plan.isRotation(team) || plan.isDerelictRepair()) return false
        return (plan.stuck && !core.items.has(plan.block.requirements)) ||
            (mindustry.gen.Structs.contains(plan.block.requirements, { i -> !core.items.has(i.item, kotlin.math.min(i.amount.toFloat(), 15f).toInt()) && Mathf.round(i.amount * mindustry.Vars.state.rules.buildCostMultiplier) > 0 }))
    }

    fun removeBuild(x: Int, y: Int, breaking: Boolean) {
        val idx = plans.indexOf { req -> req.breaking == breaking && req.x == x && req.y == y }
        if (idx != -1) {
            plans.removeIndex(idx)
        }
    }

    fun isBuilding(): Boolean {
        return plans.size != 0
    }

    fun clearBuilding() {
        plans.clear()
    }

    fun addBuild(place: BuildPlan) {
        addBuild(place, true)
    }

    fun addBuild(place: BuildPlan, tail: Boolean) {
        if (!canBuild()) return

        var replace: BuildPlan? = null
        for (plan in plans) {
            if (plan.x == place.x && plan.y == place.y) {
                replace = plan
                break
            }
        }
        if (replace != null) {
            plans.remove(replace)
        }
        val tile = mindustry.Vars.world.tile(place.x, place.y)
        if (tile != null && tile.build is ConstructBuild) {
            place.progress = (tile.build as ConstructBuild).progress
        }
        if (tail) {
            plans.addLast(place)
        } else {
            plans.addFirst(place)
        }
    }

    fun activelyBuilding(): Boolean {
        if (isBuilding()) {
            val plan = buildPlan()
            if (!mindustry.Vars.state.isEditor() && plan != null && !within(plan, if (mindustry.Vars.state.rules.infiniteResources) Float.MAX_VALUE else type!!.buildRange)) {
                return false
            }
        }
        return isBuilding() && updateBuilding
    }

    fun buildPlan(): BuildPlan? {
        return if (plans.size == 0) null else plans.first()
    }

    fun drawBuilding() {
        val active = activelyBuilding()
        if (!active && lastActive == null) return

        Draw.z(Layer.flyingUnit)

        val plan = if (active) buildPlan() else lastActive
        val tile = plan!!.tile()
        val core = team.core()

        if (tile == null || !within(plan, if (mindustry.Vars.state.rules.infiniteResources) Float.MAX_VALUE else type!!.buildRange)) {
            return
        }

        if (core != null && active && !isLocal() && !(tile.block() is ConstructBlock) && !mindustry.Vars.state.isPaused()) {
            Draw.z(Layer.plans - 1f)
            drawPlan(plan, 0.5f)
            drawPlanTop(plan, 0.5f)
            Draw.z(Layer.flyingUnit)
        }

        if (type!!.drawBuildBeam) {
            val focusLen = type!!.buildBeamOffset + Mathf.absin(Time.time, 3f, 0.6f)
            val px = x + Angles.trnsx(rotation, focusLen)
            val py = y + Angles.trnsy(rotation, focusLen)

            drawBuildingBeam(px, py)
        }
    }

    fun drawBuildingBeam(px: Float, py: Float) {
        val active = activelyBuilding()
        if (!active && lastActive == null) return

        Draw.z(Layer.flyingUnit)

        val plan = if (active) buildPlan() else lastActive
        val tile = mindustry.Vars.world.tile(plan!!.x, plan.y)

        if (tile == null || !within(plan, if (mindustry.Vars.state.rules.infiniteResources) Float.MAX_VALUE else type!!.buildRange)) {
            return
        }

        val size = if (plan.breaking) (if (active) tile.block().size else lastSize) else plan.block.size
        val tx = plan.drawx()
        val ty = plan.drawy()

        Lines.stroke(1f, if (plan.breaking) Pal.remove else Pal.accent)
        Draw.z(Layer.buildBeam)

        Draw.alpha(buildAlpha)

        if (!active && tile.build !is ConstructBuild) {
            Fill.square(plan.drawx(), plan.drawy(), size * mindustry.Vars.tilesize / 2f)
        }

        Drawf.buildBeam(px, py, tx, ty, mindustry.Vars.tilesize * size / 2f)

        Fill.square(px, py, 1.8f + Mathf.absin(Time.time, 2.2f, 1.1f), rotation + 45f)

        Draw.reset()
        Draw.z(Layer.flyingUnit)
    }
}