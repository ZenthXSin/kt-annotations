package io.eve.vanilla.comp

import io.eve.ktannot.*

import io.eve.vanilla.gen.*

@Component
abstract class TimedKillComp : Entityc, Healthc, arc.math.Scaled {
    var time = 0f
    var lifetime = 0f

    @MethodPriority(100)
    override fun update() {
        time = kotlin.math.min(time + arc.util.Time.delta, lifetime)

        if (time >= lifetime) {
            kill()
        }
    }

    override fun fin(): Float {
        return time / lifetime
    }
}