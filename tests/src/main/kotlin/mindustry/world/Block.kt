package mindustry.world

open class Block(name: String) : mindustry.ctype.MappableContent(name) {
    open var size: Int = 1
}

open class MyBlock(name: String) : Block(name) {
    @io.eve.ktannot.Load("block-@-icon")
    var icon: arc.graphics.g2d.TextureRegion? = null

    @io.eve.ktannot.Load(value = "block-@-#1-#2", lengths = [2, 3])
    var frames: Array<Array<arc.graphics.g2d.TextureRegion>> = Array(2) { Array(3) { arc.graphics.g2d.TextureRegion() } }
}
