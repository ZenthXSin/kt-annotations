package io.eve.vanilla.comp

import io.eve.ktannot.*
import io.eve.vanilla.gen.*
import kotlin.jvm.Transient
import arc.graphics.Color
import arc.struct.Bits
import arc.struct.Seq
import arc.util.Time
import arc.util.Tmp
import arc.util.pooling.Pools
import mindustry.content.StatusEffects
import mindustry.ctype.ContentType
import mindustry.entities.units.StatusEntry

/**
 * 状态效果组件（对标原版 StatusComp）。
 * 管理单位身上的状态效果:叠加/移除/动态状态/属性倍率。
 * 依赖:Posc。
 */
@Component
abstract class StatusComp : Posc {
    private var statuses = Seq<StatusEntry>(4)

    // 简化:原版用 content.getBy(ContentType.status).size 作为 Bits 大小,这里用固定 64 位并加注释
    @Transient private var applied = Bits(64)

    // 这些被视为只读
    // 注意:armor 是特例;>= 0 时覆盖,否则忽略
    @Transient var speedMultiplier = 1f
    @Transient var damageMultiplier = 1f
    @Transient var healthMultiplier = 1f
    @Transient var reloadMultiplier = 1f
    @Transient var buildSpeedMultiplier = 1f
    @Transient var dragMultiplier = 1f
    @Transient var armorOverride = -1f
    @Transient var disarmed = false

    @Import var type: mindustry.type.UnitType? = null
    @Import var maxHealth = 1f

    /** 应用状态效果 1 tick（用于永久效果） */
    fun apply(effect: mindustry.type.StatusEffect) {
        apply(effect, 1)
    }

    /** 向此单位添加状态效果 */
    fun apply(effect: mindustry.type.StatusEffect, duration: Float) {
        if (effect == StatusEffects.none || effect == null || isImmune(effect)) return // 不应用空或免疫效果

        // 无论是否应用于友方单位,都解锁状态效果
        if (mindustry.Vars.state.isCampaign()) {
            effect.unlock()
        }

        if (statuses.size > 0) {
            // 检查相反效果
            for (i in 0 until statuses.size) {
                val entry = statuses.get(i)
                // 延长效果
                if (entry.effect == effect) {
                    entry.time = kotlin.math.max(entry.time, duration)
                    effect.applied(self(), entry.time, true)
                    return
                } else if (entry.effect.applyTransition(self(), effect, entry, duration)) { // 找到反应
                    // TODO 效果可能与其他多个效果反应
                    // 找到反应后停止查找
                    return
                }
            }
        }

        if (!effect.reactive) {
            // 否则,未找到相反效果,直接添加效果
            val entry = Pools.obtain(StatusEntry::class.java) { StatusEntry() }
            entry.damageTime = 0f
            entry.set(effect, duration)
            applied.set(effect.id)
            statuses.add(entry)
            effect.applied(self(), duration, false)
        }
    }

    fun getDuration(effect: mindustry.type.StatusEffect): Float {
        val entry = statuses.find { e -> e.effect == effect }
        return entry?.time ?: 0f
    }

    fun clearStatuses() {
        statuses.each { e -> e.effect.onRemoved(self()) }
        statuses.clear()
    }

    /** 移除状态效果 */
    fun unapply(effect: mindustry.type.StatusEffect) {
        statuses.remove { e: StatusEntry ->
            if (e.effect == effect) {
                e.effect.onRemoved(self())
                Pools.free(e)
                true
            } else {
                false
            }
        }
    }

    fun isBoss(): Boolean {
        return hasEffect(StatusEffects.boss)
    }

    fun isImmune(effect: mindustry.type.StatusEffect): Boolean {
        return type!!.immunities.contains(effect)
    }

    fun statusColor(): Color {
        if (statuses.size == 0) {
            return Tmp.c1.set(Color.white)
        }

        var r = 1f
        var g = 1f
        var b = 1f
        var total = 0f
        for (entry in statuses) {
            val intensity = if (entry.time < 10f) entry.time / 10f else 1f
            r += entry.effect.color.r * intensity
            g += entry.effect.color.g * intensity
            b += entry.effect.color.b * intensity
            total += intensity
        }
        val count = statuses.size + total
        return Tmp.c1.set(r / count, g / count, b / count, 1f)
    }

    /**
     * 应用动态状态效果,可自定义统计倍率。
     * @return 要写入倍率的 entry。如果动态状态已应用,返回之前的 entry。
     */
    fun applyDynamicStatus(): StatusEntry {
        if (hasEffect(StatusEffects.dynamic)) {
            val entry = statuses.find { s -> s.effect.dynamic }
            if (entry != null) return entry
        }

        val entry = Pools.obtain(StatusEntry::class.java) { StatusEntry() }
        entry.set(StatusEffects.dynamic, Float.POSITIVE_INFINITY)
        statuses.add(entry)
        applied.set(StatusEffects.dynamic.id)
        entry.effect.applied(self(), entry.time, false)
        return entry
    }

    /** 使用动态状态效果覆盖速度（tiles/秒） */
    fun statusSpeed(speed: Float) {
        // type.speed 不应为 0
        applyDynamicStatus().speedMultiplier = speed / (type!!.speed * 60f / mindustry.Vars.tilesize)
    }

    /** 使用动态状态效果改变伤害 */
    fun statusDamageMultiplier(damageMultiplier: Float) {
        applyDynamicStatus().damageMultiplier = damageMultiplier
    }

    /** 使用动态状态效果改变装填 */
    fun statusReloadMultiplier(reloadMultiplier: Float) {
        applyDynamicStatus().reloadMultiplier = reloadMultiplier
    }

    /** 使用动态状态效果覆盖最大生命值 */
    fun statusMaxHealth(health: Float) {
        // maxHealth 不应为 0
        applyDynamicStatus().healthMultiplier = health / maxHealth
    }

    /** 使用动态状态效果覆盖建造速度 */
    fun statusBuildSpeed(buildSpeed: Float) {
        // 建造速度不应为 0
        applyDynamicStatus().buildSpeedMultiplier = buildSpeed / type!!.buildSpeed
    }

    /** 使用动态状态效果覆盖阻力 */
    fun statusDrag(drag: Float) {
        // 防止除 0（drag 可以为 0,如果某人做了个坏的单位）
        applyDynamicStatus().dragMultiplier = if (type!!.drag == 0f) 0f else drag / type!!.drag
    }

    /** 使用动态状态效果覆盖护甲 */
    fun statusArmor(armor: Float) {
        applyDynamicStatus().armorOverride = armor
    }

    abstract fun isGrounded(): Boolean

    override fun update() {
        val floor = floorOn()
        if (isGrounded() && !type!!.hovering) {
            // 应用效果
            apply(floor.status, floor.statusDuration)
        }

        applied.clear()
        armorOverride = -1f
        speedMultiplier = 1f
        damageMultiplier = 1f
        healthMultiplier = 1f
        reloadMultiplier = 1f
        buildSpeedMultiplier = 1f
        dragMultiplier = 1f
        disarmed = false

        if (statuses.isEmpty()) return

        var index = 0

        while (index < statuses.size) {
            val entry = statuses.get(index++)
            entry.time = kotlin.math.max(entry.time - Time.delta, 0)

            if (entry.effect == null || (entry.time <= 0 && !entry.effect.permanent)) {
                if (entry.effect != null) {
                    entry.effect.onRemoved(self())
                }

                Pools.free(entry)
                index--
                statuses.remove(index)
            } else {
                applied.set(entry.effect.id)

                if (entry.effect.dynamic) {
                    speedMultiplier *= entry.speedMultiplier
                    healthMultiplier *= entry.healthMultiplier
                    damageMultiplier *= entry.damageMultiplier
                    reloadMultiplier *= entry.reloadMultiplier
                    buildSpeedMultiplier *= entry.buildSpeedMultiplier
                    dragMultiplier *= entry.dragMultiplier
                    // armor 是特例;许多单位将其设置为 0,因此使用 >= 0 的覆盖
                    if (entry.armorOverride >= 0f) armorOverride = entry.armorOverride
                } else {
                    speedMultiplier *= entry.effect.speedMultiplier
                    healthMultiplier *= entry.effect.healthMultiplier
                    damageMultiplier *= entry.effect.damageMultiplier
                    reloadMultiplier *= entry.effect.reloadMultiplier
                    buildSpeedMultiplier *= entry.effect.buildSpeedMultiplier
                    dragMultiplier *= entry.effect.dragMultiplier
                }

                disarmed = disarmed or entry.effect.disarm

                entry.effect.update(self(), entry)
            }
        }
    }

    fun statusBits(): Bits {
        return applied
    }

    fun draw() {
        for (e in statuses) {
            e.effect.draw(self(), e.time)
        }
    }

    fun hasEffect(effect: mindustry.type.StatusEffect): Boolean {
        return applied.get(effect.id)
    }
}