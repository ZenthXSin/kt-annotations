package io.eve.ktannot.gen

import com.squareup.kotlinpoet.*
import java.io.File

/**
 * 对标 AssetsProcess:生成 Tex/Icon/Iconc 资源常量类,以及 Sounds/Musics 存根。
 * 简化版:只生成空壳,运行时由 Mindustry 自己的 asset 加载器填充。
 * 真实 Mindustry 项目使用 AssetsProcess 生成 Tex/Icon/Iconc,本生成器仅作占位。
 */
object AssetsGenerator {

    var GEN_PKG: String = "io.eve.ktannot.gen"

    fun generate(outDir: File, mindustryMode: Boolean = false) {
        generateTex(outDir, mindustryMode)
        generateSounds(outDir, mindustryMode)
    }

    // D2: Tex 类
    private fun generateTex(outDir: File, mindustryMode: Boolean = false) {
        if (!mindustryMode) return

        val tex = TypeSpec.objectBuilder("Tex")
            .addModifiers(KModifier.PUBLIC)
            .addFunction(
                FunSpec.builder("load").addModifiers(KModifier.PUBLIC)
                    .addAnnotation(AnnotationSpec.builder(ClassName("kotlin.jvm", "JvmStatic")).build())
                    .addStatement("// loaded by Mindustry asset system")
                    .build()
            )
            .build()
        FileSpec.builder(GEN_PKG, "Tex").addType(tex).build()
            .writeTo(outDir)
    }

    // D3: Sounds/Musics 存根
    private fun generateSounds(outDir: File, mindustryMode: Boolean = false) {
        if (!mindustryMode) return

        val soundType = ClassName("arc.audio", "Sound")

        val sounds = TypeSpec.objectBuilder("Sounds")
            .addModifiers(KModifier.PUBLIC)
            .addProperty(
                PropertySpec.builder("none", soundType, KModifier.PUBLIC)
                    .mutable(true)
                    .initializer("%T()", soundType).build()
            )
            .build()
        FileSpec.builder(GEN_PKG, "Sounds").addType(sounds).build()
            .writeTo(outDir)

        val musicType = ClassName("arc.audio", "Music")

        val musics = TypeSpec.objectBuilder("Musics")
            .addModifiers(KModifier.PUBLIC)
            .addProperty(
                PropertySpec.builder("none", musicType, KModifier.PUBLIC)
                    .mutable(true)
                    .initializer("%T()", musicType).build()
            )
            .build()
        FileSpec.builder(GEN_PKG, "Musics").addType(musics).build()
            .writeTo(outDir)
    }
}