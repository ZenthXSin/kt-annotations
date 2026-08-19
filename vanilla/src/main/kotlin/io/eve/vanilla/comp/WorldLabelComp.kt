package io.eve.vanilla.comp

import io.eve.ktannot.*
import io.eve.vanilla.gen.*
import arc.graphics.Color
import arc.graphics.g2d.Draw
import arc.graphics.g2d.Fill
import arc.graphics.g2d.Font
import arc.graphics.g2d.GlyphLayout
import arc.math.geom.Posc
import arc.scene.ui.layout.Scl
import arc.util.pooling.Pools
import arc.util.Time
import mindustry.Vars
import mindustry.annotations.Annotations.*
import mindustry.gen.*
import mindustry.graphics.Layer
import mindustry.ui.Fonts

/** Component/entity for labels in world space. Useful for servers. Does not save in files - create only on world load. */
@Component
abstract class WorldLabelComp : Posc, Drawc, Syncc {
    @Import var id = 0
    @Import var x = 0f
    @Import var y = 0f
    @Import var added = false

    var text = "sample text"
    var fontSize = 1f
    var z = Layer.playerName + 1
    /** Flags are packed into a byte for sync efficiency; see the flag static values. */
    var flags: Byte = (flagBackground or flagOutline).toByte()
    /** If not null, this label gets set to the parent position with x, y used as offsets. */
    var parent: Posc? = null
    /** Duration in seconds. Ignored if negative */
    @Transient var duration = -1f
    @Transient var expired: Runnable? = null

    @Replace
    override fun clipSize(): Float {
        if (parent != null) return Float.MAX_VALUE
        return if (text == null) 0f else text.length * 10f * fontSize
    }

    override fun update() {
        if (duration >= 0) {
            duration -= Time.delta / 60f
            if (duration <= 0) {
                hide()
                expired?.run()
            }
        }
    }

    override fun draw() {
        var px = x
        var py = y
        if (parent != null) {
            px += parent!!.x()
            py += parent!!.y()
        }
        drawAt(text, px, py, z, flags.toInt(), fontSize, Align.center,
            if (flags.toInt() and flagAlignLeft != 0) Align.left
            else if (flags.toInt() and flagAlignRight != 0) Align.right
            else Align.center)
    }

    /** Makes this label visible only to the specific player. This must be called instead of add(). */
    fun show(player: Player) {
        if (added || player.con == null) return
        player.con.localEntities.add(this)
        added = true
    }

    /** Hides this player-specific label. If you used [show] previously, you must call this method instead of [hide]! */
    fun hide(player: Player) {
        if (!added || player.con == null) return
        player.con.localEntities.remove(this)
        Call.removeWorldLabel(player.con, id)
        added = false
    }

    /** This MUST be called instead of remove()! */
    fun hide() {
        remove()
        Call.removeWorldLabel(id)
    }

    companion object {
        const val flagBackground = 1 shl 0
        const val flagOutline = 1 shl 1
        const val flagAlignLeft = 1 shl 2
        const val flagAlignRight = 1 shl 3
        const val flagAutoscale = 1 shl 4

        fun drawAt(text: String, x: Float, y: Float, layer: Float, flags: Int, fontSize: Float, align: Int, lineAlign: Int) {
            if (text == null) return
            Draw.z(layer)
            val z = Drawf.text()

            val font: Font = if (flags and flagOutline != 0) Fonts.outline else Fonts.def
            val layout = Pools.obtain(GlyphLayout::class.java, GlyphLayout::new)

            val ints = font.usesIntegerPositions()
            font.setUseIntegerPositions(false)
            font.data.setScale(0.25f * fontSize / Scl.scl(1f) /
                (if (flags and flagAutoscale != 0) 0.2f * Vars.renderer.camerascale + 0.05f else 1f))
            layout.setText(font, text)

            val border = if (flags and flagBackground != 0) 1 else 0

            var dy = y
            var dx = x

            if (Align.isBottom(align)) {
                dy += layout.height + border * 1.5f
            } else if (Align.isTop(align)) {
                dy -= border * 1.5f
            } else {
                dy += layout.height / 2
            }

            if (Align.isLeft(align)) {
                dx += layout.width / 2 + border
            } else if (Align.isRight(align)) {
                dx -= layout.width / 2 + border
            }

            if (flags and flagBackground != 0) {
                Draw.color(0f, 0f, 0f, 0.3f)
                Fill.rect(dx, dy - layout.height / 2, layout.width + 2, layout.height + 3)
                Draw.color()
            }

            val tx = if (Align.isLeft(lineAlign)) -layout.width * 0.5f
            else if (Align.isRight(lineAlign)) layout.width * 0.5f
            else 0f

            font.color = Color.white
            font.draw(text, dx + tx, dy, 0, lineAlign, false)

            Draw.reset()
            Pools.free(layout)
            font.data.setScale(1f)
            font.color = Color.white
            font.setUseIntegerPositions(ints)

            Draw.z(z)
        }
    }
}