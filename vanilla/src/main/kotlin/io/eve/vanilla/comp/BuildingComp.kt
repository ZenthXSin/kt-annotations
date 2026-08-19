package io.eve.vanilla.comp

import io.eve.ktannot.*
import io.eve.vanilla.gen.*
import kotlin.jvm.Transient
import arc.*
import arc.Graphics.Cursor.*
import arc.audio.*
import arc.func.*
import arc.graphics.*
import arc.graphics.g2d.*
import arc.math.*
import arc.math.geom.*
import arc.math.geom.QuadTree.*
import arc.scene.ui.*
import arc.scene.ui.layout.*
import arc.struct.*
import arc.util.*
import arc.util.io.*
import mindustry.*
import mindustry.audio.*
import mindustry.content.*
import mindustry.core.*
import mindustry.ctype.*
import mindustry.editor.*
import mindustry.entities.*
import mindustry.entities.bullet.*
import mindustry.game.EventType.*
import mindustry.game.*
import mindustry.game.Teams.*
import mindustry.gen.*
import mindustry.graphics.*
import mindustry.logic.*
import mindustry.type.*
import mindustry.ui.*
import mindustry.world.*
import mindustry.world.blocks.ConstructBlock.*
import mindustry.world.blocks.*
import mindustry.world.blocks.environment.*
import mindustry.world.blocks.heat.*
import mindustry.world.blocks.heat.HeatConductor.*
import mindustry.world.blocks.logic.LogicBlock.*
import mindustry.world.blocks.payloads.*
import mindustry.world.blocks.power.*
import mindustry.world.consumers.*
import mindustry.world.meta.*
import mindustry.world.modules.*

/**
 * 建筑组件（对标原版 BuildingComp）。
 * 管理所有建筑方块的生命周期、网络、逻辑、物品/液体/电力、绘制等。
 * 依赖:Posc, Teamc, Healthc, Timerc。
 */
@Component(base = true, genInterface = false)
abstract class BuildingComp : Posc, Teamc, Healthc, Timerc, QuadTreeObject, Displayable, Sized, Senseable, Controllable, Settable, AmbientSource {
    companion object {
        const val timeToSleep = 60f * 1
        const val recentDamageTime = 60f * 5f
        val tmpTiles = ObjectSet<Building>()
        val tempBuilds = Seq<Building>()
        val teamChangeEvent = BuildTeamChangeEvent()
        val bulletDamageEvent = BuildDamageEvent()
        var sleepingEntities = 0
    }

    @Import var x = 0f
    @Import var y = 0f
    @Import var health = 0f
    @Import var maxHealth = 0f
    @Import var team = Team.derelict
    @Import var dead = false

    @Transient var tile: Tile? = null
    @Transient var block: Block? = null
    @Transient var proximity = Seq<Building>(true, 6, Building::class.java)
    @Transient var cdump = 0
    @Transient var rotation = 0
    @Transient var lastAccessed: String? = null
    @Transient var visualLiquid = 0f

    /** TODO Each bit corresponds to a team ID. Only 64 are supported. Does not work on servers. */
    @Transient var visibleFlags = 0L
    @Transient var wasVisible = false

    @Transient var enabled = true
    @Transient var lastDisabler: Building? = null

    var power: PowerModule? = null
    var items: ItemModule? = null
    var liquids: LiquidModule? = null

    /** Base efficiency. Takes the minimum value of all consumers. */
    @Transient var efficiency = 0f
    /** Same as efficiency, but for optional consumers only. */
    @Transient var optionalEfficiency = 0f
    /** The efficiency this block *would* have if shouldConsume() returned true. */
    @Transient var potentialEfficiency = 0f
    /** Whether there are any consumers (aside from power) that have efficiency > 0. */
    @Transient var shouldConsumePower = false

    @Transient var healSuppressionTime = -1f
    @Transient var lastHealTime = -120f * 10f
    @Transient var suppressColor: Color = mindustry.graphics.Pal.sapBullet

    @Transient private var lastDamageTime = -recentDamageTime
    @Transient private var timeScale = 1f
    @Transient private var timeScaleDuration = 0f

    @Transient private var dumpAccum = 0f

    @Transient private var sleeping = false
    @Transient private var sleepTime = 0f
    @Transient private var initialized = false

    // used only by the indexer
    @Transient var wasDamaged = false
    @Transient var indexerBuildIndex: Short = 0
    @Transient var indexerBuildTypeIndex: Short = 0
}

    /** Sets this tile entity data to this and adds it if necessary. */
    fun init(tile: Tile, team: Team, shouldAdd: Boolean, rotation: Int): Building {
        if (!initialized) {
            create(tile.block(), team)
        } else {
            if (block!!.hasPower) {
                power!!.init = false
                //reinit power graph
                PowerGraph().add(self())
            }
        }
        proximity.clear()
        this.rotation = rotation
        this.tile = tile

        set(tile.drawx(), tile.drawy())

        if (shouldAdd) {
            add()

            if (block!!.ambientSound != Sounds.none && !Vars.headless) {
                Vars.control.sound.addAmbientSource(this)
            }
        }

        checkAllowUpdate()
        created()

        return self()
    }

    /** Sets up all the necessary variables, but does not add this entity anywhere. */
    fun create(block: Block, team: Team): Building {
        this.block = block
        this.team = team

        health = block.health
        maxHealth = block.health
        timer = Interval(block.timers)

        if (block.hasItems) items = ItemModule()
        if (block.hasLiquids) liquids = LiquidModule()
        if (block.hasPower) {
            power = PowerModule()
            power!!.graph.add(self())
        }

        initialized = true

        return self()
    }

    override fun add() {
        if (power != null) {
            power!!.graph.checkAdd()
        }
    }

    @Replace
    override fun tileX(): Int = tile!!.x

    @Replace
    override fun tileY(): Int = tile!!.y

    //endregion
    //region io

    final fun writeBase(write: Writes) {
        //TODO: this code is a legacy mess; in future versions, it should be replaced with a different system that has a 1-integer module bitmask + byte version.
        val writeVisibility = Vars.state.rules.fog && visibleFlags != 0L

        write.f(health)
        write.b(rotation or 0b10000000)
        write.b(team.id)
        write.b(if (writeVisibility) 4 else 3) //version
        write.b(if (enabled) 1 else 0)
        //write presence of items/power/liquids/cons, so removing/adding them does not corrupt future saves.
        write.b(moduleBitmask())
        if (items != null) items!!.write(write)
        if (power != null) power!!.write(write)
        if (liquids != null) liquids!!.write(write)

        //write timescale if relevant
        if (timeScale != 1f) {
            write.f(timeScale)
            write.f(timeScaleDuration)
        }

        if (lastDisabler != null && lastDisabler!!.isValid()) {
            write.i(lastDisabler!!.pos())
        }

        //efficiency is written as two bytes to save space
        write.b((Mathf.clamp(efficiency) * 255f).toInt().toByte())
        write.b((Mathf.clamp(optionalEfficiency) * 255f).toInt().toByte())

        //only write visibility when necessary, saving 8 bytes - implies new version
        if (writeVisibility) {
            write.l(visibleFlags)
        }
    }

    final fun readBase(read: Reads) {
        //cap health by block health in case of nerfs
        health = Math.min(read.f(), block!!.health)
        val rot = read.b()
        team = Team.get(read.b())

        rotation = rot.toInt() and 0b01111111

        val moduleBitsOrig = moduleBitmask()
        var moduleBits = moduleBitsOrig
        val legacy: Boolean
        var version = 0

        //new version
        if ((rot.toInt() and 0b10000000) != 0) {
            version = read.b().toInt() //version of entity save
            if (version >= 1) {
                val on = read.b()
                this.enabled = on == 1.toByte()
            }

            //get which modules should actually be read; this was added in version 2
            if (version >= 2) {
                moduleBits = read.ub().toInt()
            }
            legacy = false
        } else {
            legacy = true
        }

        if ((moduleBits and 1) != 0) (items ?: ItemModule()).read(read, legacy)
        if ((moduleBits and (1 shl 1)) != 0) (power ?: PowerModule()).read(read, legacy)
        if ((moduleBits and (1 shl 2)) != 0) (liquids ?: LiquidModule()).read(read, legacy)
        //1 << 3 is skipped (old consume module)
        if ((moduleBits and (1 shl 4)) != 0) {
            timeScale = read.f()
            timeScaleDuration = read.f()
        }
        if ((moduleBits and (1 shl 5)) != 0) {
            lastDisabler = Vars.world.build(read.i())
        }

        //unnecessary consume module read in version 2 and below
        if (version <= 2) read.bool()

        //version 3 has efficiency numbers instead of bools
        if (version >= 3) {
            efficiency = read.ub() / 255f
            potentialEfficiency = efficiency
            optionalEfficiency = read.ub() / 255f
        }

        //version 4 has visibility flags
        if (version == 4) {
            visibleFlags = read.l()
        }
    }

    fun moduleBitmask(): Int {
        return (
            (if (items != null) 1 else 0) or
            (if (power != null) 1 shl 1 else 0) or
            (if (liquids != null) 1 shl 2 else 0) or
            1 shl 3 or //old consume module
            (if (timeScale != 1f) 1 shl 4 else 0) or
            (if (lastDisabler != null && lastDisabler!!.isValid()) 1 shl 5 else 0)
            )
    }

    fun writeAll(write: Writes) {
        writeBase(write)
        write(write)
    }

    fun readAll(read: Reads, revision: Byte) {
        readBase(read)
        read(read, revision)
    }

    fun writeSync(write: Writes) {
        writeAll(write)
    }

    fun readSync(read: Reads, revision: Byte) {
        readAll(read, revision)
    }

    @CallSuper
    fun write(write: Writes) {
        //overriden by subclasses!
    }

    @CallSuper
    fun read(read: Reads, revision: Byte) {
        //overriden by subclasses!
    }
}

    //region utility methods

    fun isDiscovered(viewer: Team?): Boolean {
        if (Vars.state.rules.limitMapArea && Vars.world.getDarkness(tile!!.x, tile!!.y) >= 3) {
            return false
        }

        if (viewer == null || !Vars.state.rules.staticFog || !Vars.state.rules.fog) {
            return true
        }
        if (block!!.size <= 2) {
            return Vars.fogControl.isDiscovered(viewer, tile!!.x, tile!!.y)
        } else {
            val s = block!!.size / 2
            return Vars.fogControl.isDiscovered(viewer, tile!!.x, tile!!.y) ||
                Vars.fogControl.isDiscovered(viewer, tile!!.x - s, tile!!.y - s) ||
                Vars.fogControl.isDiscovered(viewer, tile!!.x - s, tile!!.y + s) ||
                Vars.fogControl.isDiscovered(viewer, tile!!.x + s, tile!!.y + s) ||
                Vars.fogControl.isDiscovered(viewer, tile!!.x + s, tile!!.y - s)
        }
    }

    fun addPlan(checkPrevious: Boolean) {
        addPlan(checkPrevious, false)
    }

    fun addPlan(checkPrevious: Boolean, ignoreConditions: Boolean) {
        if (!ignoreConditions && (!block!!.rebuildable || (team == Vars.state.rules.defaultTeam && Vars.state.isCampaign() && !block!!.isVisible()))) return

        var overrideConfig: Any? = null
        var toAdd: Block = this.block!!

        if (self() is ConstructBuild) {
            val entity = self() as ConstructBuild
            //update block to reflect the fact that something was being constructed
            if (entity.current != null && entity.current!!.synthetic() && entity.wasConstructing) {
                toAdd = entity.current
                overrideConfig = entity.lastConfig
            } else {
                //otherwise this was a deconstruction that was interrupted, don't want to rebuild that
                return
            }
        }

        val data = team.data()

        if (checkPrevious) {
            //remove existing blocks that have been placed here.
            //painful O(n) iteration + copy
            var i = 0
            while (i < data.plans.size) {
                val b = data.plans.get(i)
                if (b.x == tile!!.x && b.y == tile!!.y) {
                    data.plans.removeIndex(i)
                    break
                }
                i++
            }
        }

        data.plans.addFirst(mindustry.game.BlockPlan(tile!!.x, tile!!.y, rotation.toShort(), toAdd, if (overrideConfig == null) config() else overrideConfig))
    }

    fun findClosestEdge(to: Position?, solid: Boolf<Tile>): Tile? {
        if (to == null) return null
        var best: Tile? = null
        var mindst = 0f
        for (point in Edges.getEdges(block!!.size)) {
            val other = Vars.world.tile(tile!!.x + point.x, tile!!.y + point.y)
            if (other != null && !solid.get(other) && (best == null || to.dst2(other) < mindst)) {
                best = other
                mindst = other.dst2(to)
            }
        }
        return best
    }

    /** Configure with the current, local player. */
    fun configure(value: Any?) {
        //save last used config
        block!!.lastConfig = value
        Call.tileConfig(Vars.player, self(), value)
    }

    /** Configure from a server. */
    fun configureAny(value: Any?) {
        Call.tileConfig(null, self(), value)
    }

    /** Deselect this tile from configuration. */
    fun deselect() {
        if (!Vars.headless && Vars.control.input.config.getSelected() == self()) {
            Vars.control.input.config.hideConfig()
        }
    }

    /** Called clientside when the client taps a block to config.
     * @return whether the configuration UI should be shown. */
    fun configTapped(): Boolean {
        return true
    }

    fun calculateHeat(sideHeat: FloatArray): Float {
        return calculateHeat(sideHeat, null)
    }

    fun calculateHeat(sideHeat: FloatArray, cameFrom: IntSet?): Float {
        Arrays.fill(sideHeat, 0f)
        if (cameFrom != null) cameFrom.clear()

        var heat = 0f

        for (build in proximity) {
            if (build != null && build.team == team && build is HeatBlock) {
                val heater = build as HeatBlock

                val split = build.block is HeatConductor && (build.block as HeatConductor).splitHeat
                // non-routers must face us, routers must face away - next to a redirector, they're forced to face away due to cycles anyway
                if (!build.block.rotate || (!split && (relativeTo(build) + 2) % 4 == build.rotation) || (split && relativeTo(build) != build.rotation)) { //TODO hacky

                    //if there's a cycle, ignore its heat
                    if (!(build is HeatConductorBuild && (build as HeatConductorBuild).cameFrom.contains(id()))) {
                        //x/y coordinate difference across point of contact
                        val diff = (Math.min(Math.abs(build.x - x), Math.abs(build.y - y)) / Vars.tilesize)
                        //number of points that this block had contact with
                        val contactPoints = Math.min((block!!.size / 2f + build.block.size / 2f - diff).toInt(), Math.min(build.block.size, block!!.size))

                        //heat is distributed across building size
                        var add = heater.heat() / build.block.size * contactPoints
                        if (split) {
                            //heat routers split heat across 3 surfaces
                            add /= 3f
                        }

                        sideHeat[Mathf.mod(relativeTo(build).toInt(), 4)] += add
                        heat += add
                    }

                    //register traversed cycles
                    if (cameFrom != null) {
                        cameFrom.add(build.id)
                        if (build is HeatConductorBuild) {
                            cameFrom.addAll((build as HeatConductorBuild).cameFrom)
                        }
                    }

                    //massive hack but I don't really care anymore
                    if (heater is HeatConductorBuild) {
                        (heater as HeatConductorBuild).updateHeat()
                    }
                }
            }
        }
        return heat
    }

    /** Sets the time scale of the building to the given intensity, unless it's above that value */
    fun applyBoost(intensity: Float, duration: Float) {
        if (!block!!.canOverdrive) return
        //do not refresh time scale when getting a lower intensity
        if (intensity >= this.timeScale - 0.001f) {
            timeScaleDuration = Math.max(timeScaleDuration, duration)
        }
        timeScale = Math.max(timeScale, intensity)
    }

    /** Sets the time scale of the building to the given intensity, unless it's below that value */
    fun applySlowdown(intensity: Float, duration: Float) {
        //do not refresh time scale when getting a higher intensity
        if (intensity <= this.timeScale + 0.001f) {
            timeScaleDuration = Math.max(timeScaleDuration, duration)
        }
        timeScale = Math.min(timeScale, intensity)
    }

    fun applyHealSuppression(amount: Float) {
        applyHealSuppression(amount, Pal.sapBullet)
    }

    fun applyHealSuppression(amount: Float, suppressColor: Color) {
        healSuppressionTime = Math.max(healSuppressionTime, Time.time + amount)
        this.suppressColor = suppressColor
    }

    fun isHealSuppressed(): Boolean {
        return block!!.suppressable && Time.time <= healSuppressionTime
    }

    fun recentlyHealed() {
        lastHealTime = Time.time
    }

    fun wasRecentlyHealed(duration: Float): Boolean {
        return lastHealTime + duration >= Time.time
    }

    fun wasRecentlyDamaged(): Boolean {
        return lastDamageTime + recentDamageTime >= Time.time
    }

    fun eachEdge(cons: Cons<Tile>) {
        for (edge in block!!.getEdges()) {
            val other = Vars.world.tile(tile!!.x + edge.x, tile!!.y + edge.y)
            if (other != null) {
                cons.get(other)
            }
        }
    }

    fun nearby(dx: Int, dy: Int): Building? {
        return Vars.world.build(tile!!.x + dx, tile!!.y + dy)
    }
}

    fun nearby(rotation: Int): Building? {
        return when (rotation) {
            0 -> Vars.world.build(tile!!.x + 1, tile!!.y)
            1 -> Vars.world.build(tile!!.x, tile!!.y + 1)
            2 -> Vars.world.build(tile!!.x - 1, tile!!.y)
            3 -> Vars.world.build(tile!!.x, tile!!.y - 1)
            else -> null
        }
    }

    fun relativeTo(tile: Tile): Byte {
        return relativeTo(tile.x, tile.y)
    }

    fun relativeTo(build: Building): Byte {
        if (Math.abs(x - build.x) > Math.abs(y - build.y)) {
            if (x <= build.x - 1) return 0
            if (x >= build.x + 1) return 2
        } else {
            if (y <= build.y - 1) return 1
            if (y >= build.y + 1) return 3
        }
        return -1
    }

    fun relativeToEdge(other: Tile): Byte {
        return relativeTo(Edges.getFacingEdge(other, tile!!))
    }

    fun relativeTo(cx: Int, cy: Int): Byte {
        return tile!!.absoluteRelativeTo(cx, cy)
    }

    /** Multiblock front. */
    fun front(): Building? {
        val trns = block!!.size / 2 + 1
        return nearby(Geometry.d4(rotation).x * trns, Geometry.d4(rotation).y * trns)
    }

    /** Multiblock back. */
    fun back(): Building? {
        val trns = block!!.size / 2 + 1
        return nearby(Geometry.d4(rotation + 2).x * trns, Geometry.d4(rotation + 2).y * trns)
    }

    /** Multiblock left. */
    fun left(): Building? {
        val trns = block!!.size / 2 + 1
        return nearby(Geometry.d4(rotation + 1).x * trns, Geometry.d4(rotation + 1).y * trns)
    }

    /** Multiblock right. */
    fun right(): Building? {
        val trns = block!!.size / 2 + 1
        return nearby(Geometry.d4(rotation + 3).x * trns, Geometry.d4(rotation + 3).y * trns)
    }

    /** Any class that overrides this method and changes the value must call Vars.fogControl.forceUpdate(team). */
    fun fogRadius(): Float {
        return block!!.fogRadius
    }

    fun pos(): Int {
        return tile!!.pos()
    }

    fun rotdeg(): Float {
        return rotation * 90
    }

    /** @return preferred rotation of main texture region to be drawn */
    fun drawrot(): Float {
        return if (block!!.rotate && block!!.rotateDraw) rotation * 90 else 0f
    }

    fun floor(): Floor {
        return tile!!.floor()
    }

    fun interactable(team: Team): Boolean {
        return Vars.state.teams.canInteract(team, team())
    }

    fun timeScale(): Float {
        return timeScale
    }

    /**
     * @return the building's 'warmup', a smooth value from 0 to 1.
     * usually used for crafters and things that need to spin up before reaching full efficiency.
     * many blocks will just return 0.
     * */
    fun warmup(): Float {
        return 0f
    }

    /** @return total time this block has been producing something; non-crafter blocks usually return Time.time. */
    fun totalProgress(): Float {
        return Time.time
    }

    fun progress(): Float {
        return 0f
    }

    /** @return whether this block is allowed to update based on team/environment */
    fun allowUpdate(): Boolean {
        return team != Team.derelict && block!!.supportsEnv(Vars.state.rules.env) &&
            //check if outside map limit (privileged blocks are exempt)
            (tile is EditorTile || block!!.privileged || !Vars.state.rules.limitMapArea || !Vars.state.rules.disableOutsideArea || Rect.contains(Vars.state.rules.limitX, Vars.state.rules.limitY, Vars.state.rules.limitWidth, Vars.state.rules.limitHeight, tile!!.x, tile!!.y))
    }

    fun inMapArea(): Boolean {
        return !Vars.state.rules.limitMapArea || Rect.contains(Vars.state.rules.limitX * Vars.tilesize, Vars.state.rules.limitY * Vars.tilesize, Vars.state.rules.limitWidth * Vars.tilesize, Vars.state.rules.limitHeight * Vars.tilesize, x, y)
    }

    fun status(): BlockStatus {
        if (!enabled) {
            return BlockStatus.logicDisable
        }

        if (!shouldConsume()) {
            return BlockStatus.noOutput
        }

        if (efficiency <= 0 || !productionValid()) {
            return BlockStatus.noInput
        }

        return if (((Vars.state.tick / 30f) % 1f) < efficiency) BlockStatus.active else BlockStatus.noInput
    }

    /** Call when nothing is happening to the entity. This increments the internal sleep timer. */
    fun sleep() {
        sleepTime += Time.delta
        if (!sleeping && sleepTime >= timeToSleep) {
            remove()
            sleeping = true
            sleepingEntities++
        }
    }

    /** Call when this entity is updating. This wakes it up. */
    fun noSleep() {
        sleepTime = 0f
        if (sleeping) {
            add()
            sleeping = false
            sleepingEntities--
        }
    }

    /** Returns the version of this Building IO code. */
    fun version(): Byte {
        return 0
    }
}

    //region handler methods

    /** @return whether the player can select (but not actually control) this building. */
    fun canControlSelect(player: mindustry.gen.Unit): Boolean {
        return false
    }

    /** Called when a player control-selects this building - not called for ControlBlock subclasses. */
    fun onControlSelect(player: mindustry.gen.Unit) {
    }

    /** Called when this building receives a position command. Requires a commandable block. */
    fun onCommand(target: Vec2) {
    }

    /** @return the position that this block points to for commands, or null. */
    fun getCommandPosition(): Vec2? {
        return null
    }

    fun handleUnitPayload(unit: mindustry.gen.Unit, grabber: Cons<Payload>) {
        Fx.spawn.at(unit)

        if (unit.isPlayer()) {
            unit.getPlayer().clearUnit()
        }

        unit.remove()

        //needs new ID as it is now a payload
        if (Vars.net.client()) {
            unit.id = EntityGroup.nextId()
        } else {
            //server-side, this needs to be delayed until next frame because otherwise the packets sent out right after this event would have the wrong unit ID, leading to ghosts
            Core.app.post { unit.id = EntityGroup.nextId() }
        }

        grabber.get(UnitPayload(unit))
        Fx.unitDrop.at(unit)
    }

    fun canWithdraw(): Boolean {
        return true
    }

    fun canUnload(): Boolean {
        return block!!.unloadable
    }

    fun canResupply(): Boolean {
        return block!!.allowResupply
    }

    fun payloadCheck(conveyorRotation: Int): Boolean {
        return block!!.rotate && (rotation + 2) % 4 == conveyorRotation
    }

    /** Called when an unloader takes an item. */
    fun itemTaken(item: Item) {
    }

    fun allowDeposit(): Boolean {
        return block!!.alwaysAllowDeposit || !Vars.state.rules.onlyDepositCore
    }

    /** Called when this block is dropped as a payload. */
    fun dropped() {
    }

    /** This is for logic blocks. */
    fun handleString(value: Any?) {
    }

    fun created() {}

    /** @return whether this block is currently "active" and should be consuming requirements. */
    fun shouldConsume(): Boolean {
        return enabled
    }

    fun productionValid(): Boolean {
        return true
    }

    /** @return whether this building is currently "burning" a trigger consumer (an item) - if true, valid() on those will return true. */
    fun consumeTriggerValid(): Boolean {
        return false
    }

    fun getPowerProduction(): Float {
        return 0f
    }

    /** Returns the amount of items this block can accept. */
    fun acceptStack(item: Item, amount: Int, source: Teamc?): Int {
        return if (acceptItem(self(), item) && block!!.hasItems && (source == null || source.team() == team)) {
            Math.min(getMaximumAccepted(item) - items!!.get(item), amount)
        } else {
            0
        }
    }

    fun getMaximumAccepted(item: Item): Int {
        return block!!.itemCapacity
    }

    /** Remove a stack from this inventory, and return the amount removed. */
    fun removeStack(item: Item, amount: Int): Int {
        if (items == null) return 0
        val amount = Math.min(amount, items!!.get(item))
        noSleep()
        items!!.remove(item, amount)
        return amount
    }

    /** Handle a stack input. */
    fun handleStack(item: Item, amount: Int, source: Teamc?) {
        noSleep()
        items!!.add(item, amount)
    }

    /** Returns offset for stack placement. */
    fun getStackOffset(item: Item, trns: Vec2) {
    }

    fun acceptPayload(source: Building?, payload: Payload): Boolean {
        return false
    }

    fun handlePayload(source: Building?, payload: Payload) {
    }

    /**
     * Tries moving a payload forwards.
     * @param todump payload to dump.
     * @return whether the payload was moved successfully
     */
    fun movePayload(todump: Payload): Boolean {
        val trns = block!!.size / 2 + 1
        val next = tile!!.nearby(Geometry.d4(rotation).x * trns, Geometry.d4(rotation).y * trns)

        if (next != null && next.build != null && next.build!!.team == team && next.build!!.acceptPayload(self(), todump)) {
            next.build!!.handlePayload(self(), todump)
            return true
        }

        return false
    }

    /**
     * Tries dumping a payload to any adjacent block.
     * @param todump payload to dump.
     * @return whether the payload was moved successfully
     */
    fun dumpPayload(todump: Payload): Boolean {
        if (proximity.size == 0) return false

        val dump = this.cdump

        for (i in 0 until proximity.size) {
            val other = proximity.get((i + dump) % proximity.size)

            if (other.acceptPayload(self(), todump)) {
                other.handlePayload(self(), todump)
                incrementDump(proximity.size)
                return true
            }

            incrementDump(proximity.size)
        }

        return false
    }

    fun canBeReplaced(other: Block): Boolean {
        return other.canReplace(block)
    }

    fun handleItem(source: Building?, item: Item) {
        items!!.add(item, 1)
    }

    fun acceptItem(source: Building?, item: Item): Boolean {
        return block!!.consumesItem(item) && items!!.get(item) < getMaximumAccepted(item)
    }

    fun acceptLiquid(source: Building?, liquid: Liquid): Boolean {
        return block!!.hasLiquids && block!!.consumesLiquid(liquid)
    }

    fun handleLiquid(source: Building?, liquid: Liquid, amount: Float) {
        liquids!!.add(liquid, amount)
    }

    //TODO entire liquid system is awful
    fun dumpLiquid(liquid: Liquid) {
        dumpLiquid(liquid, 2f)
    }

    fun dumpLiquid(liquid: Liquid, scaling: Float) {
        dumpLiquid(liquid, scaling, -1)
    }

    /** @param outputDir output liquid direction relative to rotation, or -1 to use any direction. */
    fun dumpLiquid(liquid: Liquid, scaling: Float, outputDir: Int) {
        val dump = this.cdump

        if (liquids!!.get(liquid) <= 0.0001f) return

        if (!Vars.net.client() && Vars.state.isCampaign() && team == Vars.state.rules.defaultTeam) liquid.unlock()

        for (i in 0 until proximity.size) {
            incrementDump(proximity.size)

            var other = proximity.get((i + dump) % proximity.size)
            if (outputDir != -1 && (outputDir + rotation) % 4 != relativeTo(other).toInt()) continue

            other = other.getLiquidDestination(self(), liquid)

            if (other != null && other.block.hasLiquids && canDumpLiquid(other, liquid) && other.liquids != null) {
                val ofract = other.liquids!!.get(liquid) / other.block.liquidCapacity
                val fract = liquids!!.get(liquid) / block!!.liquidCapacity

                if (ofract < fract) transferLiquid(other, (fract - ofract) * block!!.liquidCapacity / scaling, liquid)
            }
        }
    }

    fun canDumpLiquid(to: Building, liquid: Liquid): Boolean {
        return true
    }

    fun transferLiquid(next: Building, amount: Float, liquid: Liquid) {
        val flow = Math.min(next.block.liquidCapacity - next.liquids!!.get(liquid), amount)

        if (next.acceptLiquid(self(), liquid)) {
            next.handleLiquid(self(), liquid, flow)
            liquids!!.remove(liquid, flow)
        }
    }

    fun moveLiquidForward(leaks: Boolean, liquid: Liquid): Float {
        val next = tile!!.nearby(rotation)

        if (next == null) return 0f

        return if (next.build != null) {
            moveLiquid(next.build!!, liquid)
        } else if (leaks && !next.block().solid && !next.block().hasLiquids) {
            val leakAmount = liquids!!.get(liquid) / 1.5f
            Puddles.deposit(next, tile, liquid, leakAmount, true, true)
            liquids!!.remove(liquid, leakAmount)
            0f
        } else {
            0f
        }
    }

    fun moveLiquid(next: Building, liquid: Liquid): Float {
        var next = next
        if (next == null) return 0f

        next = next.getLiquidDestination(self(), liquid)

        if (next.team == team && next.block.hasLiquids && liquids!!.get(liquid) > 0f) {
            val ofract = next.liquids!!.get(liquid) / next.block.liquidCapacity
            val fract = liquids!!.get(liquid) / block!!.liquidCapacity * block!!.liquidPressure
            var flow = Math.min(Mathf.clamp(fract - ofract) * block!!.liquidCapacity, liquids!!.get(liquid))
            flow = Math.min(flow, next.block.liquidCapacity - next.liquids!!.get(liquid))

            if (flow > 0f && ofract <= fract && next.acceptLiquid(self(), liquid)) {
                next.handleLiquid(self(), liquid, flow)
                liquids!!.remove(liquid, flow)
                return flow
                //handle reactions between different liquid types ▼
            } else if (!next.block.consumesLiquid(liquid) && next.liquids!!.currentAmount() / next.block.liquidCapacity > 0.1f && fract > 0.1f) {
                //TODO !IMPORTANT! uses current(), which is 1) wrong for multi-liquid blocks and 2) causes unwanted reactions, e.g. hydrogen + slag in pump
                //TODO these are incorrect effect positions
                val fx = (x + next.x) / 2f
                val fy = (y + next.y) / 2f

                val other = next.liquids!!.current()
                if (other.blockReactive && liquid.blockReactive) {
                    //TODO liquid reaction handler for extensibility
                    if ((other.flammability > 0.3f && liquid.temperature > 0.7f) || (liquid.flammability > 0.3f && other.temperature > 0.7f)) {
                        damageContinuous(1f)
                        next.damageContinuous(1f)
                        if (Mathf.chanceDelta(0.1)) {
                            Fx.fire.at(fx, fy)
                        }
                    } else if ((liquid.temperature > 0.7f && other.temperature < 0.55f) || (other.temperature > 0.7f && liquid.temperature < 0.55f)) {
                        liquids!!.remove(liquid, Math.min(liquids!!.get(liquid), 0.7f * Time.delta))
                        if (Mathf.chanceDelta(0.2f)) {
                            Fx.steam.at(fx, fy)
                        }
                    }
                }
            }
        }
        return 0f
    }

    fun getLiquidDestination(from: Building, liquid: Liquid): Building {
        return self()
    }

    fun getPayload(): Payload? {
        return null
    }

    /** Tries to take the payload. Returns null if no payload is present. */
    fun takePayload(): Payload? {
        return null
    }

    fun getPayloads(): PayloadSeq? {
        return null
    }
}

    /**
     * Tries to put this item into a nearby container, if there are no available
     * containers, it gets added to the block's inventory.
     */
    fun offload(item: Item) {
        produced(item, 1)
        val dump = this.cdump

        for (i in 0 until proximity.size) {
            incrementDump(proximity.size)
            val other = proximity.get((i + dump) % proximity.size)
            if (other.acceptItem(self(), item) && canDump(other, item)) {
                other.handleItem(self(), item)
                return
            }
        }

        handleItem(self(), item)
    }

    /**
     * Tries to put this item into a nearby container. Returns success. Unlike #offload(), this method does not change the block inventory.
     */
    fun put(item: Item): Boolean {
        val dump = this.cdump

        for (i in 0 until proximity.size) {
            incrementDump(proximity.size)
            val other = proximity.get((i + dump) % proximity.size)
            if (other.acceptItem(self(), item) && canDump(other, item)) {
                other.handleItem(self(), item)
                return true
            }
        }

        return false
    }

    fun produced(item: Item) {
        produced(item, 1)
    }

    fun produced(item: Item, amount: Int) {
        if (Vars.state.rules.sector != null && team == Vars.state.rules.defaultTeam) {
            Vars.state.rules.sector!!.info.handleProduction(item, amount)

            if (!Vars.net.client()) item.unlock()
        }
    }

    /** Dumps any item with an accumulator. May dump multiple times per frame. Use with care. */
    fun dumpAccumulate(): Boolean {
        return dumpAccumulate(null)
    }

    /** Dumps any item with an accumulator. May dump multiple times per frame. Use with care. */
    fun dumpAccumulate(item: Item?): Boolean {
        var res = false
        dumpAccum += delta()
        while (dumpAccum >= 1f) {
            res = res or dump(item)
            dumpAccum -= 1f
        }
        return res
    }

    /** Try dumping any item near the building. */
    fun dump(): Boolean {
        return dump(null)
    }

    /**
     * Try dumping a specific item near the building.
     * @param todump Item to dump. Can be null to dump anything.
     */
    fun dump(todump: Item?): Boolean {
        if (!block!!.hasItems || items!!.total() == 0 || proximity.size == 0 || (todump != null && !items!!.has(todump))) return false

        val dump = this.cdump
        val allItems = Vars.content.items()
        val itemSize = allItems.size
        val itemArray = allItems.items

        if (todump == null) {
            for (i in 0 until proximity.size) {
                val other = proximity.get((i + dump) % proximity.size)

                for (ii in 0 until itemSize) {
                    if (!items!!.has(ii)) continue
                    val item = itemArray[ii] as Item

                    if (other.acceptItem(self(), item) && canDump(other, item)) {
                        other.handleItem(self(), item)
                        items!!.remove(item, 1)
                        incrementDump(proximity.size)
                        return true
                    }
                }

                incrementDump(proximity.size)
            }
        } else {
            for (i in 0 until proximity.size) {
                val other = proximity.get((i + dump) % proximity.size)

                if (other.acceptItem(self(), todump) && canDump(other, todump)) {
                    other.handleItem(self(), todump)
                    items!!.remove(todump, 1)
                    incrementDump(proximity.size)
                    return true
                }

                incrementDump(proximity.size)
            }
        }

        return false
    }

    fun incrementDump(prox: Int) {
        //this is possible if transferring an item changed a block
        if (prox != 0) {
            cdump = (cdump + 1) % prox
        }
    }

    /** Used for dumping items. */
    fun canDump(to: Building, item: Item): Boolean {
        return true
    }

    /** Try offloading an item to a nearby container in its facing direction. Returns true if success. */
    fun moveForward(item: Item): Boolean {
        val other = front()
        if (other != null && other.team == team && other.acceptItem(self(), item)) {
            other.handleItem(self(), item)
            return true
        }
        return false
    }

    /** Called shortly before this building is removed. */
    fun onProximityRemoved() {
        if (power != null) {
            powerGraphRemoved()
        }
    }

    /** Called after this building is created in the world. May be called multiple times, or when adjacent buildings change. */
    //TODO ??? this is just onProximityUpdate ?
    fun onProximityAdded() {
        if (power != null) {
            updatePowerGraph()
        }
    }

    /** Called when anything adjacent to this building is placed/removed, including itself. */
    fun onProximityUpdate() {
        noSleep()
    }

    fun updatePowerGraph() {
        for (other in getPowerConnections(tempBuilds)) {
            if (other.power != null) {
                other.power!!.graph.addGraph(power!!.graph)
            }
        }
    }

    fun powerGraphRemoved() {
        if (power == null) return

        power!!.graph.remove(self())
        for (i in 0 until power!!.links.size) {
            val other = Vars.world.tile(power!!.links.get(i))
            if (other != null && other.build != null && other.build!!.power != null) {
                other.build!!.power!!.links.removeValue(pos())
            }
        }
        power!!.links.clear()
    }

    fun conductsTo(other: Building): Boolean {
        return !block!!.insulated
    }

    fun getPowerConnections(out: Seq<Building>): Seq<Building> {
        out.clear()
        if (power == null) return out

        for (other in proximity) {
            if (other != null && other.power != null
                && other.team == team
                && !(block!!.consumesPower && other.block.consumesPower && !block!!.outputsPower && !other.block.outputsPower && !block!!.conductivePower && !other.block.conductivePower)
                && conductsTo(other) && other.conductsTo(self()) && !power!!.links.contains(other.pos())
            ) {
                out.add(other)
            }
        }

        for (i in 0 until power!!.links.size) {
            val link = Vars.world.tile(power!!.links.get(i))
            if (link != null && link.build != null && link.build!!.power != null && link.build!!.team == team) out.add(link.build!!)
        }
        return out
    }

    fun getProgressIncrease(baseTime: Float): Float {
        return 1f / baseTime * edelta()
    }

    fun getDisplayEfficiency(): Float {
        return getProgressIncrease(1f) / edelta()
    }

    /** @return whether this block should play its idle sound. */
    fun shouldAmbientSound(): Boolean {
        return shouldConsume()
    }
}

    fun drawStatus() {
        if (block!!.enableDrawStatus && block!!.consumers.size > 0) {
            val multiplier = if (block!!.size > 1) 1f else 0.64f
            val brcx = x + (block!!.size * Vars.tilesize / 2f) - (Vars.tilesize * multiplier / 2f)
            val brcy = y - (block!!.size * Vars.tilesize / 2f) + (Vars.tilesize * multiplier / 2f)

            Draw.z(Layer.power + 1)
            Draw.color(Pal.gray, mindustry.graphics.Lod.alpha2)
            Fill.square(brcx, brcy, 2.5f * multiplier, 45)
            Draw.color(status().color, mindustry.graphics.Lod.alpha2)
            Fill.square(brcx, brcy, 1.5f * multiplier, 45)
            Draw.color()
        }
    }

    fun drawCracks() {
        if (!block!!.drawCracks || !damaged() || block!!.size > mindustry.graphics.BlockRenderer.maxCrackSize) return
        val id = pos()
        val region = Vars.renderer.blocks.cracks[block!!.size - 1][Mathf.clamp(((1f - healthf()) * mindustry.graphics.BlockRenderer.crackRegions).toInt(), 0, mindustry.graphics.BlockRenderer.crackRegions - 1)]
        Draw.colorl(0.2f, 0.1f + (1f - healthf()) * 0.6f)
        //TODO could be random, flipped, pseudorandom, etc
        Draw.rect(region, x, y, (id % 4) * 90)
        Draw.color()
    }

    /** Draw the block overlay that is shown when a cursor is over the block. */
    fun drawSelect() {
        block!!.drawOverlay(x, y, rotation)
        if (status() == BlockStatus.inactiveUnitFactory) {
            block!!.drawPlaceText(Core.bundle.format("rules.unitfactoryactivation.objective", UI.formatTime(Math.max(0f, Vars.state.rules.unitActivationDelay(team) - Vars.state.tick))), tile!!.x, tile!!.y, false)
        }
    }

    fun drawItemSelection(selection: UnlockableContent?) {
        if (selection != null) {
            val dx = x - block!!.size * Vars.tilesize / 2f
            val dy = y + block!!.size * Vars.tilesize / 2f
            val s = mindustry.Vars.iconSmall / 4f * selection.fullIcon.ratio()
            val h = mindustry.Vars.iconSmall / 4f
            Draw.mixcol(Color.darkGray, 1f)
            Draw.rect(selection.fullIcon, dx, dy - 1, s, h)
            Draw.reset()
            Draw.rect(selection.fullIcon, dx, dy, s, h)
        }
    }

    fun drawDisabled() {
        Draw.color(Color.scarlet)
        Draw.alpha(0.8f)

        val size = 6f
        Draw.rect(Icon.cancel.getRegion(), x, y, size, size)

        Draw.reset()
    }

    /** Called only if {@link Block#drawDynamic} is true (on by default). */
    fun draw() {
        if (block!!.variants == 0 || block!!.variantRegions == null) {
            Draw.rect(block!!.region, x, y, drawrot())
        } else {
            Draw.rect(block!!.variantRegions[Mathf.randomSeed(tile!!.pos(), 0, Math.max(0, block!!.variantRegions.size - 1))], x, y, drawrot())
        }

        drawTeamTop()
    }

    /** Called only if {@link Block#drawCached} is true (off by default). */
    fun drawCached() {
        draw()
    }

    fun recache() {
        if (!Vars.headless) Vars.renderer.blocks.recacheBuilding(block!!.buildingCacheLayer, tile!!)
    }

    fun payloadDraw() {
        val z = Draw.z()
        if (block!!.drawCached) {
            Draw.z(block!!.buildingCacheLayer.layer)
            drawCached()
        }
        if (block!!.drawDynamic) {
            Draw.z(z)
            draw()
        }
    }

    fun drawTeamTop() {
        if (block!!.teamRegion.found()) {
            if (block!!.teamRegions[team.id] === block!!.teamRegion) Draw.color(team.color)
            Draw.rect(block!!.teamRegions[team.id], x, y)
            Draw.color()
        }
    }

    fun drawLight() {
        val liq = if (block!!.hasLiquids && block!!.lightLiquid == null) liquids!!.current() else block!!.lightLiquid
        if (block!!.hasLiquids && block!!.drawLiquidLight && liq != null && liq.lightColor.a > 0.001f) {
            //yes, I am updating in draw()... but this is purely visual anyway, better have it here than in update() where it wastes time
            visualLiquid = Mathf.lerpDelta(visualLiquid, if (liquids!!.get(liq) >= 0.01f) 1f else 0f, 0.06f)
            drawLiquidLight(liq, visualLiquid)
        }
    }

    fun drawLiquidLight(liquid: Liquid, amount: Float) {
        if (amount > 0.01f) {
            val color = liquid.lightColor
            val fract = 1f
            val opacity = color.a * fract
            if (opacity > 0.001f) {
                Drawf.light(x, y, block!!.size * 30f * fract, color, opacity * amount)
            }
        }
    }

    fun drawTeam() {
        Draw.color(team.color)
        Draw.rect("block-border", x - block!!.size * Vars.tilesize / 2f + 4, y - block!!.size * Vars.tilesize / 2f + 4)
        Draw.color()
    }

    /** @return whether a building has regen/healing suppressed; if so, spawns particles on it. */
    fun checkSuppression(): Boolean {
        if (isHealSuppressed()) {
            if (Mathf.chanceDelta(0.03)) {
                Fx.regenSuppressParticle.at(x + Mathf.range(block!!.size * Vars.tilesize / 2f - 1f), y + Mathf.range(block!!.size * Vars.tilesize / 2f - 1f), suppressColor)
            }

            return true
        }

        return false
    }

    /** Called after the block is placed by this client. */
    @CallSuper
    fun playerPlaced(config: Any?) {
    }

    /** Called after the block is placed by anyone. */
    @CallSuper
    fun placed() {
        if (Vars.net.client()) return

        if ((block!!.consumesPower || block!!.outputsPower) && block!!.hasPower && block!!.connectedPower) {
            PowerNode.getNodeLinks(tile!!, block!!, team) { other ->
                if (!other.power!!.links.contains(pos())) {
                    other.configureAny(pos())
                }
            }
        }
    }

    /** Called when this block is derelict repaired. */
    @CallSuper
    fun onRepaired() {
        placed()
        if (block!!.flags.contains(BlockFlag.hasFogRadius)) {
            Vars.fogControl.forceUpdate(team, self())
        }
    }

    fun isCommandable(): Boolean {
        return block!!.commandable
    }

    /** @return whether this building is in a payload */
    fun isPayload(): Boolean {
        return tile === Vars.emptyTile
    }

    /**
     * Called when a block is placed over some other blocks. This seq will always have at least one item.
     * Should load some previous state, if necessary. */
    fun overwrote(previous: Seq<Building>) {
    }

    fun onRemoved() {
    }

    /** Called every frame a unit is on this. Hovering/flying/steppy units do not apply. */
    fun unitOn(unit: mindustry.gen.Unit) {
    }

    /** Called every frame a unit is on this. Applies to any unit. */
    fun unitOnAny(unit: mindustry.gen.Unit) {
    }

    /** Called when a unit that spawned at this tile is removed. */
    fun unitRemoved(unit: mindustry.gen.Unit) {
    }

    /** Called when a puddle is on this building. Only called at an interval (40 ticks). */
    fun puddleOn(puddle: Puddle) {
    }

    /** Called when arbitrary configuration is applied to a tile. */
    fun configured(builder: mindustry.gen.Unit?, value: Any?) {
        //null is of type void.class; anonymous classes use their superclass.
        val type: Class<*> = when {
            value == null -> Void.TYPE
            value.javaClass.isAnonymousClass() -> value.javaClass.superclass
            else -> value.javaClass
        }

        val resolveType: Class<*>
        when {
            value is Item -> resolveType = Item::class.java
            value is Block -> resolveType = Block::class.java
            value is Liquid -> resolveType = Liquid::class.java
            value is UnitType -> resolveType = UnitType::class.java
            else -> resolveType = type
        }

        if (builder != null && builder.isPlayer()) {
            updateLastAccess(builder.getPlayer())
        }

        if (block!!.configurations.containsKey(resolveType)) {
            block!!.configurations[resolveType]!!.get(this, value)
        } else if (value is Building) {
            val build = value as Building
            //copy config of another building
            val conf = build.config()
            if (conf != null && conf !is Building) {
                configured(builder, conf)
            }
        }
    }

    fun updateLastAccess(player: Player) {
        lastAccessed = player.coloredName()
    }

    /** Called when the block is tapped by the local player. */
    fun tapped() {
    }

    /** Called *after* the tile has been removed. */
    fun afterDestroyed() {
        if (block!!.destroyBullet != null) {
            //I really do not like that the bullet will not destroy derelict
            //but I can't do anything about it without using a random team
            //which may or may not cause issues with servers and js
            block!!.destroyBullet!!.create(this, if (block!!.destroyBulletSameTeam) team else Team.derelict, x, y, Mathf.randomSeed(id(), 360f))
        }
    }

    /** @return the cap for item amount calculations, used when this block explodes. */
    fun explosionItemCap(): Int {
        return block!!.itemCapacity
    }

    fun splashLiquid(liquid: Liquid, amount: Float) {
        val splash = Mathf.clamp(amount / 4f, 0f, 10f)

        for (i in 0 until Mathf.clamp(amount / 5, 0, 30).toInt()) {
            Time.run(i / 2f) {
                val other = Vars.world.tileWorld(x + Mathf.range(block!!.size * Vars.tilesize / 2), y + Mathf.range(block!!.size * Vars.tilesize / 2))
                if (other != null) {
                    Puddles.deposit(other, liquid, splash)
                }
            }
        }
    }

    /** Called when a block begins (not finishes!) deconstruction. The building is still present at this point. */
    fun onDeconstructed(builder: mindustry.gen.Unit?) {
        //deposit non-incinerable liquid on ground
        if (liquids != null && liquids!!.currentAmount() > 0 && (!liquids!!.current().incinerable || block!!.deconstructDropAllLiquid)) {
            val perCell = liquids!!.currentAmount() / (block!!.size * block!!.size) * 2f
            tile!!.getLinkedTiles { other -> Puddles.deposit(other, liquids!!.current(), perCell) }
        }
    }

    /** Called when the block is destroyed. The tile is still intact at this stage. */
    fun onDestroyed() {
        var explosiveness = block!!.baseExplosiveness
        var flammability = 0f
        var power = 0f

        if (block!!.hasItems) {
            for (item in Vars.content.items()) {
                val amount = Math.min(items!!.get(item), explosionItemCap())
                explosiveness += item.explosiveness * amount
                flammability += item.flammability * amount
                power += item.charge * Mathf.pow(amount.toFloat(), 1.1f) * 150f
            }
        }

        if (block!!.hasLiquids) {
            flammability += liquids!!.sum { liquid, amount -> liquid.flammability * amount / 2f }
            explosiveness += liquids!!.sum { liquid, amount -> liquid.explosiveness * amount / 2f }
        }

        if (block!!.consPower != null && block!!.consPower!!.buffered) {
            power += this.power!!.status * block!!.consPower!!.capacity
        }

        if (block!!.hasLiquids && Vars.state.rules.damageExplosions) {
            liquids!!.each { liquid, amount -> splashLiquid(liquid, amount) }
        }

        //cap explosiveness so fluid tanks/vaults don't instakill units
        Damage.dynamicExplosion(x, y, flammability * block!!.flammabilityScale, explosiveness * 3.5f * block!!.explosivenessScale, power, Vars.tilesize * block!!.size / 2f, Vars.state.rules.damageExplosions, block!!.destroyEffect, block!!.baseShake)

        if (block!!.createRubble && !floor().solid && !floor().isLiquid) {
            Effect.rubble(x, y, block!!.size)
        }

        if (!Vars.headless) {
            playDestroySound()

            if (explosiveness > 40f) {
                (if (Mathf.chance(0.5f)) Sounds.blockExplodeExplosive else Sounds.blockExplodeExplosiveAlt).at(tile, Mathf.random(block!!.destroyPitchMin, block!!.destroyPitchMax), block!!.destroySoundVolume)
            } else if (flammability > 5f) {
                Sounds.blockExplodeFlammable.at(tile, Mathf.random(block!!.destroyPitchMin, block!!.destroyPitchMax), block!!.destroySoundVolume)
            }

            if (power > 30000f) {
                Sounds.blockExplodeElectricBig.at(tile, Mathf.random(block!!.destroyPitchMin, block!!.destroyPitchMax), block!!.destroySoundVolume)
            } else if (power > 2000f) {
                Sounds.blockExplodeElectric.at(tile, Mathf.random(block!!.destroyPitchMin, block!!.destroyPitchMax), block!!.destroySoundVolume)
            }
        }
    }

    fun playDestroySound() {
        block!!.destroySound.at(tile, Mathf.random(block!!.destroyPitchMin, block!!.destroyPitchMax), block!!.destroySoundVolume)
    }

    fun getDisplayName(): String {
        //derelict team icon currently doesn't display
        return if (team == Team.derelict) {
            block!!.localizedName + "\n" + Core.bundle.get("block.derelict")
        } else {
            block!!.localizedName + if (team == Vars.player.team() || team.emoji.isEmpty()) "" else " " + team.emoji
        }
    }

    fun getDisplayIcon(): TextureRegion {
        return block!!.uiIcon
    }

    /** @return the item module to use for flow rate calculations */
    fun flowItems(): ItemModule? {
        return items
    }
}

    override fun display(table: Table) {
        //display the block stuff
        //TODO duplicated code?
        table.table { t ->
            t.left()
            t.add(Image(block!!.getDisplayIcon(tile!!))).scaling(Scaling.fit).size(8 * 4)
            t.labelWrap(block!!.getDisplayName(tile!!)).left().width(190f).padLeft(5)
        }.growX().left()

        table.row()

        //only display everything else if the team is the same
        if (team == Vars.player.team()) {
            table.table { bars ->
                bars.defaults().growX().height(18f).pad(4)

                displayBars(bars)
            }.growX()
            table.row()
            table.table { displayConsumption(it) }.growX()

            val displayFlow = (block!!.category == Category.distribution || block!!.category == Category.liquid) && block!!.displayFlow

            if (displayFlow) {
                val ps = " " + StatUnit.perSecond.localized()

                val flowItems = flowItems()

                if (flowItems != null) {
                    table.row()
                    table.left()
                    table.table { l ->
                        val current = Bits()

                        val rebuild = Runnable {
                            l.clearChildren()
                            l.left()
                            for (item in Vars.content.items()) {
                                if (flowItems.hasFlowItem(item)) {
                                    l.image(item.uiIcon).scaling(Scaling.fit).padRight(3f)
                                    l.label { if (flowItems.getFlowRate(item) < 0) "..." else Strings.fixed(flowItems.getFlowRate(item), 1) + ps }.color(Color.lightGray)
                                    l.row()
                                }
                            }
                        }

                        rebuild.run()
                        l.update {
                            for (item in Vars.content.items()) {
                                if (flowItems.hasFlowItem(item) && !current.get(item.id)) {
                                    current.set(item.id)
                                    rebuild.run()
                                }
                            }
                        }
                    }.left()
                }

                if (liquids != null) {
                    table.row()
                    table.left()
                    table.table { l ->
                        val current = Bits()

                        val rebuild = Runnable {
                            l.clearChildren()
                            l.left()
                            for (liquid in Vars.content.liquids()) {
                                if (liquids!!.hasFlowLiquid(liquid)) {
                                    l.image(liquid.uiIcon).scaling(Scaling.fit).size(32f).padRight(3f)
                                    l.label { if (liquids!!.getFlowRate(liquid) < 0) "..." else Strings.fixed(liquids!!.getFlowRate(liquid), 1) + ps }.color(Color.lightGray)
                                    l.row()
                                }
                            }
                        }

                        rebuild.run()
                        l.update {
                            for (liquid in Vars.content.liquids()) {
                                if (liquids!!.hasFlowLiquid(liquid) && !current.get(liquid.id)) {
                                    current.set(liquid.id)
                                    rebuild.run()
                                }
                            }
                        }
                    }.left()
                }
            }

            if (Vars.net.active() && lastAccessed != null) {
                table.row()
                table.add(Core.bundle.format("lastaccessed", lastAccessed)).growX().wrap().left()
            }

            table.marginBottom(-5)
        }
    }

    fun displayConsumption(table: Table) {
        table.left()
        for (cons in block!!.consumers) {
            if (cons.optional && cons.booster) continue
            cons.build(self(), table)
        }
    }

    fun displayBars(table: Table) {
        for (bar in block!!.listBars()) {
            val result = bar.get(self())
            if (result == null) continue
            table.add(result).growX()
            table.row()
        }
    }

    /** Called when this block is tapped to build a UI on the table.
     * configurable must be true for this to be called. */
    fun buildConfiguration(table: Table) {
    }

    /** Update table alignment after configuring. */
    fun updateTableAlign(table: Table) {
        val pos = Core.input.mouseScreen(x, y - block!!.size * Vars.tilesize / 2f - 1)
        table.setPosition(pos.x - Core.scene.marginLeft, pos.y - Core.scene.marginBottom, Align.top)
    }

    /** Returns whether a hand cursor should be shown over this block. */
    fun getCursor(): Cursor {
        return if (block!!.configurable && interactable(Vars.player.team())) SystemCursor.hand else SystemCursor.arrow
    }

    /**
     * Called when another tile is tapped while this building is selected.
     * @return whether this block should be deselected.
     */
    fun onConfigureBuildTapped(other: Building): Boolean {
        if (block!!.clearOnDoubleTap) {
            if (self() == other) {
                deselect()
                configure(null)
                return false
            }
            return true
        }
        return self() != other
    }

    /**
     * Called when a position is tapped while this building is selected.
     *
     * @return whether the tap event is consumed - if true, the player will not start shooting or interact with things under the cursor.
     * */
    fun onConfigureTapped(x: Float, y: Float): Boolean {
        return false
    }

    /**
     * Called when this block's config menu is closed.
     */
    fun onConfigureClosed() {}

    /** Returns whether this config menu should show when the specified player taps it. */
    fun shouldShowConfigure(player: Player): Boolean {
        return true
    }

    /** Whether this configuration should be hidden now. Called every frame the config is open. */
    fun shouldHideConfigure(player: Player): Boolean {
        return false
    }

    fun drawConfigure() {
        Draw.color(Pal.accent)
        Lines.stroke(1f)
        Lines.square(x, y, block!!.size * Vars.tilesize / 2f + 1f)
        Draw.reset()
    }

    fun checkSolid(): Boolean {
        return false
    }

    fun handleDamage(amount: Float): Float {
        return amount
    }

    fun absorbLasers(): Boolean {
        return block!!.absorbLasers
    }

    fun isInsulated(): Boolean {
        return block!!.insulated
    }

    fun collide(other: Bullet): Boolean {
        return true
    }

    /** Handle a bullet collision.
     * @return whether the bullet should be removed. */
    fun collision(other: Bullet): Boolean {
        val wasDead = health <= 0
        val t = other.type

        var damage = other.type.buildingDamage(other)
        if (!t.pierceArmor) {
            damage = Damage.applyArmor(damage, block!!.armor * t.armorMultiplier * t.blockArmorMultiplier)
        }

        damage(other, other.team, damage)

        if (health <= 0 && !wasDead) {
            Events.fire(BuildingBulletDestroyEvent(self(), other))
        }

        return true
    }

    /** Used to handle damage from splash damage for certain types of blocks. */
    fun damage(source: Team?, damage: Float) {
        damage(damage)
    }

    /** Handles splash damage with a bullet source. */
    fun damage(bullet: Bullet, source: Team, damage: Float) {
        damage(source, damage)
        Events.fire(bulletDamageEvent.set(self(), bullet))
    }

    /** Changes this building's team in a safe manner. */
    fun changeTeam(next: Team) {
        changeTeam(next, true)
    }

    /** Changes this building's team in a safe manner. */
    fun changeTeam(next: Team, updatePower: Boolean) {
        if (this.team == next) return
        if (block!!.forceTeam != null) team = block!!.forceTeam!!

        val last = this.team

        if (last == next) return

        val was = isValid()

        if (was) Vars.indexer.removeIndex(tile!!)

        this.team = next

        if (power != null && updatePower) {
            val oldGraph = power!!.graph
            for (other in proximity) {
                if (other != null && other.team != team && other.power != null && other.power!!.graph === oldGraph) {
                    PowerGraph().reflow(other)
                }
            }
            var i = 0
            while (i < power!!.links.size) {
                val other = Vars.world.build(power!!.links.items[i])

                //only reflow links that were connected to the old power graph; ones that have a new one were already covered.
                if (other != null && other.team != team && other.power != null && other.power!!.graph === oldGraph) {
                    power!!.links.removeIndex(i)
                    other.power!!.links.removeValue(pos())

                    PowerGraph().reflow(other)

                    i--
                }
                i++
            }
            PowerGraph().reflow(self())

            updatePowerGraph()
        }

        if (was) {
            Vars.indexer.addIndex(tile!!)
            Events.fire(teamChangeEvent.set(last, self()))
        }

        checkAllowUpdate()
    }

    fun canPickup(): Boolean {
        return block!!.canPickup
    }

    /** Called right before this building is picked up. */
    fun pickedUp() {
    }

    /** Called right after this building is picked up. */
    fun afterPickedUp() {
        if (power != null) {
            //TODO can lead to ghost graphs?
            power!!.graph = PowerGraph()
            power!!.links.clear()
            if (block!!.consPower != null && !block!!.consPower!!.buffered) {
                power!!.status = 0f
            }
        }
    }

    fun removeFromProximity() {
        onProximityRemoved()
        tmpTiles.clear()

        val nearby = Edges.getEdges(block!!.size)
        for (point in nearby) {
            val other = Vars.world.build(tile!!.x + point.x, tile!!.y + point.y)
            //remove this tile from all nearby tile's proximities
            if (other != null) {
                tmpTiles.add(other)
            }
        }

        for (other in tmpTiles) {
            other.proximity.remove(self(), true)
            other.onProximityUpdate()
        }
        proximity.clear()
    }

    fun updateProximity() {
        tmpTiles.clear()
        proximity.clear()

        val nearby = Edges.getEdges(block!!.size)
        for (point in nearby) {
            val other = Vars.world.build(tile!!.x + point.x, tile!!.y + point.y)

            if (other == null || other.team != team) continue

            other.proximity.addUnique(self())

            tmpTiles.add(other)
        }

        //using a set to prevent duplicates
        for (tile in tmpTiles) {
            proximity.add(tile)
        }

        onProximityAdded()
        onProximityUpdate()

        for (other in tmpTiles) {
            other.onProximityUpdate()
        }

        if (!Vars.headless && block!!.drawCached) recache()
    }

    fun onNearbyBuildAdded(other: Building) {}

    fun consume() {
        for (cons in block!!.consumers) {
            cons.trigger(self())
        }
    }

    fun canConsume(): Boolean {
        return potentialEfficiency > 0
    }

    /** Scaled delta. */
    fun delta(): Float {
        return Time.delta * timeScale
    }

    /** Efficiency * delta. */
    fun edelta(): Float {
        return efficiency * delta()
    }

    /** Called after efficiency is updated but before consumers are updated. Use to apply your own multiplier. */
    fun updateEfficiencyMultiplier() {
        val scale = efficiencyScale()
        efficiency *= scale
        optionalEfficiency *= scale
    }

    /** Calculate your own efficiency multiplier. By default, this is applied in updateEfficiencyMultiplier. */
    fun efficiencyScale(): Float {
        return 1f
    }

    fun updateConsumption() {
        //everything is valid when cheating
        if (!block!!.hasConsumers || cheating()) {
            potentialEfficiency = if (enabled && productionValid()) 1f else 0f
            efficiency = if (shouldConsume()) potentialEfficiency else 0f
            optionalEfficiency = efficiency
            shouldConsumePower = true
            updateEfficiencyMultiplier()
            return
        }

        //disabled -> nothing works
        if (!enabled) {
            potentialEfficiency = 0f
            efficiency = 0f
            optionalEfficiency = 0f
            shouldConsumePower = false
            return
        }

        val update = shouldConsume() && productionValid()

        var minEfficiency = 1f

        //assume efficiency is 1 for the calculations below
        efficiency = 1f
        optionalEfficiency = 1f
        shouldConsumePower = true

        //first pass: get the minimum efficiency of any consumer
        for (cons in block!!.nonOptionalConsumers) {
            val result = cons.efficiency(self())

            if (cons !== block!!.consPower && result <= 0.0000001f) {
                shouldConsumePower = false
            }

            minEfficiency = Math.min(minEfficiency, result)
        }

        //same for optionals
        for (cons in block!!.optionalConsumers) {
            optionalEfficiency = Math.min(optionalEfficiency, cons.efficiency(self()))
        }

        //efficiency is now this minimum value
        efficiency = minEfficiency
        optionalEfficiency = Math.min(optionalEfficiency, minEfficiency)

        //assign "potential"
        potentialEfficiency = efficiency

        //no updating means zero efficiency
        if (!update) {
            efficiency = 0f
            optionalEfficiency = 0f
        }

        updateEfficiencyMultiplier()

        //second pass: update every consumer based on efficiency
        if (update && efficiency > 0) {
            for (cons in block!!.updateConsumers) {
                cons.update(self())
            }
        }
    }

    fun updatePayload(unitHolder: mindustry.gen.Unit?, buildingHolder: Building?) {
        update()
    }

    fun updateTile() {
    }

    /** @return ambient sound volume scale. */
    fun ambientVolume(): Float {
        return efficiency
    }
}

    //region overrides

    /** Tile configuration. Defaults to null. Used for block rebuilding. */
    fun config(): Any? {
        return null
    }

    @Replace
    override fun isValid(): Boolean {
        return tile!!.build == self() && !dead()
    }

    @MethodPriority(100)
    override fun heal() {
        healthChanged()
    }

    @MethodPriority(100)
    override fun heal(amount: Float) {
        healthChanged()
    }

    override fun hitSize(): Float {
        return tile!!.block().size * Vars.tilesize
    }

    @Replace
    override fun kill() {
        Call.buildDestroyed(self())
    }

    @Replace
    override fun damage(damage: Float) {
        if (dead()) return

        val dm = Vars.state.rules.blockHealth(team)
        lastDamageTime = Time.time

        var damage = damage
        if (Mathf.zero(dm)) {
            damage = health + 1
        } else {
            damage /= dm
        }

        //TODO handle this better on the client.
        if (!Vars.net.client()) {
            health -= handleDamage(damage)
        }

        healthChanged()

        if (health <= 0) {
            Call.buildDestroyed(self())
        }
    }

    fun healthChanged() {
        //server-side, health updates are batched.
        if (Vars.net.server()) {
            Vars.netServer.buildHealthUpdate(self())
        }

        Vars.indexer.notifyHealthChanged(self())
    }

    override fun sense(sensor: LAccess): Double {
        return when (sensor) {
            LAccess.x -> World.conv(x)
            LAccess.y -> World.conv(y)
            LAccess.color -> Color.toDoubleBits(team.color.r, team.color.g, team.color.b, 1f)
            LAccess.dead -> if (!isValid()) 1.0 else 0.0
            LAccess.solid -> if (block!!.solid || checkSolid()) 1.0 else 0.0
            LAccess.team -> team.id.toDouble()
            LAccess.health -> health.toDouble()
            LAccess.maxHealth -> maxHealth.toDouble()
            LAccess.efficiency -> efficiency.toDouble()
            LAccess.timescale -> timeScale.toDouble()
            LAccess.range -> if (this is Ranged) (this as Ranged).range() / Vars.tilesize else 0.0
            LAccess.rotation -> rotation.toDouble()
            LAccess.totalItems -> if (items == null) 0.0 else items!!.total().toDouble()
            //totalLiquids is inherently bad design, but unfortunately it is useful for conduits/tanks
            LAccess.totalLiquids -> if (liquids == null) 0.0 else liquids!!.currentAmount().toDouble()
            LAccess.totalPower -> if (power == null || block!!.consPower == null) 0.0 else (power!!.status * (if (block!!.consPower!!.buffered) block!!.consPower!!.capacity else 1f)).toDouble()
            LAccess.itemCapacity -> if (block!!.hasItems) block!!.itemCapacity.toDouble() else 0.0
            LAccess.liquidCapacity -> if (block!!.hasLiquids) block!!.liquidCapacity.toDouble() else 0.0
            LAccess.powerCapacity -> if (block!!.consPower != null) block!!.consPower!!.capacity.toDouble() else 0.0
            LAccess.powerNetIn -> if (power == null) 0.0 else (power!!.graph.getLastScaledPowerIn() * 60).toDouble()
            LAccess.powerNetOut -> if (power == null) 0.0 else (power!!.graph.getLastScaledPowerOut() * 60).toDouble()
            LAccess.powerNetStored -> if (power == null) 0.0 else power!!.graph.getLastPowerStored()
            LAccess.powerNetCapacity -> if (power == null) 0.0 else power!!.graph.getLastCapacity()
            LAccess.enabled -> if (enabled) 1.0 else 0.0
            LAccess.controlled -> if (this is ControlBlock && (this as ControlBlock).isControlled()) GlobalVars.ctrlPlayer else 0.0
            LAccess.payloadCount -> ((if (getPayloads() != null) getPayloads()!!.total() else 0) + if (getPayload() != null) 1 else 0).toDouble()
            LAccess.size -> block!!.size.toDouble()
            LAccess.cameraX, LAccess.cameraY, LAccess.cameraWidth, LAccess.cameraHeight -> if (this is ControlBlock) (this as ControlBlock).unit().sense(sensor) else 0.0
            else -> Double.NaN //gets converted to null in logic
        }
    }

    override fun senseObject(sensor: LAccess): Any? {
        return when (sensor) {
            LAccess.type -> block
            LAccess.firstItem -> if (items == null) null else items!!.first()
            LAccess.config -> if (block!!.configSenseable()) config() else null
            LAccess.payloadType -> if (getPayload() is UnitPayload) (getPayload() as UnitPayload).unit.type else if (getPayload() is BuildPayload) (getPayload() as BuildPayload).block() else null
            else -> mindustry.logic.Senseable.noSensed
        }
    }

    override fun sense(content: Content): Double {
        if (content is Item && items != null) return items!!.get(content).toDouble()
        if (content is Liquid && liquids != null) return liquids!!.get(content).toDouble()
        if (getPayloads() != null) {
            if (content is UnitType) return getPayloads()!!.get(content).toDouble()
            if (content is Block) return getPayloads()!!.get(content).toDouble()
        }
        return Double.NaN //invalid sense
    }

    override fun control(type: LAccess, p1: Double, p2: Double, p3: Double, p4: Double) {
        if (type == LAccess.enabled) {
            enabled = !Mathf.zero(p1.toFloat())
        }
    }

    override fun control(type: LAccess, p1: Any?, p2: Double, p3: Double, p4: Double) {
        //don't execute configure instructions that copy logic building configures; this can cause extreme lag
        if (type == LAccess.config && block!!.logicConfigurable && p1 !is LogicBuild) {
            //change config only if it's new
            configured(null, p1)
        }
    }

    override fun setProp(prop: LAccess, value: Double) {
        when (prop) {
            LAccess.health -> {
                health = Mathf.clamp(value, 0.0, maxHealth.toDouble()).toFloat()
                if (health <= 0f && !dead()) {
                    Call.buildDestroyed(self())
                } else {
                    healthChanged()
                }
            }
            LAccess.team -> {
                val team = Team.get(value.toInt())
                if (this.team != team) {
                    changeTeam(team)
                }
            }
            LAccess.totalPower -> {
                if (power != null && block!!.consPower != null && block!!.consPower!!.buffered) {
                    power!!.status = Mathf.clamp((value / block!!.consPower!!.capacity).toFloat())
                }
            }
            else -> {}
        }
    }

    override fun setProp(prop: LAccess, value: Any?) {
        when (prop) {
            LAccess.team -> {
                if (value is Team && this.team != value) {
                    changeTeam(value)
                }
            }
            else -> {}
        }
    }

    override fun setProp(content: UnlockableContent, value: Double) {
        if (content is Item && items != null) {
            val amount = value.toInt()
            if (items!!.get(content) != amount) {
                if (items!!.get(content) < amount) {
                    handleStack(content, acceptStack(content, amount - items!!.get(content), null), null)
                } else if (amount >= 0) {
                    removeStack(content, items!!.get(content) - amount)
                }
            }
        } else if (content is Liquid && liquids != null) {
            val amount = Mathf.clamp(value.toFloat(), 0f, block!!.liquidCapacity.toFloat())
            //decreasing amount is always allowed
            if (amount < liquids!!.get(content) || (acceptLiquid(self(), content) && (liquids!!.current() === content || liquids!!.currentAmount() <= 0.1f || block!!.consumesLiquid(content)))) {
                liquids!!.set(content, amount)
            }
        }
    }

    @Replace
    override fun inFogTo(viewer: Team): Boolean {
        if (team == viewer || !Vars.state.rules.fog) return false

        val size = block!!.size
        val of = block!!.sizeOffset
        val tx = tile!!.x
        val ty = tile!!.y

        if (!isDiscovered(viewer)) return true

        for (x in 0 until size) {
            for (y in 0 until size) {
                if (Vars.fogControl.isVisibleTile(viewer, tx + x + of, ty + y + of)) {
                    return false
                }
            }
        }

        return true
    }

    override fun killed() {
        dead = true
        Events.fire(BlockDestroyEvent(tile))
        onDestroyed()
        if (tile !== Vars.emptyTile) {
            tile!!.remove()
        }
        remove()
        afterDestroyed()
    }

    fun checkAllowUpdate() {
        if (!allowUpdate()) {
            enabled = false
        }
    }

    @Final
    @Replace
    override fun update() {
        //TODO refactor to separate loop?
        timeScaleDuration -= Time.delta
        if (timeScaleDuration <= 0f) {
            timeScale = 1f
        }

        updateConsumption()

        if (enabled || !block!!.noUpdateDisabled) {
            updateTile()
        }
    }

    /** When a block is newly revealed outside of camera view range, it is updated on the minimap. */
    fun updateFogVisibility() {
        if (!wasVisible && !inFogTo(Vars.player.team())) {
            visibleFlags = visibleFlags or (1L shl Vars.player.team().id)
            wasVisible = true
            Vars.renderer.blocks.updateShadow(self())
            Vars.renderer.minimap.update(tile!!)
            if (block!!.drawCached) recache()
        }
    }

    override fun getAmbientVolume(): Float {
        return block!!.ambientSoundVolume * ambientVolume()
    }

    override fun getAmbientSound(): Sound {
        return block!!.ambientSound
    }

    override fun hitbox(out: Rect) {
        out.setCentered(x, y, block!!.size * Vars.tilesize, block!!.size * Vars.tilesize)
    }

    @Replace
    override fun toString(): String {
        return "Building#" + id() + "[" + tileX() + "," + tileY() + "]:" + block
    }
}
