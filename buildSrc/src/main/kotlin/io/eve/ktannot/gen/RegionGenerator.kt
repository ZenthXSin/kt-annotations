package io.eve.ktannot.gen

import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import java.io.File

/**
 * 对标 LoadRegionProcessor:生成 ContentRegions.loadRegions(content) 方法,
 * 负责按 @Load 注解把字段赋值为 Core.atlas.find(...),支持 @ / @size / # / #1 等占位符,以及数组。
 */
object RegionGenerator {

    fun generate(classes: List<KtClass>, outDir: File, mindustryMode: Boolean = false) {
        val loadFields = classes.flatMap { cls ->
            cls.fields.filter { it.annotations.containsKey("Load") }.map { Triple(cls, it, it.annotations.getValue("Load")) }
        }
        System.err.println("[kt-annot] RegionGenerator: loadFields=${loadFields.size}, fields=${loadFields.map { t -> "${t.first.name}.${t.second.name}: keys=${t.third.keys}" }}")
        if (loadFields.isEmpty()) return

        val grouped = loadFields.groupBy { it.first.fullName }

        val atlas = if (mindustryMode) "arc.Core.atlas" else "Core.atlas"
        val fn = FunSpec.builder("loadRegions")
            .addParameter("content", com.squareup.kotlinpoet.ClassName("mindustry.ctype", "MappableContent"))
            .addModifiers(KModifier.PUBLIC)
            .addCode(buildBody(grouped, atlas))
            .build()

        // 为 each referenced type 加 import(用简单名生成 if (content is X))
        val fileBuilder = com.squareup.kotlinpoet.FileSpec.builder("io.eve.ktannot.gen", "ContentRegions")
        grouped.keys.forEach { fqn ->
            if (fqn.contains(".")) {
                fileBuilder.addImport(fqn.substringBeforeLast('.'), fqn.substringAfterLast('.'))
        fileBuilder.addImport("arc.graphics.g2d", "TextureRegion")
            }
        }
        fileBuilder.addType(TypeSpec.objectBuilder("ContentRegions").addFunction(fn).build())
        fileBuilder.build().writeTo(outDir)
    }

    private fun buildBody(grouped: Map<String, List<Triple<KtClass, KtField, Map<String, String>>>>, atlas: String): com.squareup.kotlinpoet.CodeBlock {
        val sb = StringBuilder()
        grouped.forEach { (typeName, fields) ->
            val simple = typeName.substringAfterLast('.')
            sb.append("if (content is $simple) {\n")
            fields.sortedBy { it.second.name }.forEach { (_, field, ann) ->
                val doFallback = ann["fallback"]?.let { it != "error" } ?: false
                val fallbackSuffix = if (doFallback) ", ${parse(ann["fallback"]!!, "content")}" else ""
                val arrayLen = ann["length"]?.toIntOrNull()
                val hasLength = arrayLen != null || ann.containsKey("lengths")
                if (hasLength) {
                    val len = arrayLen ?: 1
                    sb.append("  for (INDEX0 in 0 until $len) {\n")
                    sb.append("    content.${field.name}[INDEX0] = $atlas.find(${parse(ann["value"] ?: "", "content")}$fallbackSuffix)\n")
                    sb.append("  }\n")
                } else {
                    sb.append("  content.${field.name} = $atlas.find(${parse(ann["value"] ?: "", "content")}$fallbackSuffix)\n")
                }
            }
            sb.append("}\n")
        }
        return com.squareup.kotlinpoet.CodeBlock.of("%L", sb.toString())
    }

    private fun count(str: String, sub: String): Int {
        var last = 0; var cnt = 0
        while (last != -1) {
            last = str.indexOf(sub, last)
            if (last != -1) { cnt++; last += sub.length }
        }
        return cnt
    }

    private fun parse(value: String, contentVar: String): String {
        var v = "\"${value.trim('"')}\""
        v = v.replace("@size", "\" + (content as mindustry.world.Block).size + \"")
        v = v.replace("@", "\" + content.name + \"")
        v = v.replace("#1", "\" + INDEX0 + \"")
        v = v.replace("#2", "\" + INDEX1 + \"")
        v = v.replace("#", "\" + INDEX0 + \"")
        return v
    }

    private fun parseArray(str: String): IntArray {
        val cleaned = str.replace("[", "").replace("]", "").trim()
        return if (cleaned.isEmpty()) intArrayOf() else cleaned.split(",").map { it.trim().toInt() }.toIntArray()
    }
}