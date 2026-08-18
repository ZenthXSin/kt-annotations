package io.eve.vanilla.comp

import io.eve.ktannot.*
import io.eve.vanilla.gen.*
import arc.graphics.*

@Component
abstract class EffectStateComp : Posc, Drawc, Timedc, Rotc, Childc {
    @Import var time = 0f
    @Import var lifetime = 0f
    @Import var rotation = 0f
    @Import var x = 0f
    @Import var y = 0f
    @Import var id = 0

    var color = Color(Color.white)
    var effect: mindustry.entities.Effect? = null
    var data: Any? = null

    override fun draw() {
        lifetime = effect!!.render(id, color, time, lifetime, rotation, x, y, data)
    }

    @Replace
    fun clipSize(): Float {
        return effect!!.clip
    }
}