package io.eve.vanilla.comp

import io.eve.ktannot.*
import io.eve.vanilla.gen.*
import arc.graphics.*
import arc.graphics.g2d.*
import arc.math.*

@Component
abstract class DecalComp : Drawc, Timedc, Rotc, Posc {
    @Import var x = 0f
    @Import var y = 0f
    @Import var rotation = 0f

    var color = Color(1f, 1f, 1f, 1f)
    var region: TextureRegion? = null

    override fun draw() {
        Draw.z(mindustry.graphics.Layer.scorch)
        Draw.mixcol(color, color.a)
        Draw.alpha(1f - Mathf.curve(fin(), 0.98f))
        Draw.rect(region!!, x, y, rotation)
        Draw.reset()
    }

    @Replace
    fun clipSize(): Float {
        return region!!.width * 2f
    }
}