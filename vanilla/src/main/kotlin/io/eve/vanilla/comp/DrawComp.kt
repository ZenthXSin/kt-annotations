package io.eve.vanilla.comp

import io.eve.ktannot.*

import io.eve.vanilla.gen.*

@Component
abstract class DrawComp : Posc {
    fun clipSize(): Float = Float.MAX_VALUE

    fun draw() {
    }
}