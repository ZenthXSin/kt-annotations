package io.eve.vanilla.comp

import io.eve.ktannot.*
import io.eve.vanilla.gen.*
import arc.graphics.*
import arc.graphics.g2d.*
import arc.math.*
import arc.util.*
import kotlin.jvm.Transient

@Component
abstract class LaunchCoreComp : Drawc, Timedc {
    @Import var x = 0f
    @Import var y = 0f

    // renamed from `in` (Kotlin keyword) to `interval`
    @Transient var timer = Interval()

    lateinit var block: mindustry.world.Block

    override fun draw() {
        val alpha = fout(Interp.pow5Out)
        var scale = (1f - alpha) * 1.4f + 1f
        val cx = cx()
        val cy = cy()
        val rotation = fin() * (140f + Mathf.randomSeedRange(id(), 50f))

        Draw.z(mindustry.graphics.Layer.effect + 0.001f)

        Draw.color(mindustry.graphics.Pal.engine)

        val rad = 0.2f + fslope()
        val rscl = (block.size - 1) * 0.85f

        Fill.light(cx, cy, 10, 25f * (rad + scale - 1f) * rscl, Tmp.c2.set(mindustry.graphics.Pal.engine).a(alpha), Tmp.c1.set(mindustry.graphics.Pal.engine).a(0f))

        Draw.alpha(alpha)
        for (i in 0 until 4) {
            mindustry.graphics.Drawf.tri(cx, cy, 6f * rscl, 40f * (rad + scale - 1f) * rscl, i * 90f + rotation)
        }

        Draw.color()

        Draw.z(mindustry.graphics.Layer.weather - 1)

        val region = block.fullIcon
        scale *= region.scl()
        val rw = region.width * scale
        val rh = region.height * scale

        Draw.alpha(alpha)
        Draw.rect(region, cx, cy, rw, rh, rotation - 45)

        Tmp.v1.trns(225f, fin(Interp.pow3In) * 250f)

        Draw.z(mindustry.graphics.Layer.flyingUnit + 1)
        Draw.color(0, 0, 0, 0.22f * alpha)
        Draw.rect(region, cx + Tmp.v1.x, cy + Tmp.v1.y, rw, rh, rotation - 45)

        Draw.reset()
    }

    private fun cx(): Float {
        return x + fin(Interp.pow2In) * (12f + Mathf.randomSeedRange(id() + 3, 4f))
    }

    private fun cy(): Float {
        return y + fin(Interp.pow5In) * (100f + Mathf.randomSeedRange(id() + 2, 30f))
    }

    override fun update() {
        val r = 4f
        if (timer.get(3f - fin() * 2f)) {
            mindustry.content.Fx.rocketSmokeLarge.at(cx() + Mathf.range(r), cy() + Mathf.range(r), fin())
        }
    }
}