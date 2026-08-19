package io.eve.vanilla.comp

import io.eve.ktannot.*
import io.eve.vanilla.gen.*
import arc.math.Angles
import arc.math.Mathf
import arc.math.geom.Vec2
import arc.util.Time
import arc.util.Tmp
import mindustry.ai.types.CommandAI
import mindustry.annotations.Annotations.*
import mindustry.async.PhysicsProcess
import mindustry.gen.*
import mindustry.type.UnitType

@Component
abstract class SegmentComp : Posc, Rotc, Hitboxc, Unitc, Segmentc {
    @Import var x = 0f
    @Import var y = 0f
    @Import var rotation = 0f
    @Import var type: UnitType? = null
    @Import var vel = Vec2()

    @Transient var parentSegment: Segmentc? = null
    @Transient var childSegment: Segmentc? = null
    @Transient var headSegment: Segmentc? = null
    @Transient var segmentIndex = 0

    var parentId = 0

    fun isHead(): Boolean = parentSegment == null

    fun addChild(other: Unit) {
        if (other === self()) return

        if (childSegment != null) {
            childSegment!!.parentSegment(null)
        }

        val seg = other as? Segmentc
        if (seg != null) {
            if (seg.parentSegment() != null) {
                seg.parentSegment()!!.childSegment(null)
            }

            childSegment = seg
            seg.parentSegment(this)
        }
    }

    @Replace
    override fun ignoreSolids(): Boolean {
        return isFlying() || parentSegment != null
    }

    override fun update() {
        if (childSegment != null && !childSegment!!.isValid()) {
            childSegment = null
        }

        if (parentSegment != null && !parentSegment!!.isValid()) {
            parentSegment = null
        }

        if (parentSegment == null) {
            segmentIndex = 0

            if (childSegment != null) {
                headSegment = this
                childSegment!!.updateSegment(this, this, 1)
            }
        }
    }

    @Replace
    override fun playerControllable(): Boolean {
        return type!!.playerControllable && isHead()
    }

    @Replace
    override fun shouldUpdateController(): Boolean {
        return isHead()
    }

    @Replace
    override fun moving(): Boolean {
        if (isHead()) {
            return !vel.isZero(0.01f)
        } else {
            return deltaLen() / Time.delta >= 0.01f
        }
    }

    @Replace
    override fun collisionLayer(): Int {
        if (parentSegment != null) return -1
        return if (type!!.allowLegStep && type!!.legPhysicsLayer) PhysicsProcess.layerLegs
        else if (isGrounded()) PhysicsProcess.layerGround
        else PhysicsProcess.layerFlying
    }

    @Replace
    override fun isCommandable(): Boolean {
        return parentSegment == null && controller() is CommandAI
    }

    override fun afterSync() {
        checkParent()
    }

    override fun afterReadAll() {
        checkParent()
    }

    override fun beforeWrite() {
        parentId = if (parentSegment == null) -1 else parentSegment!!.id()
    }

    fun checkParent() {
        if (parentId != -1) {
            val parent = Groups.unit.getByID(parentId)
            if (parent is Segmentc) {
                parentSegment = parent
                parent.childSegment(this)
                return
            }
            parentId = -1
        }
        parentSegment = null
    }

    fun updateSegment(head: Segmentc, parent: Segmentc, index: Int) {
        rotation = Angles.clampRange(rotation, parent.rotation(), type!!.segmentRotationRange)
        segmentIndex = index
        headSegment = head

        val headDelta = head.deltaLen()

        if (headDelta > 0.001f) {
            rotation = Mathf.slerpDelta(rotation, parent.rotation(), type!!.baseRotateSpeed * Mathf.clamp(headDelta / type!!.speed / Time.delta))
        }

        val moveVec = Tmp.v1.trns(rotation + 180f, type!!.segmentSpacing).add(parent.x(), parent.y()).sub(x, y)
        val prefSpeed = type!!.speed * Time.delta * 9999f
        move(moveVec.limit(prefSpeed))

        if (childSegment != null) {
            childSegment!!.updateSegment(head, this, index + 1)
        }
    }
}