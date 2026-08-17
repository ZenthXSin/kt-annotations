package io.eve.ktannot.gen

import java.io.File

/**
 * 对标 StructProcess:生成"值类型"类,把字段打包进整数基元(byte/short/int/long)。
 * 完全复刻原版位运算逻辑(bitMask / get / set / get(fields...))。
 *
 * 手写文本生成而非 KotlinPoet:KotlinPoet 会把超长行自动 wrap,在 `|`/`+`
 * 运算符处换行,而 Kotlin 解析器对(本环境)行尾运算符 + 换行续行报错。
 */
object StructGenerator {

    fun generate(classes: List<KtClass>, outDir: File) {
        for (cls in classes.filter { it.annotations.containsKey("Struct") }) {
            try {
                generateOne(cls, outDir)
            } catch (e: Exception) {
                System.err.println("[kt-annot] Struct process failed for ${cls.fullName}: ${e.message}")
            }
        }
    }

    private fun generateOne(cls: KtClass, outDir: File) {
        if (!cls.name.endsWith("Struct")) {
            System.err.println("[kt-annot] All @Struct classes must end in 'Struct': ${cls.name}")
            return
        }
        val structName = cls.name.removeSuffix("Struct")
        val structParam = structName.lowercase()

        val fields = cls.fields
        if (fields.isEmpty()) {
            System.err.println("[kt-annot] making a struct with no fields is utterly pointless: ${cls.name}")
            return
        }

        val sizes = fields.map { varSize(it) }
        val structSize = sizes.sum()
        val structTotalSize = when {
            structSize <= 8 -> 8
            structSize <= 16 -> 16
            structSize <= 32 -> 32
            else -> 64
        }
        val structType = typeForSize(structSize)

        val sb = StringBuilder()
        val pkg = cls.packageName
        sb.append("package $pkg\n\n")
        val pkgPrefix = if (pkg.isEmpty()) "" else "$pkg."
        sb.append("public object $structName {\n")

        // doc
        sb.append("  /**\n   * Bits used: $structSize / $structTotalSize\n")
        var offset = 0
        for ((i, f) in fields.withIndex()) {
            val size = sizes[i]
            sb.append("   * <br>  ${f.name} [$offset..${offset + size}]\n")
            offset += size
        }
        sb.append("   */\n")

        // bitMasks
        for ((i, f) in fields.withIndex()) {
            val size = sizes[i]
            val off = prefixSum(sizes, i)
            val cap = f.name.replaceFirstChar { it.uppercase() }
            val init = if (f.type.isBoolean()) "(1L shl $off)" else "(0x${bitMaskHex(off, size, structTotalSize)}L)"
            sb.append("  public val bitMask$cap: $structType = $init\n")
        }

        // getters
        for ((i, f) in fields.withIndex()) {
            val size = sizes[i]
            val off = prefixSum(sizes, i)
            val cap = f.name.replaceFirstChar { it.uppercase() }
            val ret = structTypeName(f.type)
            sb.append("\n  public fun ${f.name}($structParam: $structType): $ret = ")
            if (f.type.isBoolean()) {
                sb.append("($structParam and (1L shl $off)) != 0L\n")
            } else if (f.type.isFloat()) {
                sb.append("Float.fromBits((($structParam ushr $off) and 0x${bitMaskHex(0, size, size)}L).toInt())\n")
            } else {
                sb.append("(($structParam ushr $off) and 0x${bitMaskHex(0, size, size)}L).to${fieldCastSuffix(f.type)}()\n")
            }
        }

        // setters
        for ((i, f) in fields.withIndex()) {
            val size = sizes[i]
            val off = prefixSum(sizes, i)
            val cap = f.name.replaceFirstChar { it.uppercase() }
            val ret = structTypeName(f.type)
            sb.append("\n  public fun ${f.name}($structParam: $structType, `value`: $ret): $structType = ")
            if (f.type.isBoolean()) {
                sb.append("if (value) ($structParam or (1L shl $off)) else ($structParam and (1L shl $off).inv())\n")
            } else if (f.type.isFloat()) {
                sb.append("(($structParam and (0x${bitMaskHex(off, size, structTotalSize)}L).inv()) or ((Float.floatToIntBits(value).toLong() shl $off) and 0x${bitMaskHex(off, size, structTotalSize)}L))\n")
            } else {
                sb.append("(($structParam and (0x${bitMaskHex(off, size, structTotalSize)}L).inv()) or ((value.toLong() shl $off) and 0x${bitMaskHex(off, size, structTotalSize)}L))\n")
            }
        }

        // constructor get(fields...) — 用语句块 + 中间变量,避免超长单行表达式
        sb.append("\n  public fun `get`(")
        for ((i, f) in fields.withIndex()) {
            sb.append("${f.name}: ${structTypeName(f.type)}")
            if (i != fields.lastIndex) sb.append(", ")
        }
        sb.append("): $structType {\n")
        for ((i, f) in fields.withIndex()) {
            val size = sizes[i]
            val off = prefixSum(sizes, i)
            val tmp = "v$i"
            val expr = when {
                f.type.isBoolean() -> "(if (${f.name}) (1L shl $off) else 0L)"
                f.type.isFloat() -> "((Float.floatToIntBits(${f.name}).toLong() shl $off) and 0x${bitMaskHex(off, size, structTotalSize)}L)"
                else -> "(((${f.name}.toLong()) shl $off) and 0x${bitMaskHex(off, size, structTotalSize)}L)"
            }
            sb.append("    val $tmp: $structType = $expr\n")
        }
        val acc = (0 until fields.size).joinToString(" or ") { "v$it" }
        sb.append("    return $acc\n  }\n}\n")

        val file = File(outDir, "${pkg.replace('.', '/')}/${structName}.kt")
        file.parentFile.mkdirs()
        file.writeText(sb.toString())
    }

    private fun prefixSum(sizes: List<Int>, i: Int): Int = sizes.subList(0, i).sum()

    private fun varSize(f: KtField): Int = when {
        f.type.isBoolean() -> 1
        f.type.isByte() -> 8
        f.type.isShort() -> 16
        f.type.isFloat() -> 32
        f.type.isInt() -> 32
        f.type.isLong() -> 64
        else -> throw IllegalArgumentException("Invalid struct field type: ${f.type}")
    }

    private fun typeForSize(size: Int): String = when {
        size <= 8 -> "Byte"
        size <= 16 -> "Short"
        size <= 32 -> "Int"
        else -> "Long"
    }

    private fun structTypeName(type: String): String = when {
        type.isBoolean() -> "Boolean"
        type.isByte() -> "Byte"
        type.isShort() -> "Short"
        type.isFloat() -> "Float"
        type.isInt() -> "Int"
        type.isLong() -> "Long"
        else -> throw IllegalArgumentException("Invalid struct field type: $type")
    }

    private fun bitMaskHex(offset: Int, size: Int, totalSize: Int): String {
        var value = java.math.BigInteger.ZERO
        var one = java.math.BigInteger.ONE
        for (i in 0 until totalSize) {
            if (i in offset until offset + size) value = value.or(one)
            one = one.shiftLeft(1)
        }
        return value.toString(16).uppercase()
    }

    private fun fieldCastSuffix(type: String): String = when {
        type.isByte() -> "Byte"
        type.isShort() -> "Short"
        type.isInt() -> "Int"
        type.isLong() -> "Long"
        else -> error("No cast suffix for $type")
    }

    private fun String.isBoolean() = this == "Boolean" || this == "boolean" || this == "kotlin.Boolean"
    private fun String.isFloat() = this == "Float" || this == "float" || this == "kotlin.Float"
    private fun String.isByte() = this == "Byte" || this == "byte" || this == "kotlin.Byte"
    private fun String.isShort() = this == "Short" || this == "short" || this == "kotlin.Short"
    private fun String.isInt() = this == "Int" || this == "int" || this == "kotlin.Int"
    private fun String.isLong() = this == "Long" || this == "long" || this == "kotlin.Long"
}