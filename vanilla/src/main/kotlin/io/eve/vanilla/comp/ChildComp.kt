package io.eve.vanilla.comp

import io.eve.ktannot.*

import io.eve.vanilla.gen.*
import arc.math.Angles
import mindustry.world.blocks.RotBlock

@Component
abstract class ChildComp : Posc, Rotc {
    @Import var x = 0f
    @Import var y = 0f
    @Import var rotation = 0f

    @Nullable
    var parent: Posc? = null
    var rotWithParent = false
    var offsetX = 0f
    var offsetY = 0f
    var offsetPos = 0f
    var offsetRot = 0f

    override fun add() {
        if (parent != null) {
            offsetX = x - parent.getX()
            offsetY = y - parent.getY()
            if (rotWithParent) {
                if (parent is Rotc) {
                    offsetPos = -parent.rotation()
                    offsetRot = rotation - parent.rotation()
                } else if (parent is RotBlock) {
                    offsetPos = -parent.buildRotation()
                    offsetRot = rotation - parent.buildRotation()
                }
            }
        }
    }

    override fun update() {
        if (parent != null) {
            if (rotWithParent) {
                if (parent is Rotc) {
                    x = parent.getX() + Angles.trnsx(parent.rotation() + offsetPos, offsetX, offsetY)
                    y = parent.getY() + Angles.trnsy(parent.rotation() + offsetPos, offsetX, offsetY)
                    rotation = parent.rotation() + offsetRot
                } else if (parent is RotBlock) {
                    x = parent.getX() + Angles.trnsx(parent.buildRotation() + offsetPos, offsetX, offsetY)
                    y = parent.getY() + Angles.trnsy(parent.buildRotation() + offsetPos, offsetX, offsetY)
                    rotation = parent.buildRotation() + offsetRot
                }
            } else {
                x = parent.getX() + offsetX
                y = parent.getY() + offsetY
            }
        }
    }
}