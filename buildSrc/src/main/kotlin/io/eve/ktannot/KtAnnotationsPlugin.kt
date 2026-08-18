package io.eve.ktannot

import io.eve.ktannot.gen.ContentScanner
import io.eve.ktannot.gen.EntityGenerator
import io.eve.ktannot.gen.LogicGenerator
import io.eve.ktannot.gen.RegionGenerator
import io.eve.ktannot.gen.RemoteGenerator
import io.eve.ktannot.gen.StructGenerator
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File

/** 生成任务:扫描 src 目录的 Kotlin 源码,运行全部注解处理器,输出生成代码。 */
open class GenerateTask : DefaultTask() {

    @get:InputFiles
    val sourceDir: DirectoryProperty = project.objects.directoryProperty()

    @get:OutputDirectory
    val outputDir: DirectoryProperty = project.objects.directoryProperty()

    // 真实 Mindustry 模式:生成对接 mindustry.net.Packet / arc.util.io.Writes / mindustry.world.Block / arc.Core 的代码
    @get:org.gradle.api.tasks.Internal
    var mindustryMode: Boolean = false
    @get:org.gradle.api.tasks.Internal
    var genPackage: String = "io.eve.ktannot.gen"

    @TaskAction
    fun run() {
        val src = sourceDir.get().asFile
        val out = outputDir.get().asFile
        out.mkdirs()

        logger.lifecycle("[kt-annot] scanning ${src.path} (mindustryMode=$mindustryMode)")
        val classes = ContentScanner().scan(src)
        logger.lifecycle("[kt-annot] found ${classes.size} classes")

        // 运行全部注解处理器(对标 Mindustry 6 大处理器)
        EntityGenerator.GEN_PKG = genPackage; EntityGenerator.generate(classes, out, mindustryMode)   // @EntityDef / @Component / @GroupDef
        StructGenerator.generate(classes, out)                  // @Struct
        RegionGenerator.generate(classes, out, mindustryMode)          // @Load
        RemoteGenerator.generate(classes, out, mindustryMode)   // @Remote
        LogicGenerator.generate(classes, out)                   // @RegisterStatement

        logger.lifecycle("[kt-annot] generation done -> ${out.path}")
    }
}

class KtAnnotationsPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.extensions.create("ktAnnotations", KtAnnotationsExtension::class.java)

        val taskProvider = target.tasks.register("generateKtAnnotations", GenerateTask::class.java)
        val task = taskProvider
        taskProvider.configure {
            group = "kt-annotations"
            description = "Generate Mindustry-style annotations code (entities, structs, regions, remote, logic, assets)"
            sourceDir.set(File(target.projectDir, "src/main/kotlin"))
            outputDir.set(File(target.buildDir, "generated/ktannot/main/kotlin"))
        }

        // 传递 mindustryMode:extensions.ktAnnotations.mindustryMode → 任务属性
        target.afterEvaluate {
            val ext = target.extensions.findByName("ktAnnotations") as? KtAnnotationsExtension
            if (ext != null) {
                taskProvider.configure { mindustryMode = ext.mindustryMode; genPackage = ext.genPackage }
            }
        }

        target.afterEvaluate {
            // 将生成目录追加到 main source set,并让 compileKotlin 依赖生成任务
            // buildSrc 无法直接引用 SourceSetContainer/KotlinSourceSet(位于 gradle 插件类别 jar),
            // 因此用无类型扩展 + 反射访问,保持可编译。
            val generated = File(target.buildDir, "generated/ktannot/main/kotlin")
            runCatching {
                val srcSets = target.extensions.findByName("sourceSets") ?: return@runCatching
                val main: Any = srcSets.javaClass.getMethod("getByName", String::class.java).invoke(srcSets, "main")
                val java: Any = main.javaClass.getMethod("getJava").invoke(main)
                java.javaClass.getMethod("srcDir", Any::class.java).invoke(java, generated)
                val ext = main.javaClass.getMethod("getExtensions").invoke(main)
                val kts = ext.javaClass.getMethod("findByName", String::class.java).invoke(ext, "kotlin")
                if (kts != null) {
                    val kk = kts.javaClass.getMethod("getKotlin").invoke(kts)
                    kk.javaClass.getMethod("srcDir", Any::class.java).invoke(kk, generated)
                }
            }.onFailure { e ->
                target.logger.warn("[kt-annot] failed to wire generated source dir: ${e.message}")
            }
            val compileTask = target.tasks.findByName("compileKotlin")
            if (compileTask != null) {
                compileTask.dependsOn(task)
            }
        }
    }
}

open class KtAnnotationsExtension {
    var sourceDir: String = "src/main/kotlin"
    var outputDir: String = "build/generated/ktannot/main/kotlin"
    var mindustryMode: Boolean = false
    var genPackage: String = "io.eve.ktannot.gen"
}