package io.eve.vanilla.comp

import io.eve.ktannot.*

import io.eve.vanilla.gen.*

@Component
abstract class ShielderComp : Damagec, Teamc, Posc {
    fun absorb() {
    }
}