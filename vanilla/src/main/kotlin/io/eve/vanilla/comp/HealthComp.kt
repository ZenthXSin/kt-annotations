package io.eve.vanilla.comp

import io.eve.ktannot.*

import io.eve.vanilla.gen.*
import kotlin.jvm.Transient

@Component
abstract class HealthComp : Entityc, Posc {
    companion object {
        const val hitDuration = 9f
    }

    var health = 0f
    @Transient var hitTime = 0f
    @Transient var maxHealth = 1f
    @Transient var dead = false

    fun isValid(): Boolean {
        return !dead && isAdded()
    }

    fun healthf(): Float {
        return health / maxHealth
    }

    override fun update() {
        hitTime -= arc.util.Time.delta / hitDuration
    }

    fun killed() {
        //implement by other components
    }

    fun kill() {
        if (dead) return

        health = health.coerceAtMost(0f)
        dead = true
        killed()
        remove()
    }

    fun heal() {
        dead = false
        health = maxHealth
    }

    fun damaged(): Boolean {
        return health < maxHealth - 0.001f
    }

    fun damagePierce(amount: Float, withEffect: Boolean) {
        damage(amount, withEffect)
    }

    fun damagePierce(amount: Float) {
        damagePierce(amount, true)
    }

    fun damageArmorMult(amount: Float, armorMult: Float, withEffect: Boolean) {
        damage(amount, withEffect)
    }

    fun damageArmorMult(amount: Float, armorMult: Float) {
        damageArmorMult(amount, armorMult, true)
    }

    fun damage(amount: Float) {
        if (health.isNaN()) health = 0f

        health -= amount
        hitTime = 1f
        if (health <= 0 && !dead) {
            kill()
        }
    }

    fun damage(amount: Float, withEffect: Boolean) {
        val pre = hitTime

        damage(amount)

        if (!withEffect) {
            hitTime = pre
        }
    }

    fun damageContinuous(amount: Float) {
        damage(amount * arc.util.Time.delta, hitTime <= -10 + hitDuration)
    }

    fun damageContinuousPierce(amount: Float) {
        damagePierce(amount * arc.util.Time.delta, hitTime <= -20 + hitDuration)
    }

    fun damageContinuousArmorMult(amount: Float, armorMult: Float) {
        damageArmorMult(amount * arc.util.Time.delta, armorMult, hitTime <= -20 + hitDuration)
    }

    fun clampHealth() {
        health = health.coerceAtMost(maxHealth)
        if (health.isNaN()) health = 0f
    }

    fun heal(amount: Float) {
        health += amount
        clampHealth()
    }

    fun healFract(amount: Float) {
        heal(amount * maxHealth)
    }
}