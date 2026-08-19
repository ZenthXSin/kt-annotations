package io.eve.vanilla.comp

import io.eve.ktannot.*
import io.eve.vanilla.gen.*
import kotlin.jvm.Transient
import arc.graphics.Color
import arc.graphics.g2d.TextureRegion
import arc.math.Angles
import arc.math.Mathf
import arc.math.geom.Position
import arc.math.geom.Vec2
import arc.scene.ui.layout.Table
import arc.util.Time
import arc.util.Tmp
import mindustry.ai.types.AIController
import mindustry.ai.types.CommandAI
import mindustry.ai.types.LogicAI
import mindustry.content.Blocks
import mindustry.content.Fx
import mindustry.content.Sounds
import mindustry.core.World
import mindustry.entities.Damage
import mindustry.entities.Effect
import mindustry.entities.Units
import mindustry.entities.abilities.Ability
import mindustry.entities.abilities.EmptyDataAbility
import mindustry.entities.units.WeaponMount
import mindustry.entities.units.UnitController
import mindustry.game.EventType
import mindustry.game.EventType.UnitDestroyEvent
import mindustry.game.EventType.UnitDrownEvent
import mindustry.game.Team
import mindustry.game.Trigger
import mindustry.gen.Building
import mindustry.gen.Bullet
import mindustry.gen.Call
import mindustry.gen.Events
import mindustry.gen.Hitboxc
import mindustry.gen.Player
import mindustry.gen.Payloadc
import mindustry.gen.Unit
import mindustry.gen.Unitc
import mindustry.logic.LAccess
import mindustry.logic.Ranged
import mindustry.logic.Senseable
import mindustry.logic.Settable
import mindustry.logic.GlobalVars
import mindustry.type.Item
import mindustry.type.ItemStack
import mindustry.type.UnitType
import mindustry.ui.Displayable
import mindustry.world.Tile
import mindustry.world.blocks.environment.Floor
import mindustry.world.blocks.payloads.BuildPayload
import mindustry.world.blocks.payloads.UnitPayload
import mindustry.world.blocks.defense.ExplosionShield
import mindustry.world.meta.BlockFlag
import mindustry.game.EventType as EventTypeAlias
import mindustry.type.ItemStack as ItemStackAlias

/**
 * 单位组件（对标原版 UnitComp）。
 * 核心单位组件:控制器、移动、瞄准、感应、建造/采矿、死亡/摧毁、渲染。
 * 依赖:Healthc, Physicsc, Hitboxc, Statusc, Teamc, Itemsc, Rotc, Unitc, Weaponsc, Drawc, Syncc, Shieldc, Displayable, Ranged, Minerc, Builderc, Senseable, Settable。
 */
@Component
abstract class UnitComp : Healthc, Physicsc, Hitboxc, Statusc, Teamc, Itemsc, Rotc, Unitc, Weaponsc, Drawc, Syncc, Shieldc, Displayable, Ranged, Minerc, Builderc, Senseable, Settable {

    companion object {
        private val tmp1 = Vec2()
        private val tmp2 = Vec2()
        const val warpDst = 8f
    }

    @Import var dead = false
    @Import var disarmed = false
    @Import var x = 0f
    @Import var y = 0f
    @Import var rotation = 0f
    @Import var maxHealth = 1f
    @Import var drag = 0f
    @Import var armor = 0f
    @Import var hitSize = 0f
    @Import var health = 0f
    @Import var shield = 0f
    @Import var dragMultiplier = 1f
    @Import var armorOverride = -1f
    @Import var speedMultiplier = 1f
    @Import var team = Team.derelict
    @Import var id = 0
    @Import var mineTile: Tile? = null
    @Import var vel = Vec2()
    @Import var mounts: Array<WeaponMount> = arrayOf()
    @Import var stack = ItemStack()

    private var controller: UnitController? = null
    var abilities: Array<Ability> = arrayOf()
    var type: UnitType = UnitTypes.alpha
    var spawnedByCore = false
    var flag = 0.0

    @Transient var trail: Trail? = null
    //TODO 可以更好地表示为一个单位
    @Transient var dockedType: UnitType? = null

    @Transient var lastCommanded: String? = null
    @Transient var shadowAlpha = -1f
    @Transient var healTime = 0f
    @Transient var lastFogPos = 0
    /** 仅用于自杀单位 */
    @Transient var hasTarget = false
    @Transient private var resupplyTime = Mathf.random(10f)
    @Transient private var wasPlayer = false
    @Transient private var wasHealed = false

    @SyncLocal var elevation = 0f
    @Transient private var wasFlying = false
    @Transient var drownTime = 0f
    @Transient var splashTimer = 0f
    @Transient var lastDrownFloor: Floor? = null

    fun checkTarget(targetAir: Boolean, targetGround: Boolean): Boolean {
        return (isGrounded() && targetGround) || (isFlying() && targetAir)
    }

    fun isGrounded(): Boolean {
        return elevation < 0.001f
    }

    fun isFlying(): Boolean {
        return elevation >= 0.09f
    }

    fun canDrown(): Boolean {
        return isGrounded() && type.canDrown
    }

    fun drownFloor(): Floor? {
        return floorOn()
    }

    fun wobble() {
        x += Mathf.sin(Time.time + (id % 10) * 12, 25f, 0.05f) * Time.delta * elevation
        y += Mathf.cos(Time.time + (id % 10) * 12, 25f, 0.05f) * Time.delta * elevation
    }

    fun moveAt(vector: Vec2, acceleration: Float) {
        val t = tmp1.set(vector) //目标向量
        tmp2.set(t).sub(vel).limit(acceleration * vector.len() * Time.delta) //增量向量
        vel.add(tmp2)
    }

    fun floorSpeedMultiplier(): Float {
        val on: Floor = if (isFlying() || type.hovering) Blocks.air.asFloor() else floorOn()
        return Math.pow(on.speedMultiplier.toDouble(), type.floorMultiplier.toDouble()).toFloat() * speedMultiplier
    }

    /** 当此单位从工厂或出生点卸载时调用。 */
    fun unloaded() {
    }

    fun updateBoosting(boost: Boolean) {
        updateBoosting(boost, false)
    }

    fun updateBoosting(boost: Boolean, event: Boolean) {
        if (!type.canBoost || dead) return

        val shouldBoost = boost || onSolid() || (isFlying() && !canLand())
        elevation = Mathf.approachDelta(elevation, if (type.canBoost) Mathf.num(shouldBoost) else 0f, if (shouldBoost) type.riseSpeed else type.descentSpeed)
        if (event) {
            Events.fire(Trigger.unitCommandBoost)
        }
    }

    /** 基于首选单位移动类型移动。 */
    fun movePref(movement: Vec2) {
        if (type.omniMovement) {
            moveAt(movement)
        } else {
            rotateMove(movement)
        }
    }

    fun moveAt(vector: Vec2) {
        moveAt(vector, type.accel)
    }

    fun approach(vector: Vec2) {
        vel.approachDelta(vector, type.accel * speed())
    }

    fun rotateMove(vec: Vec2) {
        moveAt(Tmp.v2.trns(rotation, vec.len()))

        if (!vec.isZero()) {
            rotation = Angles.moveToward(rotation, vec.angle(), type.rotateSpeed * Time.delta * speedMultiplier)
        }
    }

    fun aimLook(pos: Position) {
        aim(pos)
        lookAt(pos)
    }

    fun aimLook(x: Float, y: Float) {
        aim(x, y)
        lookAt(x, y)
    }

    fun isPathImpassable(tileX: Int, tileY: Int): Boolean {
        return !type.flying && mindustry.Vars.world.tiles.in(tileX, tileY) && type.pathCost.getCost(team.id, mindustry.Vars.pathfinder.get(tileX, tileY)) == -1
    }

    /** @return 物理的近似方形碰撞箱尺寸 */
    fun physicSize(): Float {
        return hitSize * 0.7f
    }

    /** @return 此单位下方是否有实心的、未被占用的地面 */
    fun canLand(): Boolean {
        return !onSolid() && Units.count(x, y, physicSize()) { f -> f != self() && f.isGrounded() } == 0
    }

    fun inRange(other: Position): Boolean {
        return within(other, type.range)
    }

    fun hasWeapons(): Boolean {
        return type.hasWeapons()
    }

    /** @return 考虑提升和地形倍率的速度。 */
    fun speed(): Float {
        val strafePenalty = if (isGrounded() || !isPlayer()) 1f else Mathf.lerp(1f, type.strafePenalty, Angles.angleDist(vel().angle(), rotation) / 180f)
        val boost = Mathf.lerp(1f, if (type.canBoost) type.boostMultiplier else 1f, elevation)
        return type.speed * strafePenalty * boost * floorSpeedMultiplier()
    }

    /** @return 单位想要看向的位置。 */
    fun prefRotation(): Float {
        if (activelyBuilding() && type.rotateToBuilding) {
            return angleTo(buildPlan())
        } else if (mineTile != null) {
            return angleTo(mineTile)
        } else if (moving() && type.omniMovement) {
            return vel().angle()
        }
        return rotation
    }

    fun ammof(): Float {
        return 1f
    }

    override fun displayable(): Boolean {
        return type.hoverable
    }

    @Replace
    override fun isSyncHidden(team: Team): Boolean {
        //射击会暴露位置,以便看到子弹
        return !isShooting() && inFogTo(team)
    }

    override fun handleSyncHidden() {
        remove()
        mindustry.Vars.netClient.clearRemovedEntity(id)
    }

    @Replace
    override fun inFogTo(viewer: Team): Boolean {
        if (this.team == viewer || !mindustry.Vars.state.rules.fog) return false

        if (hitSize <= 16f) {
            return !mindustry.Vars.fogControl.isVisible(viewer, x, y)
        } else {
            //对于大的 hitSize,检查单位周围
            val trns = hitSize / 2f
            for (p in Geometry.d8) {
                if (mindustry.Vars.fogControl.isVisible(viewer, x + p.x * trns, y + p.y * trns)) {
                    return false
                }
            }
        }

        return true
    }

    override fun range(): Float {
        return type.maxRange
    }

    @Replace
    fun clipSize(): Float {
        if (isBuilding()) {
            return if (mindustry.Vars.state.rules.infiniteResources) Float.MAX_VALUE else Math.max(type.clipSize, type.region.width) + type.buildRange + tilesize * 4f
        }
        if (mining()) {
            return type.clipSize + type.mineRange
        }
        return type.clipSize
    }

    override fun sense(sensor: LAccess): Double {
        return when (sensor) {
            LAccess.totalItems -> stack().amount.toDouble()
            LAccess.itemCapacity -> type.itemCapacity.toDouble()
            LAccess.rotation -> rotation.toDouble()
            LAccess.health -> health.toDouble()
            LAccess.shield -> shield.toDouble()
            LAccess.maxHealth -> maxHealth.toDouble()
            LAccess.flying -> if (isFlying()) 1.0 else 0.0
            LAccess.x -> World.conv(x).toDouble()
            LAccess.y -> World.conv(y).toDouble()
            LAccess.velocityX -> (vel.x * 60f / mindustry.Vars.tilesize).toDouble()
            LAccess.velocityY -> (vel.y * 60f / mindustry.Vars.tilesize).toDouble()
            LAccess.dead -> if (dead || !isAdded()) 1 else 0
            LAccess.team -> team.id.toDouble()
            LAccess.shooting -> if (isShooting()) 1 else 0
            LAccess.boosting -> if (type.canBoost && isFlying()) 1 else 0
            LAccess.range -> (range() / mindustry.Vars.tilesize).toDouble()
            LAccess.shootX -> World.conv(aimX()).toDouble()
            LAccess.shootY -> World.conv(aimY()).toDouble()
            LAccess.cameraX -> if (controller is Player) {
                val player = controller as Player
                World.conv(if (player.con == null) arc.Core.camera.position.x else player.con.viewX).toDouble()
            } else 0.0
            LAccess.cameraY -> if (controller is Player) {
                val player = controller as Player
                World.conv(if (player.con == null) arc.Core.camera.position.y else player.con.viewY).toDouble()
            } else 0.0
            LAccess.cameraWidth -> if (controller is Player) {
                val player = controller as Player
                World.conv(if (player.con == null) arc.Core.camera.width else player.con.viewWidth).toDouble()
            } else 0.0
            LAccess.cameraHeight -> if (controller is Player) {
                val player = controller as Player
                World.conv(if (player.con == null) arc.Core.camera.height else player.con.viewHeight).toDouble()
            } else 0.0
            LAccess.mining -> if (mining()) 1 else 0
            LAccess.mineX -> if (mining()) mineTile!!.x.toDouble() else -1.0
            LAccess.mineY -> if (mining()) mineTile!!.y.toDouble() else -1.0
            LAccess.buildX -> if (isBuilding()) buildPlan()!!.x.toDouble() else -1.0
            LAccess.buildY -> if (isBuilding()) buildPlan()!!.y.toDouble() else -1.0
            LAccess.armor -> if (armorOverride >= 0f) armorOverride.toDouble() else armor.toDouble()
            LAccess.flag -> flag
            LAccess.speed -> (type.speed * 60f / mindustry.Vars.tilesize * speedMultiplier).toDouble()
            LAccess.controlled -> {
                if (!isValid()) 0.0
                else when {
                    controller is LogicAI -> mindustry.logic.GlobalVars.ctrlProcessor.toDouble()
                    controller is Player -> mindustry.logic.GlobalVars.ctrlPlayer.toDouble()
                    controller is CommandAI && (controller as CommandAI).hasCommand() -> mindustry.logic.GlobalVars.ctrlCommand.toDouble()
                    else -> 0.0
                }
            }
            LAccess.payloadCount -> {
                val pay = (this as? Any) as? Payloadc
                pay?.payloads()?.size?.toDouble() ?: 0.0
            }
            LAccess.totalPayload -> {
                val pay = (this as? Any) as? Payloadc
                (pay?.payloadUsed()?.div(mindustry.Vars.tilesize * mindustry.Vars.tilesize.toFloat()))?.toDouble() ?: 0.0
            }
            LAccess.payloadCapacity -> (type.payloadCapacity / mindustry.logic.GlobalVars.tilePayload).toDouble()
            LAccess.size -> (hitSize / mindustry.Vars.tilesize).toDouble()
            LAccess.color -> Color.toDoubleBits(team.color.r, team.color.g, team.color.b, 1f).toDouble()
            LAccess.selectedRotation -> if (controller is Player) (controller as Player).selectedRotation.toDouble() else 0.0
            LAccess.pingX -> if (controller is Player && (controller as Player).isPinging()) World.conv((controller as Player).pingX).toDouble() else Double.NaN
            LAccess.pingY -> if (controller is Player && (controller as Player).isPinging()) World.conv((controller as Player).pingY).toDouble() else Double.NaN
            else -> Double.NaN
        }
    }

    override fun senseObject(sensor: LAccess): Any? {
        return when (sensor) {
            LAccess.type -> type
            LAccess.name -> if (controller is Player) (controller as Player).name else null
            LAccess.firstItem -> if (stack().amount == 0) null else item()
            LAccess.controller -> {
                if (!isValid()) null
                else if (controller is LogicAI) (controller as LogicAI).controller
                else this
            }
            LAccess.payloadType -> {
                val pay = (this as? Any) as? Payloadc ?: return null
                if (pay.payloads().isEmpty()) null
                else {
                    val peek = pay.payloads().peek()
                    when (peek) {
                        is UnitPayload -> peek.unit.type
                        is BuildPayload -> peek.block()
                        else -> null
                    }
                }
            }
            LAccess.building -> if (isBuilding() && !buildPlan()!!.breaking) buildPlan()!!.tile().build else null
            LAccess.breaking -> if (isBuilding() && buildPlan()!!.breaking) buildPlan()!!.tile().build else null
            LAccess.selectedBlock -> if (controller is Player) (controller as Player).selectedBlock else null
            LAccess.pingText -> if (controller is Player && (controller as Player).isPinging()) (controller as Player).pingText else null
            else -> mindustry.logic.GlobalVars.noSensed
        }
    }

    override fun sense(content: Content): Double {
        if (content == stack().item) return stack().amount.toDouble()
        if (content is UnitType) {
            val pay = (this as? Any) as? Payloadc ?: return 0.0
            return if (pay.payloads().isEmpty()) 0.0
            else pay.payloads().count { p -> p is UnitPayload && p.unit.type == content }.toDouble()
        }
        if (content is Block) {
            val pay = (this as? Any) as? Payloadc ?: return 0.0
            return if (pay.payloads().isEmpty()) 0.0
            else pay.payloads().count { p -> p is BuildPayload && p.build.block == content }.toDouble()
        }
        return Double.NaN
    }

    override fun setProp(prop: LAccess, value: Double) {
        when (prop) {
            LAccess.health -> {
                health = Mathf.clamp(value.toFloat(), 0f, maxHealth)
                if (health <= 0f && !dead) {
                    kill()
                }
            }
            LAccess.shield -> shield = Math.max(value.toFloat(), 0f)
            LAccess.x -> {
                x = World.unconv(value.toFloat())
                if (!isLocal()) snapInterpolation()
            }
            LAccess.y -> {
                y = World.unconv(value.toFloat())
                if (!isLocal()) snapInterpolation()
            }
            LAccess.velocityX -> vel.x = (value * mindustry.Vars.tilesize / 60.0).toFloat()
            LAccess.velocityY -> vel.y = (value * mindustry.Vars.tilesize / 60.0).toFloat()
            LAccess.rotation -> rotation = value.toFloat()
            LAccess.team -> {
                if (!mindustry.Vars.net.client()) {
                    val t = Team.get(value.toInt())
                    if (controller is Player) {
                        (controller as Player).team(t)
                    }
                    team = t
                }
            }
            LAccess.flag -> flag = value
            LAccess.speed -> statusSpeed(Mathf.clamp(value.toFloat(), 0f, 1000f))
            LAccess.armor -> statusArmor(Math.max(value.toFloat(), 0f))
        }
    }

    override fun setProp(prop: LAccess, value: Any?) {
        when (prop) {
            LAccess.team -> {
                if (value is Team && !mindustry.Vars.net.client()) {
                    if (controller is Player) (controller as Player).team(value)
                    team = value
                }
            }
            LAccess.payloadType -> {
                //仅服务器端
                val pay = (this as? Any) as? Payloadc ?: return
                if (mindustry.Vars.net.client()) return
                when (value) {
                    is Block -> {
                        if (value.synthetic()) {
                            val build = value.newBuilding().create(value, team())
                            if (pay.canPickup(build)) pay.addPayload(BuildPayload(build))
                        }
                    }
                    is UnitType -> {
                        val unit = value.create(team())
                        if (pay.canPickup(unit)) pay.addPayload(UnitPayload(unit))
                    }
                    null -> {
                        if (pay.payloads().size > 0) {
                            pay.payloads().pop()
                        }
                    }
                }
            }
        }
    }

    override fun setProp(content: UnlockableContent, value: Double) {
        if (content is Item) {
            stack.item = content
            stack.amount = Mathf.clamp(value.toInt(), 0, type.itemCapacity)
        }
    }

    @Replace
    override fun canShoot(): Boolean {
        //提升时无法射击
        return !disarmed && !(type.canBoost && isFlying())
    }

    fun isEnemy(): Boolean {
        return type.isEnemy
    }

    @Replace
    override fun collides(other: Hitboxc): Boolean {
        return hittable()
    }

    override fun collision(other: Hitboxc, x: Float, y: Float) {
        if (other is Bullet) {
            controller?.hit(other)
        }
    }

    override fun itemCapacity(): Int {
        return type.itemCapacity
    }

    override fun bounds(): Float {
        return hitSize * 2f
    }

    override fun controller(next: UnitController) {
        this.controller = next
        if (next.unit() != self()) next.unit(self())
    }

    override fun controller(): UnitController? {
        return controller
    }

    fun resetController() {
        controller(type.createController(self()))
    }

    override fun set(def: UnitType, controller: UnitController) {
        if (this.type != def) {
            setType(def)
        }
        controller(controller)
    }

    /** @return 用于单位物理的碰撞层。返回 PhysicsProcess 内容之外的任何内容都会导致游戏崩溃。 */
    fun collisionLayer(): Int {
        return if (type.allowLegStep && type.legPhysicsLayer) mindustry.async.PhysicsProcess.layerLegs
        else if (isGrounded()) mindustry.async.PhysicsProcess.layerGround
        else mindustry.async.PhysicsProcess.layerFlying
    }

    fun lookAt(angle: Float) {
        rotation = Angles.moveToward(rotation, angle, type.rotateSpeed * Time.delta * speedMultiplier())
    }

    fun lookAt(pos: Position) {
        lookAt(angleTo(pos))
    }

    fun lookAt(x: Float, y: Float) {
        lookAt(angleTo(x, y))
    }

    fun isAI(): Boolean {
        return controller is AIController
    }

    /** @return 单位是否*可以*被命令,即使其控制器当前不是 CommandAI。 */
    fun allowCommand(): Boolean {
        return controller is CommandAI
    }

    /** @return 单位是否有 CommandAI 控制器 */
    fun isCommandable(): Boolean {
        return controller is CommandAI
    }

    fun canTarget(other: Teamc?): Boolean {
        if (other == null) return false
        return when (other) {
            is Unit -> other.checkTarget(type.targetAir, type.targetGround)
            is Building -> type.targetGround
            else -> false
        }
    }

    fun command(): CommandAI {
        if (controller is CommandAI) {
            return controller as CommandAI
        } else {
            throw IllegalArgumentException("Unit cannot be commanded - check isCommandable() first.")
        }
    }

    fun isMissile(): Boolean {
        return this is TimedKillc
    }

    fun count(): Int {
        return team.data().countType(type)
    }

    fun cap(): Int {
        return Units.getCap(team)
    }

    fun setType(type: UnitType) {
        this.type = type
        this.maxHealth = type.health
        this.drag = type.drag
        this.armor = type.armor
        this.hitSize = type.hitSize

        if (mounts().size != type.weapons.size) setupWeapons(type)
        if (abilities.size != type.abilities.size || (abilities.isNotEmpty() && abilities[0] is EmptyDataAbility)) {
            val old = abilities
            abilities = Array(type.abilities.size) { idx ->
                type.abilities.get(idx).copy().also { newAb ->
                    if (idx < old.size) {
                        newAb.data = old[idx].data
                    }
                }
            }
        }
        if (controller == null) controller(type.createController(self()))
    }

    fun playerControllable(): Boolean {
        return type.playerControllable && !(controller is LogicAI && (controller as LogicAI).controller != null && (controller as LogicAI).controller.block.privileged)
    }

    fun targetable(targeter: Team): Boolean {
        return type.targetable(self(), targeter)
    }

    fun killable(): Boolean {
        return type.killable(self())
    }

    fun hittable(): Boolean {
        return type.hittable(self())
    }

    override fun afterSync() {
        //读取后设置类型信息
        setType(this.type)
        controller?.unit(self())
    }

    override fun afterRead() {
        setType(this.type)
        controller?.unit(self())
        //重置控制器状态
        if (!(controller is AIController && (controller as AIController).keepState())) {
            controller(type.createController(self()))
        }
    }

    override fun afterReadAll() {
        controller?.afterRead(self())
    }

    override fun add() {
        team.data().updateCount(type, 1)

        //检查是否超过单位上限
        if (type.useUnitCap && count() > cap() && !spawnedByCore && !dead && !mindustry.Vars.state.rules.editor) {
            Call.unitCapDeath(self())
            team.data().updateCount(type, -1)
        }
    }

    override fun remove() {
        team.data().updateCount(type, -1)
        controller?.removed(self())

        //确保残影不会突然消失
        if (trail != null && trail!!.size() > 0) {
            Fx.trailFade.at(x, y, trail!!.width(), if (type.trailColor == null) team.color else type.trailColor, trail!!.copy())
        }
    }

    override fun landed() {
        if (type.mechLandShake > 0f) {
            Effect.shake(type.mechLandShake, type.mechLandShake, this)
        }
        type.landed(self())
    }

    override fun heal(amount: Float) {
        if (health < maxHealth && amount > 0) {
            wasHealed = true
        }
    }

    fun updateDrowning() {
        val floor = drownFloor()

        if (floor != null && floor.isLiquid && floor.drownTime > 0 && canDrown()) {
            lastDrownFloor = floor
            drownTime += Time.delta / (hitSize / 8f * type.drownTimeMultiplier * floor.drownTime)
            if (Mathf.chanceDelta(0.05f)) {
                floor.drownUpdateEffect.at(x, y, hitSize, floor.mapColor)
            }

            if (drownTime >= 0.999f && !mindustry.Vars.net.client()) {
                kill()
                Events.fire(UnitDrownEvent(self()))
            }
        } else {
            drownTime -= Time.delta / 50f
        }

        drownTime = Mathf.clamp(drownTime)
    }

    override fun update() {
        type.update(self())

        //更新边界
        if (type.bounded) {
            var bot = 0f
            var left = 0f
            var top = mindustry.Vars.world.unitHeight()
            var right = mindustry.Vars.world.unitWidth()

            //TODO 隐藏地图规则仅适用于玩家队伍？应该吗？
            if (mindustry.Vars.state.rules.limitMapArea && !team.isAI()) {
                bot = mindustry.Vars.state.rules.limitY * mindustry.Vars.tilesize
                left = mindustry.Vars.state.rules.limitX * mindustry.Vars.tilesize
                top = mindustry.Vars.state.rules.limitHeight * mindustry.Vars.tilesize + bot
                right = mindustry.Vars.state.rules.limitWidth * mindustry.Vars.tilesize + left
            }

            if (!mindustry.Vars.net.client() || isLocal()) {
                var dx = 0f
                var dy = 0f

                //将单位推离边界
                if (x < left) dx += (-(x - left) / warpDst)
                if (y < bot) dy += (-(y - bot) / warpDst)
                if (x > right - mindustry.Vars.tilesize) dx -= (x - (right - mindustry.Vars.tilesize)) / warpDst
                if (y > top - mindustry.Vars.tilesize) dy -= (y - (top - mindustry.Vars.tilesize)) / warpDst

                velAddNet(dx * Time.delta, dy * Time.delta)
                val margin = mindustry.Vars.tilesize * 1f
                x = Mathf.clamp(x, left - margin, right - mindustry.Vars.tilesize + margin)
                y = Mathf.clamp(y, bot - margin, top - mindustry.Vars.tilesize + margin)
            }

            //不飞行时限制位置
            if (isGrounded()) {
                x = Mathf.clamp(x, left, right - mindustry.Vars.tilesize)
                y = Mathf.clamp(y, bot, top - mindustry.Vars.tilesize)
            }

            //超出边界时死亡
            if (x < -mindustry.Vars.finalWorldBounds + left || y < -mindustry.Vars.finalWorldBounds + bot || x >= right + mindustry.Vars.finalWorldBounds || y >= top + mindustry.Vars.finalWorldBounds) {
                kill()
            }
        }

        if (health.isNaN()) {
            health = 0f
            kill()
        }

        //更新溺水/飞行状态
        val floor = floorOn()
        val tile = tileOn()

        if (isFlying() != wasFlying) {
            if (wasFlying) {
                if (tile != null) {
                    Fx.unitLand.at(x, y, if (floor.isLiquid) 1f else 0.5f, tile.getFloorColor())
                }
            }
            wasFlying = isFlying()
        }

        if (!type.hovering && isGrounded() && type.emitWalkEffect) {
            splashTimer += Mathf.dst(deltaX(), deltaY())
            if (splashTimer >= (7f + hitSize() / 8f)) {
                floor.walkEffect.at(x, y, hitSize() / 8f, if (tile != null) tile.getFloorColor() else floor.mapColor)
                splashTimer = 0f

                if (type.emitWalkSound) {
                    floor.walkSound.at(x, y, Mathf.random(floor.walkSoundPitchMin, floor.walkSoundPitchMax), floor.walkSoundVolume)
                }
            }
        }

        updateDrowning()

        if (wasHealed && healTime <= -1f) {
            healTime = 1f
        }
        healTime -= Time.delta / 20f
        wasHealed = false

        //在已占领的扇区立即死亡
        if (team.isOnlyAI() && mindustry.Vars.state.isCampaign() && mindustry.Vars.state.getSector().isCaptured()) {
            kill()
        }

        if (!mindustry.Vars.headless) {
            mindustry.Vars.control.sound.loop(type.loopSound, this, type.loopSoundVolume)
            if (type.moveSound != Sounds.none) {
                val progress = Mathf.clamp(vel.len() / type.speed)
                val pitch = Mathf.lerp(type.moveSoundPitchMin, type.moveSoundPitchMax, progress)
                mindustry.Vars.control.sound.loop(type.moveSound, this, type.moveSoundVolume * progress, pitch)
            }
        }

        //检查环境是否不支持
        if (!type.supportsEnv(mindustry.Vars.state.rules.env) && !dead) {
            Call.unitEnvDeath(self())
            team.data().updateCount(type, -1)
        }

        for (a in abilities) {
            a.update(self())
        }

        if (trail != null) {
            trail!!.length = type.trailLength

            val scale = if (type.useEngineElevation) elevation else 1f
            val offset = type.engineOffset / 2f + type.engineOffset / 2f * scale

            val cx = x + Angles.trnsx(rotation + 180, offset)
            val cy = y + Angles.trnsy(rotation + 180, offset)
            trail!!.update(cx, cy)
        }

        drag = type.drag * (if (isGrounded()) floorOn().dragMultiplier else 1f) * dragMultiplier * mindustry.Vars.state.rules.dragMultiplier

        //基于出生点应用击退
        if (team != mindustry.Vars.state.rules.waveTeam && mindustry.Vars.state.hasSpawns() && (!mindustry.Vars.net.client() || isLocal()) && hittable()) {
            val relativeSize = mindustry.Vars.state.rules.dropZoneRadius + hitSize / 2f + 1f
            for (spawn in mindustry.Vars.spawner.getSpawns()) {
                if (within(spawn.worldx(), spawn.worldy(), relativeSize)) {
                    velAddNet(Tmp.v1.set(this).sub(spawn.worldx(), spawn.worldy()).setLength(0.1f + 1f - dst(spawn) / relativeSize).scl(0.45f * Time.delta))
                }
            }
        }

        //模拟坠落
        if (dead || health <= 0) {
            //死亡时阻力更小
            drag = 0.01f

            //标准坠落烟雾
            if (Mathf.chanceDelta(0.1)) {
                Tmp.v1.rnd(Mathf.range(hitSize))
                type.fallEffect.at(x + Tmp.v1.x, y + Tmp.v1.y)
            }

            //推进器坠落轨迹
            if (Mathf.chanceDelta(0.2)) {
                val offset = type.engineOffset / 2f + type.engineOffset / 2f * elevation
                val range = Mathf.range(type.engineSize)
                type.fallEngineEffect.at(
                    x + Angles.trnsx(rotation + 180, offset) + Mathf.range(range),
                    y + Angles.trnsy(rotation + 180, offset) + Mathf.range(range),
                    Mathf.random()
                )
            }

            //下降
            elevation -= type.fallSpeed * Time.delta

            if (isGrounded() || health <= -maxHealth * type.wreckHealthMultiplier) {
                Call.unitDestroy(id)
            }
        }

        if (tile != null && tile.build != null) {
            tile.build!!.unitOnAny(self())
        }

        if (tile != null && isGrounded() && !type.hovering) {
            //单位方块更新
            if (tile.build != null) {
                tile.build!!.unitOn(self())
            }

            //应用伤害
            if (floor.damageTaken > 0f) {
                damageContinuous(floor.damageTaken)
            }
        }

        //杀死在对其来说为实心的格子上的实体
        if (tile != null && !canPassOn()) {
            //如果可以则提升
            if (type.canBoost) {
                elevation = 1f
            } else if (!mindustry.Vars.net.client() && !(!mindustry.Vars.headless && isRemote())) {
                kill()
            }
        }

        //AI仅在服务器上更新
        if (!mindustry.Vars.net.client() && !dead && shouldUpdateController()) {
            controller?.updateUnit()
        }

        //当控制器变为无效时清除
        if (controller?.isValidController() == false) {
            resetController()
        }

        //移除由核心生成的单位
        if (spawnedByCore && !isPlayer() && !dead) {
            Call.unitDespawn(self())
        }
    }

    fun shouldUpdateController(): Boolean {
        return true
    }

    /** @return 此单位的预览 UI 图标。 */
    fun icon(): TextureRegion {
        return type.uiIcon
    }

    /** 实际摧毁单位,移除并创建爆炸效果。 */
    fun destroy() {
        if (!isAdded() || !killable()) return

        val explosiveness = 2f + item().explosiveness * stack().amount * 1.53f
        val flammability = item().flammability * stack().amount / 1.9f
        val power = item().charge * Mathf.pow(stack().amount, 1.11f) * 160f

        if (!spawnedByCore) {
            Damage.dynamicExplosion(x, y, flammability, explosiveness, power, (bounds() + type.legLength / 1.7f) / 2f, mindustry.Vars.state.rules.damageExplosions && mindustry.Vars.state.rules.unitCrashDamage(team) > 0, item().flammability > 1, team, type.deathExplosionEffect, 0f)
        } else {
            type.deathExplosionEffect.at(x, y, bounds() / 2f / 8f)
        }

        val shake = if (type.deathShake < 0) 3f + hitSize / 3f else type.deathShake

        if (type.createScorch) {
            Effect.scorch(x, y, (hitSize / 5).toInt())
        }
        Effect.shake(shake, shake, this)
        type.deathSound.at(this, 1f, type.deathSoundVolume)

        Events.fire(UnitDestroyEvent(self()))

        if (explosiveness > 7f && (isLocal() || wasPlayer)) {
            Events.fire(Trigger.suicideBomb)
        }

        for (mount in mounts) {
            if (mount.weapon.shootOnDeath && !(mount.weapon.bullet.killShooter && mount.totalShots > 0)) {
                if (mount.weapon.shootOnDeathEffect != null && !hasTarget) {
                    mount.allowShootEffects = false
                    mount.weapon.shootOnDeathEffect!!.at(x, y, rotation)
                }
                mount.reload = 0f
                mount.shoot = true
                mount.weapon.update(self(), mount)
            }
        }

        //如果此单位坠毁（正在飞行）,在半径内造成伤害
        if (type.flying && !spawnedByCore && type.createWreck && mindustry.Vars.state.rules.unitCrashDamage(team) > 0) {
            val shields = mindustry.Vars.indexer.getEnemy(team, BlockFlag.shield)
            val crashDamage = Mathf.pow(hitSize, 0.75f) * type.crashDamageMultiplier * 2.5f * mindustry.Vars.state.rules.unitCrashDamage(team)
            if (shields.isEmpty() || !shields.contains { b -> b is ExplosionShield && b.absorbExplosion(x, y, crashDamage) }) {
                Damage.damage(team, x, y, Mathf.pow(hitSize, 0.94f) * 1.25f, crashDamage, true, false, true)
            }
        }

        if (!mindustry.Vars.headless && type.createScorch) {
            for (i in type.wreckRegions.indices) {
                if (type.wreckRegions[i].found()) {
                    val range = type.hitSize / 4f
                    Tmp.v1.rnd(range)
                    Effect.decal(type.wreckRegions[i], x + Tmp.v1.x, y + Tmp.v1.y, rotation - 90)
                }
            }
        }

        for (a in abilities) {
            a.death(self())
        }

        type.killed(self())

        remove()
    }

    /** @return 直接或间接玩家控制器的名称。 */
    override fun getControllerName(): String? {
        if (isPlayer()) return getPlayer()?.coloredName()
        if (controller is LogicAI && (controller as LogicAI).controller != null) return (controller as LogicAI).controller.lastAccessed
        return null
    }

    override fun display(table: Table) {
        type.display(self(), table)
    }

    override fun draw() {
        type.draw(self())
    }

    override fun isPlayer(): Boolean {
        return controller is Player
    }

    fun getPlayer(): Player? {
        return if (isPlayer()) controller as? Player else null
    }

    override fun killed() {
        wasPlayer = isLocal()
        health = Math.min(health, 0f)
        dead = true

        //当单位已在地面上时,直接摧毁
        if (!type.flying || !type.createWreck) {
            destroy()
        } else {
            type.wreckSound.at(this, 1f, type.wreckSoundVolume)
        }
    }

    @Replace
    override fun kill() {
        if (dead || mindustry.Vars.net.client() || !killable()) return

        //死亡是同步的;这会调用 killed()
        Call.unitDeath(id)
    }

    @Replace
    override fun toString(): String {
        return "Unit#" + id() + ":" + type + " (" + x + ", " + y + ")"
    }