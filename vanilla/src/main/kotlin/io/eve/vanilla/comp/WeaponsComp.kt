package io.eve.vanilla.comp

import io.eve.ktannot.*
import io.eve.vanilla.gen.*
import kotlin.jvm.Transient
import arc.math.geom.Position
import arc.util.Tmp
import arc.struct.Seq
import mindustry.entities.units.WeaponMount

/**
 * 武器组件（对标原版 WeaponsComp）。
 * 管理单位的武器挂载、瞄准、射击控制。
 * 依赖:Teamc, Posc, Rotc, Velc, Statusc。
 */
@Component
abstract class WeaponsComp : Teamc, Posc, Rotc, Velc, Statusc {
    @Import var x = 0f
    @Import var y = 0f
    @Import var disarmed = false
    @Import var type: mindustry.type.UnitType? = null

    /** 武器挂载数组,永不为 null */
    var mounts = Seq<*>()

    @Transient var isRotate = false

    var aimX = 0f
    var aimY = 0f
    var isShooting = false

    fun setWeaponRotation(rotation: Float) {
        for (mount in mounts) {
            mount.rotation = rotation
        }
    }

    fun setupWeapons(def: mindustry.type.UnitType) {
        mounts = Seq<*>().also { s ->
            for (i in 0 until def.weapons.size) {
                s.add(def.weapons.get(i).mountType.get(def.weapons.get(i)))
            }
        }
    }

    fun controlWeapons(rotateShoot: Boolean) {
        controlWeapons(rotateShoot, rotateShoot)
    }

    fun controlWeapons(rotate: Boolean, shoot: Boolean) {
        for (mount in mounts) {
            if (mount.weapon.controllable) {
                mount.rotate = rotate
                mount.shoot = shoot
            }
        }
        isRotate = rotate
        isShooting = shoot
    }

    fun aim(pos: Position) {
        aim(pos.getX(), pos.getY())
    }

    /** 瞄准某物,所有挂载都会指向它 */
    fun aim(x: Float, y: Float) {
        Tmp.v1.set(x, y).sub(this.x, this.y)
        if (Tmp.v1.len() < type!!.aimDst) Tmp.v1.setLength(type!!.aimDst)

        val tx = Tmp.v1.x + this.x
        val ty = Tmp.v1.y + this.y

        for (mount in mounts) {
            if (mount.weapon.controllable) {
                mount.aimX = tx
                mount.aimY = ty
            }
        }

        aimX = tx
        aimY = ty
    }

    fun canShoot(): Boolean {
        return !disarmed
    }

    override fun remove() {
        for (mount in mounts) {
            if (mount.weapon.continuous && mount.bullet != null && mount.bullet.owner == self()) {
                mount.bullet.time = mount.bullet.lifetime - 10f
                mount.bullet = null
            }

            if (mount.sound != null) {
                mount.sound.stop()
            }
        }
    }

    /** 更新此单位的射击和旋转 */
    override fun update() {
        for (mount in mounts) {
            mount.weapon.update(self(), mount)
        }
    }
}