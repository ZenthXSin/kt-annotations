package io.eve.vanilla.comp

import io.eve.ktannot.*

import kotlin.jvm.Transient

@Component
abstract class TimerComp {
    @Transient var timer = arc.util.Interval(6)

    fun timer(index: Int, time: Float): Boolean {
        if (time.isInfinite()) return false
        return this.timer.get(index, time)
    }
}