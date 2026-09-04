package io.eve.ktannot.gen

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.TypeSpec
import java.io.File

/** Generates ContentRegions.loadRegions(content) for fields annotated with @Load. */
object RegionGenerator {

    fun generate(
        classes: List<KtClass>,
        outDir: File,
        mindustryMode: Boolean = false,
        genPackage: String = "io.eve.ktannot.gen",
    ) {
        val loadFields = classes.flatMap { cls ->
            cls.fields.filter { it.annotations.containsKey("Load") }
                .map { Triple(cls, it, it.annotations.getValue("Load")) }
        }
        if (loadFields.isEmpty()) return

        val grouped = loadFields.groupBy { it.first.fullName }
        val atlas = if (mindustryMode) "arc.Core.atlas" else "Core.atlas"
        val fn = FunSpec.builder("loadRegions")
            .addParameter("content", ClassName("mindustry.ctype", "MappableContent"))
            .addModifiers(KModifier.PUBLIC)
            .addCode(buildBody(grouped, atlas))
            .build()

        val fileBuilder = FileSpec.builder(genPackage, "ContentRegions")
        grouped.keys.forEach { fqn ->
            if (fqn.contains(".")) {
                fileBuilder.addImport(fqn.substringBeforeLast('.'), fqn.substringAfterLast('.'))
            }
        }
        fileBuilder.addImport("arc.graphics.g2d", "TextureRegion")
        fileBuilder.addType(TypeSpec.objectBuilder("ContentRegions").addFunction(fn).build())
        fileBuilder.build().writeTo(outDir)
    }

    private fun buildBody(
        grouped: Map<String, List<Triple<KtClass, KtField, Map<String, String>>>>,
        atlas: String,
    ): CodeBlock {
        val sb = StringBuilder()
        grouped.forEach { (typeName, fields) ->
            val simple = typeName.substringAfterLast('.')
            sb.append("if (content is $simple) {\n")
            fields.sortedBy { it.second.name }.forEach { (_, field, ann) ->
                val dimensions = dimensions(ann)
                val fallback = ann["fallback"]?.takeIf { it != "error" }
                    ?.let { ", ${parse(it, "content")}" } ?: ""
                if (dimensions.isEmpty()) {
                    sb.append("  content.${field.name} = $atlas.find(${parse(ann["value"] ?: "", "content")}$fallback)\n")
                } else {
                    appendLoops(sb, field.name, dimensions, 0, atlas, ann, fallback)
                }
            }
            sb.append("}\n")
        }
        return CodeBlock.of("%L", sb.toString())
    }

    private fun appendLoops(
        sb: StringBuilder,
        fieldName: String,
        dimensions: List<Int>,
        depth: Int,
        atlas: String,
        ann: Map<String, String>,
        fallback: String,
    ) {
        val indent = "  ".repeat(depth + 1)
        val index = "INDEX$depth"
        sb.append("${indent}for ($index in 0 until ${dimensions[depth]}) {\n")
        if (depth + 1 == dimensions.size) {
            sb.append("${"  ".repeat(depth + 2)}content.$fieldName[$index] = $atlas.find(${parse(ann["value"] ?: "", "content")}$fallback)\n")
        } else {
            appendLoops(sb, fieldName + "[$index]", dimensions, depth + 1, atlas, ann, fallback)
        }
        sb.append("$indent}\n")
    }

    private fun dimensions(ann: Map<String, String>): List<Int> {
        val lengths = ann["lengths"]?.let(::parseArray) ?: intArrayOf()
        if (lengths.isNotEmpty()) return lengths.toList()
        val length = ann["length"]?.toIntOrNull() ?: 1
        // length=1 is still an array declaration; infer the first dimension from the field initializer
        // only when the scanner preserved no explicit lengths. Without a known bound, keep scalar behavior.
        return length.takeIf { it > 1 }?.let { listOf(it) }.orEmpty()
    }

    private fun parse(value: String, contentVar: String): String {
        var v = "\"${value.trim('"')}\""
        v = v.replace("@size", "\" + (content as mindustry.world.Block).size + \"")
        v = v.replace("@", "\" + $contentVar.name + \"")
        v = v.replace("#1", "\" + INDEX0 + \"")
        v = v.replace("#2", "\" + INDEX1 + \"")
        v = v.replace("#", "\" + INDEX0 + \"")
        return v
    }

    private fun parseArray(str: String): IntArray {
        val cleaned = str.removePrefix("[").removeSuffix("]").trim()
        return if (cleaned.isEmpty()) intArrayOf()
        else cleaned.split(",").map { it.trim().toInt() }.toIntArray()
    }
}
