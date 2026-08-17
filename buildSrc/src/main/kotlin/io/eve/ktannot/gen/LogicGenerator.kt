package io.eve.ktannot.gen

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import java.io.File

/**
 * 对标 LogicStatementProcessor:生成 LogicIO 类,
 * 通过注册 @RegisterStatement 注解的类,自动生成 write(obj, out) / read(tokens, length) 方法。
 */
object LogicGenerator {

    fun generate(classes: List<KtClass>, outDir: File) {
        val types = classes.filter { it.annotations.containsKey("RegisterStatement") }
        if (types.isEmpty()) return

        val objType = ANY
        val stringBuilder = ClassName("kotlin.text", "StringBuilder")
        val stringArray = ClassName("kotlin", "Array").parameterizedBy(STRING)

        val writer = FunSpec.builder("write")
            .addModifiers(KModifier.PUBLIC)
            .addParameter("obj", objType)
            .addParameter("out", stringBuilder)
        val reader = FunSpec.builder("read")
            .addModifiers(KModifier.PUBLIC)
            .returns(ClassName("mindustry.logic", "LStatement").copy(nullable = true))
            .addParameter("tokens", stringArray)
            .addParameter("length", Int::class)

        // allStatements
        val provType = ClassName("arc.func", "Prov")
        val lStatement = ClassName("mindustry.logic", "LStatement")
        val seqType = ClassName("arc.struct", "Seq")
        val seqOf = seqType.parameterizedBy(provType.parameterizedBy(lStatement))

        val statInit = types.joinToString(", ") { t -> "Prov { ${t.fullName}() }" }
        val allStatements = PropertySpec.builder("allStatements", seqOf, KModifier.PUBLIC)
            .initializer("Seq.with($statInit)")
            .build()

        val first = types.first()
        val firstName = (first.annotations.getValue("RegisterStatement")["value"] ?: "").trim('"')
        // writer: if (obj.javaClass == X) {...} else if ... — 用一次 begin + N-1 next + 一次 end
        writer.beginControlFlow("if (obj.javaClass == %T::class.java)", ClassName.bestGuess(first.fullName))
        appendWriteBody(writer, first, firstName)
        for (t in types.drop(1)) {
            val name = (t.annotations.getValue("RegisterStatement")["value"] ?: "").trim('"')
            writer.nextControlFlow("else if (obj.javaClass == %T::class.java)", ClassName.bestGuess(t.fullName))
            appendWriteBody(writer, t, name)
        }
        writer.endControlFlow()

        // reader: if (tokens[0] == "x") {...} else if ... + 最后 return null
        reader.beginControlFlow("if (tokens[0] == %S)", firstName)
        appendReadBody(reader, first)
        for (t in types.drop(1)) {
            val name = (t.annotations.getValue("RegisterStatement")["value"] ?: "").trim('"')
            reader.nextControlFlow("else if (tokens[0] == %S)", name)
            appendReadBody(reader, t)
        }
        reader.endControlFlow()
        reader.addStatement("return null")

        val file = FileSpec.builder("io.eve.ktannot.gen", "LogicIO")
            .addType(
                TypeSpec.objectBuilder("LogicIO")
                    .addProperty(allStatements)
                    .addFunction(writer.build())
                    .addFunction(reader.build())
                    .build()
            )
            .build()
        file.writeTo(outDir)
    }

    private fun appendWriteBody(writer: FunSpec.Builder, t: KtClass, name: String) {
        writer.addStatement("out.append(%S)", name.trim('"'))
        for (f in t.fields) {
            if (f.isStatic || f.isTransient) continue
            writer.addStatement("out.append(%S)", " ")
            writer.addStatement("out.append((obj as ${t.fullName}).${f.name})")
        }
    }

    private fun appendReadBody(reader: FunSpec.Builder, t: KtClass) {
        reader.addStatement("val result = ${t.fullName}()")
        t.fields.forEachIndexed { index, f ->
            if (f.isStatic || f.isTransient) return@forEachIndexed
            val valueOf = when (f.type) {
                "Int", "int", "kotlin.Int" -> "toInt"
                "Float", "float", "kotlin.Float" -> "toFloat"
                "Boolean", "boolean", "kotlin.Boolean" -> "toBoolean"
                "Long", "long", "kotlin.Long" -> "toLong"
                "Short", "short", "kotlin.Short" -> "toShort"
                "Byte", "byte", "kotlin.Byte" -> "toByte"
                else -> "toString"
            }
            reader.addStatement("if (length > ${index + 1}) result.${f.name} = tokens[${index + 1}].$valueOf()")
        }
        reader.addStatement("result.afterRead()")
        reader.addStatement("return result")
    }
}