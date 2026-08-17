package mindustry.ctype

// ---- 桩类:模拟 Mindustry 内容系统,使生成代码在本工程可独立编译 ----

open class Content {
    open var id: Int = 0
}

open class MappableContent(val name: String, id: Int = 0) : Content() {
    override var id: Int = id
    override fun toString(): String = name
}
