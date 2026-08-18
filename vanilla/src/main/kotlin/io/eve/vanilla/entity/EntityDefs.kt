package io.eve.vanilla.entity

import io.eve.ktannot.EntityDef
import io.eve.ktannot.GroupDef
import io.eve.vanilla.comp.*

/**
 * 组合实体:带位置、血量、碰撞箱、速度、旋转、绘制、定时。
 * 对标原版 UnitEntity 的组件组合。
 */
@EntityDef(
    [PosComp::class, TeamComp::class, HealthComp::class, HitboxComp::class,
     VelComp::class, RotComp::class, DrawComp::class, TimedComp::class],
    serialize = true, isFinal = true
)
abstract class UnitDef

/**
 * 组合实体:带位置、血量的简易实体(无碰撞箱,无速度)。
 */
@EntityDef([PosComp::class, HealthComp::class], serialize = true, isFinal = true)
abstract class SimpleEntityDef

// 组定义:位置组,空间索引
@GroupDef([PosComp::class], spatial = true, mapping = true)
abstract class gpos

