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
        taskProvider.configure { t ->
            t.group = "kt-annotations"
            t.description = "Generate Mindustry-style annotations code (entities, structs, regions, remote, logic, assets)"
            t.sourceDir.set(File(target.projectDir, "src/main/kotlin"))
            t.outputDir.set(File(target.buildDir, "generated/ktannot/main/kotlin"))
        }

        // 传递 extension 配置到任务属性
        target.afterEvaluate {
            val ext = target.extensions.findByName("ktAnnotations") as? KtAnnotationsExtension
            if (ext != null) {
                val outputPath = target.file(ext.outputDir)
                taskProvider.configure { t2 ->
                    t2.mindustryMode = ext.mindustryMode
                    t2.genPackage = ext.genPackage
                    t2.sourceDir.set(target.file(ext.sourceDir))
                    t2.outputDir.set(outputPath)
                }

                // 将生成目录追加到 main source set,并让 compileKotlin 依赖生成任务
                // Use SourceSetContainer to add generated source dirs.
                // 注: Kotlin 源目录由各消费模块自己的 build.gradle.kts 统一声明
                // (kotlin.srcDir("build/generated/ktannot/main/kotlin")),生成器插件只需
                // 把 Java 源目录加上并让 compileKotlin 依赖生成任务即可,避免在插件内
                // 直接引用 KGP 的 KotlinSourceSet 类型(standalone plugin 编译期不可用)。
                val srcSets = target.extensions.findByType(org.gradle.api.tasks.SourceSetContainer::class.java)
                if (srcSets != null) {
                    val main = srcSets.getByName("main")
                    main.java.srcDir(outputPath)
                }
                val compileTask = target.tasks.findByName("compileKotlin")
                if (compileTask != null) {
                    compileTask.dependsOn(taskProvider.flatMap { it.outputDir })
                }
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