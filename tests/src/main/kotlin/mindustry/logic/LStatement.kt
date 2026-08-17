package mindustry.logic

open class LStatement {
    open fun afterRead() {}
    open fun write(out: StringBuilder) {}
    open fun read(tokens: Array<String>, length: Int) {}
}
