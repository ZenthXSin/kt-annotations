package io.eve.ktannot.testcontent

import io.eve.ktannot.*
import io.eve.ktannot.gen.Player
import arc.graphics.g2d.TextureRegion
import mindustry.logic.LStatement

// ============ 组件定义(对标 Mindustry 的 *Comp) ============

/** 基础组件:所有实体都有位置 */
@Component
abstract class PosComp {
    var x: Float = 0f
    var y: Float = 0f

    fun dist(other: PosComp): Float = 0f
}

/** 带同步字段的组件(名字含 Sync 触发同步逻辑) */
@Component(base = true)
abstract class SyncComp {
    @SyncField(true)
    var hp: Float = 100f

    @SyncField(false, clamped = true)
    var angle: Float = 0f
}

/** 继承 PosComp 的组件 */
@Component
abstract class UnitComp : PosComp() {
    var speed: Float = 1f

    fun move() {}
}

// ============ 实体定义 ============

@EntityDef([UnitComp::class, SyncComp::class])
abstract class UnitDef

// ============ 组 ============

@GroupDef(value = [UnitComp::class], collide = true, spatial = true)
abstract class gUnitGroup

// ============ Struct ============

@Struct
class CoordStruct {
    var x: Int = 0
    @StructField(8)
    var health: Byte = 0
    var alive: Boolean = false
}

// ============ Load ============

// MyBlock 用桩(mindustry.world.MyBlock),这里不再重复定义

// ============ Remote ============

object NetCalls {
    @Remote(targets = Loc.both, variants = Variant.both, called = Loc.both)
    fun ping(player: Player, id: Int) {}
}

// ============ Logic Statement ============

@RegisterStatement("print")
class PrintStatement : LStatement() {
    var text: String = ""
}