package io.eve.vanilla.comp

import io.eve.ktannot.*
import io.eve.vanilla.gen.*
import kotlin.jvm.Transient
import arc.Core
import arc.graphics.Color
import arc.graphics.g2d.Draw
import arc.graphics.g2d.Fill
import arc.graphics.g2d.Font
import arc.graphics.g2d.GlyphLayout
import arc.math.Interp
import arc.math.Mathf
import arc.math.geom.Vec2
import arc.scene.ui.layout.Align
import arc.scene.ui.layout.Scl
import arc.struct.Seq
import arc.util.Time
import arc.util.Tmp
import arc.util.pooling.Pools
import mindustry.Vars
import mindustry.ai.types.CommandAI
import mindustry.content.Fx
import mindustry.content.UnitTypes
import mindustry.entities.units.BuildPlan
import mindustry.entities.units.UnitController
import mindustry.game.EventType
import mindustry.game.EventType.UnitChangeEvent
import mindustry.game.Team
import mindustry.gen.BuildPlan
import mindustry.gen.Building
import mindustry.gen.Call
import mindustry.gen.Entityc
import mindustry.gen.Events
import mindustry.gen.Player
import mindustry.gen.Playerc
import mindustry.gen.Syncc
import mindustry.gen.Timerc
import mindustry.gen.Unit
import mindustry.gen.UnitController
import mindustry.graphics.Drawf
import mindustry.graphics.Layer
import mindustry.graphics.Pal
import mindustry.input.InputHandler
import mindustry.net.Administration
import mindustry.net.NetConnection
import mindustry.net.Packets
import mindustry.type.ItemStack
import mindustry.ui.Fonts
import mindustry.world.Block
import mindustry.world.blocks.storage.CoreBlock
import mindustry.world.blocks.storage.CoreBlock.CoreBuild
import mindustry.gen.Unit as UnitGen

@Component
abstract class PlayerComp : UnitController, Entityc, Syncc, Timerc, Drawc {
    companion object {
        private val deathDelay = 60f
        private val pingDuration = 20f * 60f
    }

    @Import var x = 0f
    @Import var y = 0f

    @ReadOnly var unit: Unit? = null
    @Transient var con: NetConnection? = null
    @ReadOnly var team = Team.sharded
    @SyncLocal var typing = false
    @SyncLocal var shooting = false
    @SyncLocal var boosting = false
    @SyncLocal var selectedBlock: Block? = null
    @SyncLocal var selectedRotation = 0
    @SyncLocal var mouseX = 0f
    @SyncLocal var mouseY = 0f
    /** command the unit had before it was controlled. */
    var lastCommand: mindustry.ai.UnitCommand? = null
    var admin = false
    var name = "frog"
    var color = Color()

    @Transient var locale = "en"
    @Transient var deathTimer = 0f
    @Transient var lastText = ""
    @Transient var textFadeTime = 0f
    @Transient var itemDepositRate = arc.util.Ratekeeper()
    @Transient var pingX = 0f
    @Transient var pingY = 0f
    @Transient var pingTime = 0f
    @Transient var pingText: String? = null

    @Transient private var lastReadUnit: Unit? = null
    @Transient private var wrongReadUnits = 0
    @Transient var justSwitchFrom: Unit? = null
    @Transient var justSwitchTo: Unit? = null

    @Transient var lastPreviewPlanGroup = -1
    @Transient var lastPreviewPlanGroupServer = -1
    @Transient var lastPreviewPlanTimestamp = 0L
    @Transient var receivingNewPlanGroup = false
    @Transient var previewPlansCurrent = Seq<BuildPlan>(BuildPlan::class.java)
    @Transient var previewPlansAssembling = Seq<BuildPlan>(BuildPlan::class.java)
    @Transient var previewPlanTree: arc.math.geom.QuadTree<BuildPlan>? = null
    @Transient var planEachable: mindustry.input.InputHandler.QueryEachable? = null
    @Transient var previewPlansDirty = false

    fun getPreviewPlans(): Seq<BuildPlan> {
        val timeToCommit = 100L //ms needed after first plan is received to \"commit\" the plans.
        if (Time.timeSinceMillis(lastPreviewPlanTimestamp) >= timeToCommit && receivingNewPlanGroup) {
            receivingNewPlanGroup = false
            previewPlansDirty = true
            previewPlansCurrent.set(previewPlansAssembling)
            previewPlansAssembling.clear()
        }
        return previewPlansCurrent
    }

    fun handlePreviewPlans(groupId: Int, plans: Seq<BuildPlan>?) {
        if (groupId > lastPreviewPlanGroup) {
            previewPlansAssembling.clear()
            lastPreviewPlanGroup = groupId
            receivingNewPlanGroup = true
            lastPreviewPlanTimestamp = Time.millis()
        } else if (groupId < lastPreviewPlanGroup) {
            return
        } else if (!receivingNewPlanGroup) {
            return
        }

        if (plans == null) return

        val added = Math.min(plans.size, Vars.maxPlayerPreviewPlans - previewPlansAssembling.size)
        if (added > 0) {
            previewPlansAssembling.addAll(plans, 0, added)
        }
    }

    fun isBuilder(): Boolean {
        return unit != null && unit!!.canBuild()
    }

    fun closestCore(): CoreBuild? {
        return Vars.state.teams.closestCore(x, y, team)
    }

    fun core(): CoreBuild? {
        return team.core()
    }

    /** @return largest/closest core, with the largest cores getting priority */
    fun bestCore(): CoreBuild? {
        val cores = team.cores()
        return cores.min { b ->
            cores.size == 1 || (b.block as CoreBlock).unitType.supportsEnv(Vars.state.rules.env)
        } ?: null
    }

    @Suppress("UNCHECKED_CAST")
    private fun coresMin(cores: Seq<CoreBuild>): CoreBuild? {
        if (cores.isEmpty) return null
        var best = cores.first()
        var bestScore = -1
        for (i in 0 until cores.size) {
            val b = cores.get(i)
            val size = b.block.size
            val dist = b.dst2(x, y)
            val score = size * 10000 - dist.toInt()
            if (score > bestScore || (i == 0)) {
                bestScore = score
                best = b
            }
        }
        return best
    }

    fun icon(): arc.graphics.g2d.TextureRegion {
        if (dead()) {
            if (core() == null) {
                return UnitTypes.alpha.uiIcon
            }
            val bestCore = bestCore()
            if (bestCore == null) {
                return UnitTypes.alpha.uiIcon
            }
            return (bestCore.block as CoreBlock).unitType.uiIcon
        }
        return unit!!.icon()
    }

    fun displayAmmo(): Boolean {
        return unit is mindustry.gen.BlockUnitc
    }

    fun reset() {
        team = Vars.state.rules.defaultTeam
        admin = false
        typing = false
        textFadeTime = 0f
        x = 0f
        y = 0f
        lastPreviewPlanTimestamp = 0L
        lastPreviewPlanGroup = -1
        lastPreviewPlanGroupServer = -1
        previewPlanTree = null
        planEachable = null
        previewPlansCurrent.clear()
        previewPlansAssembling.clear()
        receivingNewPlanGroup = false
        previewPlansDirty = false
        if (!dead()) {
            unit!!.resetController()
            unit = null
        }
    }

    override fun isValidController(): Boolean {
        return isAdded()
    }

    override fun isLogicControllable(): Boolean {
        return false
    }

    @Replace
    fun clipSize(): Float {
        return Float.MAX_VALUE
    }

    override fun afterSync() {
        if (isLocal() && unit == justSwitchFrom && justSwitchFrom != null && justSwitchTo != null) {
            unit = justSwitchTo
            if (++wrongReadUnits >= 2) {
                justSwitchFrom = null
                wrongReadUnits = 0
            }
        } else {
            justSwitchFrom = null
            justSwitchTo = null
            wrongReadUnits = 0
        }

        val set = unit
        unit = lastReadUnit
        unit(set)
        lastReadUnit = unit
        if (unit != null) {
            unit!!.aim(mouseX, mouseY)
            unit!!.controlWeapons(shooting, shooting)
            unit!!.controller(this)
        }
    }

    override fun update() {
        if (unit != null && !unit!!.isValid()) {
            clearUnit()
        }

        val core: CoreBuild?

        if (!dead()) {
            set(unit)
            unit!!.team(team)
            deathTimer = 0f

            if (unit!!.type.canBoost) {
                val shouldBoost = unit!!.onSolid() || boosting || (unit!!.isFlying() && !unit!!.canLand())
                unit!!.elevation = Mathf.approachDelta(unit!!.elevation, if (shouldBoost) 1f else 0f, if (shouldBoost) unit!!.type.riseSpeed else unit!!.type.descentSpeed)
            }
        } else {
            core = bestCore()
            if (core != null) {
                deathTimer += Time.delta
                if (deathTimer >= deathDelay) {
                    core.requestSpawn(self())
                    deathTimer = 0f
                }
            }
        }

        textFadeTime -= Time.delta / (60f * 5f)
    }

    fun checkSpawn() {
        val core = bestCore()
        if (core != null) {
            core.requestSpawn(self())
        }
    }

    override fun remove() {
        if (unit != null) {
            clearUnit()
        }
        lastReadUnit = null
        justSwitchTo = null
        justSwitchFrom = null
    }

    fun team(team: Team) {
        this.team = team
        if (unit != null) {
            unit!!.team(team)
        }
    }

    fun clearUnit() {
        unit(null)
    }

    override fun unit(): Unit? {
        return unit
    }

    override fun unit(unit: Unit?) {
        if (isLocal() && unit == justSwitchFrom && justSwitchFrom != null && justSwitchTo != null) {
            return
        }

        if (this.unit == unit) return

        if (unit != null && unit!!.controller() is CommandAI) {
            val ai = unit!!.controller() as CommandAI
            lastCommand = ai.command
        }

        if (this.unit != null) {
            this.unit!!.resetController()
            if (lastCommand != null && this.unit!!.controller() is CommandAI) {
                val ai = this.unit!!.controller() as CommandAI
                ai.command(lastCommand!!)
            }
        }
        this.unit = unit
        if (unit != null) {
            unit!!.team(team)
            unit!!.controller(this)

            if (unit!!.isRemote() && !Vars.net.client()) {
                unit!!.snapInterpolation()
            }

            if (!Vars.headless && isLocal()) {
                Vars.control.input.block = null
            }
        }

        Events.fire(UnitChangeEvent(self(), unit))
    }

    fun dead(): Boolean {
        return unit == null || !unit!!.isValid()
    }

    fun ip(): String {
        return if (con == null) "localhost" else con!!.address
    }

    fun uuid(): String {
        return if (con == null) "[LOCAL]" else con!!.uuid
    }

    fun usid(): String {
        return if (con == null) "[LOCAL]" else con!!.usid
    }

    fun kick(reason: Administration.KickReason) {
        con!!.kick(reason)
    }

    fun kick(reason: Administration.KickReason, duration: Long) {
        con!!.kick(reason, duration)
    }

    fun kick(reason: String) {
        con!!.kick(reason)
    }

    fun kick(reason: String, duration: Long) {
        con!!.kick(reason, duration)
    }

    override fun draw() {
        drawPing()
        drawName()
    }

    fun isPinging(): Boolean {
        return pingTime > 0f
    }

    private fun drawPing() {
        if (pingTime <= 0f || !Vars.renderer.showPings || name == null || (!Vars.state.rules.showOtherTeamPings && team != Vars.player.team())) return

        val alpha = Math.min(
            Interp.pow5Out.apply(Mathf.clamp(Mathf.map(pingTime, 1f / 20f, 0f, 1f, 0f))),
            Interp.pow5Out.apply(Mathf.clamp(Mathf.map(pingTime, 1f, 0.98f, 0f, 1f)))
        )

        Tmp.c1.set(color).a(alpha)

        pingTime -= Time.delta / pingDuration

        val s = Scl.scl(4f) / Vars.renderer.getDisplayScale()

        Draw.z(Layer.playerName)
        val z = Drawf.text()
        val hover = Mathf.absin(5f, 1f)
        val scaling = 1f + Mathf.clamp(Interp.pow5In.apply(Mathf.map(pingTime, 1f, 0.96f, 1f, 0f))) * 3f

        Drawf.square(pingX, pingY, 2f * scaling * s, 45f, Tmp.c1, Tmp.c3.set(Color.darkGray).mul(color).a(Tmp.c1.a), s)
        Drawf.fillPoly(pingX, pingY + 9f * s + hover * s, 3, 3f * s, -90f, Tmp.c1, Tmp.c3, s)

        if (pingText != null) {
            Drawf.text(name, pingX, pingY + (20f + hover) * s, Tmp.c1, 0.7f * s)
            Drawf.text(pingText!!, pingX, pingY + (16f + hover) * s, Tmp.c2.set(1f, 1f, 1f, Tmp.c1.a), s)
        } else {
            Drawf.text(name, pingX, pingY + (16f + hover) * s, Tmp.c1, s)
        }

        Draw.reset()
        Draw.z(z)
    }

    private fun drawName() {
        if (unit == null || name == null) return

        val clip = unit!!.type.hitSize * 2f
        if (!Core.camera.bounds(Tmp.r1).overlaps(x - clip / 2f, y - clip / 2f, clip, clip)) return

        if (name == null || unit!!.inFogTo(Vars.player.team())) return

        Draw.z(Layer.playerName)
        val z = Drawf.text()

        val font = Fonts.outline
        val layout = Pools.obtain(GlyphLayout::class.java) { GlyphLayout() }
        val nameHeight = 11f
        val textHeight = 15f

        val ints = font.usesIntegerPositions()
        font.setUseIntegerPositions(false)
        font.data.setScale(0.25f / Scl.scl(1f))
        layout.setText(font, name)

        if (!isLocal()) {
            Draw.color(0f, 0f, 0f, 0.3f)
            Fill.rect(unit!!.x, unit!!.y + nameHeight - layout.height / 2f, layout.width + 2f, layout.height + 3f)
            Draw.color()
            font.setColor(color)
            font.draw(name, unit!!.x, unit!!.y + nameHeight, 0f, Align.center, false)

            if (admin) {
                val s = 3f
                Draw.color(color.r * 0.5f, color.g * 0.5f, color.b * 0.5f, 1f)
                Draw.rect(mindustry.ui.Icon.adminSmall.getRegion(), unit!!.x + layout.width / 2f + 2f + 1f, unit!!.y + nameHeight - 1.5f, s, s)
                Draw.color(color)
                Draw.rect(mindustry.ui.Icon.adminSmall.getRegion(), unit!!.x + layout.width / 2f + 2f + 1f, unit!!.y + nameHeight - 1f, s, s)
            }
        }

        if (Core.settings.getBool("playerchat") && ((textFadeTime > 0 && lastText != null) || typing)) {
            val text = if (textFadeTime <= 0 || lastText == null) "[lightgray]" + arc.util.Strings.animated(Time.time, 4, 15f, ".") else lastText
            val width = 100f
            val visualFadeTime = 1f - Mathf.curve(1f - textFadeTime, 0.9f)
            font.setColor(1f, 1f, 1f, if (textFadeTime <= 0 || lastText == null) 1f else visualFadeTime)

            layout.setText(font, text, Color.white, width, Align.bottom, true)

            Draw.color(0f, 0f, 0f, 0.3f * (if (textFadeTime <= 0 || lastText == null) 1f else visualFadeTime))
            Fill.rect(unit!!.x, unit!!.y + textHeight + layout.height - layout.height / 2f, layout.width + 2f, layout.height + 3f)
            font.draw(text, unit!!.x - width / 2f, unit!!.y + textHeight + layout.height, width, Align.center, true)
        }

        Draw.reset()
        Pools.free(layout)
        font.data.setScale(1f)
        font.setColor(Color.white)
        font.setUseIntegerPositions(ints)

        Draw.z(z)
    }

    /** @return name with a markup color prefix */
    fun coloredName(): String {
        return "[#" + color.toString().uppercase() + "]" + name
    }

    fun plainName(): String {
        return arc.util.Strings.stripColors(name)
    }

    fun sendMessage(text: String) {
        sendMessage(text, null, null)
    }

    fun sendMessage(text: String, from: Player?) {
        sendMessage(text, from, null)
    }

    fun sendMessage(text: String, from: Player?, unformatted: String?) {
        if (isLocal()) {
            if (Vars.ui != null) {
                Vars.ui.chatfrag.addMessage(text)
            }
        } else {
            Call.sendMessage(con, text, unformatted, from)
        }
    }

    fun sendUnformatted(unformatted: String) {
        sendUnformatted(null, unformatted)
    }

    fun sendUnformatted(from: Player?, unformatted: String) {
        sendMessage(Vars.netServer.chatFormatter.format(from, unformatted), from, unformatted)
    }

    fun getInfo(): Administration.PlayerInfo {
        if (isLocal()) {
            throw IllegalArgumentException("Local players cannot be traced and do not have info.")
        } else {
            return Vars.netServer.admins.getInfo(uuid())
        }
    }
}
