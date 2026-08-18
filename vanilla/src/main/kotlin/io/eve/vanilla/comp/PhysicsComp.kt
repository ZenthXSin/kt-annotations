package io.eve.vanilla.comp

import io.eve.ktannot.*
import io.eve.vanilla.gen.*
import kotlin.jvm.Transient
import arc.math.Mathf
import arc.math.geom.Vec2

/**
 * 物理组件（对标原版 PhysicsComp）。
 * 受物理影响,与相似高度的物体碰撞反弹,具有质量。
 * 依赖:Velc, Hitboxc。
 */
@Component
abstract class PhysicsComp : Velc, Hitboxc {
    @Import var hitSize = 0f
    @Import var x = 0f
    @Import var y = 0f
    @Import var vel = Vec2()

    // 简化:PhysicRef 是原版 async.PhysicsProcess 的内部引用,这里用 Any 占位
    @Transient var physref: Any? = null

    /** 质量 = 物体面积 */
    fun mass(): Float {
        return hitSize * hitSize * Mathf.pi
    }

    fun impulse(x: Float, y: Float) {
        val mass = mass()
        vel.add(x / mass, y / mass)
    }

    fun impulse(v: Vec2) {
        impulse(v.x, v.y)
    }

    fun impulseNet(v: Vec2) {
        impulse(v.x, v.y)

        // 手动移动单位以模拟远程玩家的速度
        if (isRemote()) {
            val mass = mass()
            move(v.x / mass, v.y / mass)
        }
    }
}