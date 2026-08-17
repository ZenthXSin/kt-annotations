package mindustry.world

open class Block(name: String) : mindustry.ctype.MappableContent(name) {
    open var size: Int = 1
}

open class MyBlock(name: String) : Block(name) {
    @io.eve.ktannot.Load("block-@-icon")
    var icon: arc.graphics.g2d.TextureRegion? = null
}
