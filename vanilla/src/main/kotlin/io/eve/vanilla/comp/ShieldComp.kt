package io.eve.vanilla.comp

import io.eve.ktannot.*
import io.eve.vanilla.gen.*
import kotlin.jvm.Transient
import arc.graphics.Color

/**
 * 护盾组件（对标原版 ShieldComp）。
 * 吸收生命值伤害,护甲减伤,盾牌透明度动画。
 * 依赖:Healthc, Posc。
 */
@Component
abstract class ShieldComp : Healthc, Posc {
    @Import var health = 0f
    @Import var hitTime = 0f
    @Import var x = 0f
    @Import var y = 0f
    @Import var healthMultiplier = 1f
    @Import var armorOverride = -1f
    @Import var dead = false
    @Import var team = mindustry.game.Team.derelict

    /** 吸收生命值伤害 */
    var shield = 0f
    /** 从伤害中扣除的数值,无需保存 */
    @Transient var armor = 0f
    /** 盾牌透明度 */
    @Transient var shieldAlpha = 0f

    @Replace
    override fun damage(amount: Float) {
        // 应用护甲和缩放效果
        rawDamage(mindustry.entities.Damage.applyArmor(amount, if (armorOverride >= 0f) armorOverride else armor) / healthMultiplier / mindustry.Vars.state.rules.unitHealth(team))
    }

    @Replace
    override fun damagePierce(amount: Float, withEffect: Boolean) {
        val pre = hitTime
        rawDamage(amount / healthMultiplier / mindustry.Vars.state.rules.unitHealth(team))
        if (!withEffect) {
            hitTime = pre
        }
    }

    @Replace
    override fun damageArmorMult(amount: Float, armorMult: Float, withEffect: Boolean) {
        val pre = hitTime
        rawDamage(mindustry.entities.Damage.applyArmor(amount, if (armorOverride >= 0f) armorOverride * armorMult else armor * armorMult) / healthMultiplier / mindustry.Vars.state.rules.unitHealth(team))
        if (!withEffect) {
            hitTime = pre
        }
    }

    protected fun rawDamage(amount: Float) {
        val hadShields = shield > 0.0001f

        if (health.isNaN()) health = 0f

        if (hadShields) {
            shieldAlpha = 1f
        }

        val shieldDamage = kotlin.math.min(kotlin.math.max(shield, 0), amount)
        shield -= shieldDamage
        hitTime = 1f
        val remaining = amount - shieldDamage

        if (remaining > 0) {
            health -= remaining
            if (health <= 0 && !dead) {
                kill()
            }

            if (hadShields && shield <= 0.0001f) {
                mindustry.content.Fx.unitShieldBreak.at(x, y, 0, mindustry.graphics.Pal.shield, this)
            }
        }
    }

    override fun update() {
        shieldAlpha -= arc.util.Time.delta / 15f
        if (shieldAlpha < 0) shieldAlpha = 0f
    }
}