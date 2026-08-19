package io.eve.vanilla.comp

import io.eve.ktannot.*
import io.eve.vanilla.gen.*
import kotlin.jvm.Transient
import arc.func.Cons
import arc.math.Mathf
import arc.math.geom.Vec2
import arc.scene.ui.layout.Table
import arc.struct.Seq
import arc.util.Tmp
import mindustry.Vars
import mindustry.content.Fx
import mindustry.content.Sounds
import mindustry.core.World
import mindustry.entities.Units
import mindustry.game.EventType
import mindustry.game.EventType.PickupEvent
import mindustry.game.EventType.PayloadDropEvent
import mindustry.gen.Events
import mindustry.game.Team
import mindustry.gen.Building
import mindustry.gen.Unit
import mindustry.type.UnitType
import mindustry.world.blocks.payloads.BuildPayload
import mindustry.world.blocks.payloads.Payload
import mindustry.world.blocks.payloads.UnitPayload
import mindustry.world.blocks.power.PowerGraph
import mindustry.world.modules.ItemModule
import mindustry.world.modules.LiquidModule

/** An entity that holds a payload. */
@Component
abstract class PayloadComp : Posc, Rotc, Hitboxc, Unitc {
    @Import var x = 0f
    @Import var y = 0f
    @Import var rotation = 0f
    @Import var team = Team.derelict
    @Import var type: UnitType? = null

    var payloads = Seq<Payload>()

    @Transient var payloadPower: PowerGraph? = null

    override fun update() {
        if (payloadPower != null) {
            payloadPower!!.clear()
        }

        //update power graph first, resolve everything
        for (pay in payloads) {
            if (pay is BuildPayload && pay.build.power != null) {
                if (payloadPower == null) payloadPower = PowerGraph(false)
                pay.build.power.graph = null
                payloadPower!!.add(pay.build)
            }
        }

        payloadPower?.update()

        for (pay in payloads) {
            pay.set(x, y, rotation)
            pay.update(self(), null)
        }
    }

    override fun remove() {
        for (pay in payloads) {
            pay.remove()
        }
        payloads.clear()
    }

    fun destroy() {
        if (Vars.state.rules.unitPayloadsExplode) payloads.each { it.destroyed() }
    }

    fun payloadUsed(): Float = payloads.sumf { it.size() * it.size() }

    fun canPickup(unit: Unit): Boolean {
        return type!!.pickupUnits && payloadUsed() + unit.hitSize * unit.hitSize <= type!!.payloadCapacity + 0.001f && unit.team == team() && unit.isAI() && unit.type.allowedInPayloads
    }

    fun canPickup(build: Building): Boolean {
        return payloadUsed() + build.block.size * build.block.size * Vars.tilesize * Vars.tilesize <= type!!.payloadCapacity + 0.001f && build.canPickup() && build.team == team
    }

    fun canPickupPayload(pay: Payload): Boolean {
        return payloadUsed() + pay.size() * pay.size() <= type!!.payloadCapacity + 0.001f && (type!!.pickupUnits || pay !is UnitPayload)
    }

    fun hasPayload(): Boolean = payloads.size > 0

    fun addPayload(load: Payload) {
        payloads.add(load)
    }

    fun pickup(unit: Unit) {
        if (unit.isAdded()) unit.team.data().updateCount(unit.type, 1)
        unit.remove()
        addPayload(UnitPayload(unit))
        Fx.unitPickup.at(unit)
        if (Vars.net.client()) {
            Vars.netClient.clearRemovedEntity(unit.id)
        }
        Sounds.payloadPickup.at(self(), Mathf.random(0.9f, 1.1f))
        Events.fire(PickupEvent(self(), unit))
    }

    fun pickup(tile: Building) {
        tile.pickedUp()
        tile.tile.remove()
        tile.afterPickedUp()
        addPayload(BuildPayload(tile))
        Fx.unitPickup.at(tile)
        Sounds.payloadPickup.at(self())
        Events.fire(PickupEvent(self(), tile))
    }

    fun dropLastPayload(): Boolean {
        if (payloads.isEmpty()) return false
        val load = payloads.peek()
        if (tryDropPayload(load)) {
            payloads.pop()
            return true
        }
        return false
    }

    fun tryDropPayload(payload: Payload): Boolean {
        val on = tileOn()

        //clear removed state of unit so it can be synced
        if (Vars.net.client() && payload is UnitPayload) {
            Vars.netClient.clearRemovedEntity(payload.unit.id)
        }

        //drop off payload on an acceptor if possible
        if (on != null && on.build != null && on.build.team == team && on.build.acceptPayload(on.build, payload)) {
            Fx.unitDrop.at(on.build)
            on.build.handlePayload(on.build, payload)
            return true
        }

        return when (payload) {
            is BuildPayload -> dropBlock(payload)
            is UnitPayload -> dropUnit(payload)
            else -> false
        }
    }

    fun canDropPayload(): Boolean {
        if (payloads.isEmpty()) return false
        val payload = payloads.peek()
        val on = tileOn()

        if (on != null && on.build != null && on.build.team == team && on.build.acceptPayload(on.build, payload)) return true

        return when (payload) {
            is BuildPayload -> {
                val tile = payload.build
                val tx = World.toTile(x - tile.block.offset)
                val ty = World.toTile(y - tile.block.offset)
                val onTile = Vars.world.tile(tx, ty)
                onTile != null && mindustry.world.Build.validPlace(tile.block, tile.team, tx, ty, tile.rotation, false)
            }
            is UnitPayload -> {
                val u = payload.unit
                !(!u.canPass(World.toTile(x + Tmp.v1.x), World.toTile(y + Tmp.v1.y)) || Units.count(x, y, u.physicSize()) { o -> o.isGrounded() && o.hitSize > 14f } > 1)
            }
            else -> false
        }
    }

    fun dropUnit(payload: UnitPayload): Boolean {
        val u = payload.unit

        //add random offset to prevent unit stacking
        Tmp.v1.rnd(Mathf.random(2f))

        //can't drop ground units
        //allow stacking for small units for now - otherwise, unit transfer would get annoying
        if (!u.canPass(World.toTile(x + Tmp.v1.x), World.toTile(y + Tmp.v1.y)) || Units.count(x, y, u.physicSize()) { o -> o.isGrounded() && o.hitSize > 14f } > 1) {
            return false
        }

        Fx.unitDrop.at(this)

        //clients do not drop payloads
        if (Vars.net.client()) return true

        u.set(x + Tmp.v1.x, y + Tmp.v1.y)
        u.rotation(rotation)
        //reset the ID to a new value to make sure it's synced
        u.id = mindustry.entities.EntityGroup.nextId()
        //decrement count to prevent double increment
        if (!u.isAdded()) u.team.data().updateCount(u.type, -1)
        u.add()
        u.unloaded()
        val dropSound = when {
            payload.size() <= 12f -> Sounds.payloadDrop1
            payload.size() <= 20f -> Sounds.payloadDrop2
            else -> Sounds.payloadDrop3
        }
        dropSound.at(self(), Mathf.random(0.9f, 1.1f))
        Events.fire(PayloadDropEvent(self(), u))

        return true
    }

    /** @return whether the tile has been successfully placed. */
    fun dropBlock(payload: BuildPayload): Boolean {
        val tile = payload.build
        val tx = World.toTile(x - tile.block.offset)
        val ty = World.toTile(y - tile.block.offset)
        val on = Vars.world.tile(tx, ty)
        if (on != null && mindustry.world.Build.validPlace(tile.block, tile.team, tx, ty, tile.rotation, false)) {
            payload.place(on, tile.rotation)
            Events.fire(PayloadDropEvent(self(), tile))

            if (getControllerName() != null) {
                payload.build.lastAccessed = getControllerName()
            }

            Fx.unitDrop.at(tile)
            on.block().placeEffect.at(on.drawx(), on.drawy(), on.block().size.toFloat())
            on.block().placeSound.at(tile)
            return true
        }

        return false
    }

    fun contentInfo(table: Table, itemSize: Float, width: Float) {
        table.clear()
        table.top().left()

        var pad = 0f
        val items = payloads.size.toFloat()
        if (itemSize * items + pad * items > width) {
            pad = (width - itemSize * items) / items
        }

        for (p in payloads) {
            table.image(p.icon()).size(itemSize).padRight(pad)
        }
    }
}
