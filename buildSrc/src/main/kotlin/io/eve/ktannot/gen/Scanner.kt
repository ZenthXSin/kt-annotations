package io.eve.ktannot.gen

import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiFileFactory
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass as PsiKtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtModifierListOwner
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty
import java.io.File

/**
 * 用 Kotlin PSI 扫描源码目录,提取类/接口/对象 + 字段/方法 + 注解(参数以字符串文本保留)。
 * 对标 javax APT 的 elements/types 查询,但跑在 Gradle plugin 侧,不依赖 javac。
 *
 * 类型名解析:PSI 的 typeReference.text 可能是简单名(如 "PosComp"),第二遍用类名→FQN
 * 索引把 superTypes / 字段类型 / 方法类型解析成完整限定名。
 */
class ContentScanner {

    /** 扫描 dir 下所有 .kt 文件,返回所有顶层+嵌套类声明(含注解元数据)。 */
    fun scan(dir: File): List<KtClass> {
        val files = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        if (files.isEmpty()) return emptyList()

        val configuration = CompilerConfiguration()
        val disposable = Disposer.newDisposable()
        val result = mutableListOf<KtClass>()
        try {
            val environment = KotlinCoreEnvironment.createForProduction(
                disposable, configuration, EnvironmentConfigFiles.JVM_CONFIG_FILES
            )
            val psiFactory = PsiFileFactory.getInstance(environment.project)
            for (file in files) {
                try {
                    val text = file.readText()
                    val ktFile = psiFactory.createFileFromText(file.name, org.jetbrains.kotlin.idea.KotlinFileType.INSTANCE, text) as KtFile
                    val pkg = ktFile.packageDirective?.qualifiedName ?: ""
                    ktFile.declarations.forEach { decl ->
                        when (decl) {
                            is PsiKtClass -> result.addAll(scanClass(decl, pkg))
                            is KtObjectDeclaration -> result.addAll(scanClass(decl, pkg))
                        }
                    }
                } catch (e: Exception) {
                    System.err.println("[kt-annot] PSI parse failed for ${file.path}: ${e.message}")
                }
            }

            // 类名 -> FQN 索引(用于把 superTypes / 字段类型 / 方法类型的简单名解析成全限定名)
            val nameToFqn = mutableMapOf<String, String>()
            for (c in result) nameToFqn[c.name] = c.fullName

            // 第二遍:解析所有类型引用
            result.forEach { c ->
                c.rawSuperTypes = c.rawSuperTypes.map { r ->
                    if (r.contains(".")) r else nameToFqn[r] ?: knownFqn[r] ?: r
                }
                c.superTypes = c.rawSuperTypes
                c.fields = c.fields.map { f ->
                    f.copy(type = resolveType(f.type, nameToFqn))
                }
                c.methods = c.methods.map { m ->
                    m.copy(
                        returnType = resolveType(m.returnType, nameToFqn),
                        parameters = m.parameters.map { p -> p.copy(type = resolveType(p.type, nameToFqn)) }
                    )
                }
            }
            Disposer.dispose(disposable)
        } catch (e: Exception) {
            System.err.println("[kt-annot] PSI environment init failed: ${e.message}")
        }
        return result
    }

    private val knownFqn = mapOf(
        "Reads" to "arc.util.io.Reads", "Writes" to "arc.util.io.Writes",
        "Position" to "arc.math.geom.Position", "Vec2" to "arc.math.geom.Vec2",
        "Rect" to "arc.math.geom.Rect", "Cons" to "arc.func.Cons",
        "Team" to "mindustry.game.Team", "Building" to "mindustry.gen.Building",
        "Block" to "mindustry.world.Block", "Tile" to "mindustry.world.Tile",
        "Floor" to "mindustry.world.blocks.environment.Floor",
        "CoreBlock" to "mindustry.world.blocks.storage.CoreBlock",
        "Entityc" to "io.eve.vanilla.gen.Entityc", "Posc" to "io.eve.vanilla.gen.Posc",
        "Hitboxc" to "io.eve.vanilla.gen.Hitboxc",
        "EntityCollisions" to "mindustry.entities.EntityCollisions",
        "SolidPred" to "mindustry.entities.EntityCollisions.SolidPred",
        "Sized" to "mindustry.entities.Sized",
        "CoreBuild" to "mindustry.gen.CoreBuild",
        "Scaled" to "arc.math.Scaled",
        "FloatBuffer" to "java.nio.FloatBuffer",
        "QuadTreeObject" to "arc.math.geom.QuadTree.QuadTreeObject",
        "QuadTree" to "arc.math.geom.QuadTree",
        "Item" to "mindustry.type.Item",
        "ItemStack" to "mindustry.type.ItemStack",
        "Time" to "arc.util.Time", "Mathf" to "arc.math.Mathf",
        "CoreBuild" to "mindustry.world.blocks.storage.CoreBlock.CoreBuild",
        "Vec2" to "arc.math.geom.Vec2",
        "T" to "kotlin.Any",
        "Color" to "arc.graphics.Color",
        "TextureRegion" to "arc.graphics.g2d.TextureRegion",
        "Interval" to "arc.util.Interval",
        "Effect" to "mindustry.entities.Effect",
        "Any" to "kotlin.Any",
        "Bits" to "arc.struct.Bits",
        "StatusEntry" to "mindustry.entities.units.StatusEntry",
        "StatusEffect" to "mindustry.type.StatusEffect",
        "BuildPlan" to "mindustry.entities.units.BuildPlan",
        "Seq" to "arc.struct.Seq",
        "Queue" to "arc.struct.Queue",
        "WeaponMount" to "mindustry.entities.units.WeaponMount",
        "Array" to "kotlin.Array",
        "UnitType" to "mindustry.type.UnitType",
    )

    private fun splitGenericArgs(s: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        val buf = StringBuilder()
        for (c in s) {
            when (c) {
                '<' -> { depth++; buf.append(c) }
                '>' -> { depth--; buf.append(c) }
                ',' -> { if (depth == 0) { parts.add(buf.toString().trim()); buf.clear() } else buf.append(c) }
                else -> buf.append(c)
            }
        }
        if (buf.isNotEmpty()) parts.add(buf.toString().trim())
        return parts
    }

    private fun resolveType(type: String, nameToFqn: Map<String, String>): String {
        if (type.contains(".") && !type.contains("<")) return type

        // 单字母大写 = 泛型参数名,不解析
        if (type.length == 1 && type[0].isUpperCase()) return type

        val clean = type.removeSuffix("?")
        val resolved = nameToFqn[clean] ?: knownFqn[clean]
        if (resolved != null) return if (type.endsWith("?")) "$resolved?" else resolved
        // 泛型:递归解析每个类型参数(支持嵌套)
        if (type.contains("<")) {
            val gi = type.indexOf('<')
            val base = type.substring(0, gi).trim()
            // 找到匹配的 > 结尾
            var depth = 0
            var ge = -1
            for (i in type.indices) {
                when (type[i]) {
                    '<' -> depth++
                    '>' -> { depth--; if (depth == 0) { ge = i; break } }
                }
            }
            if (ge >= 0) {
                val inner = type.substring(gi + 1, ge).trim()
                val innerResolved = splitGenericArgs(inner).map { resolveType(it.trim(), nameToFqn) }.joinToString(", ")
                val baseResolved = resolveType(base, nameToFqn)
                return "$baseResolved<$innerResolved>"
            }
        }
        return type
    }

    /** 递归扫描类(含嵌套类)。 */
    private fun scanClass(klass: KtClassOrObject, pkg: String, outer: String? = null): List<KtClass> {
        val name = klass.name ?: return emptyList()
        val fqn = outer?.let { "$it.$name" } ?: name
        val kind = when {
            klass is KtObjectDeclaration -> Kind.OBJECT
            !(klass is PsiKtClass) -> Kind.CLASS
            (klass as PsiKtClass).isInterface() -> Kind.INTERFACE
            else -> Kind.CLASS
        }

        val annotations = parseAnnotations(klass)
        val rawSupers = klass.superTypeListEntries.mapNotNull { it.typeReference?.text }

        val fields = klass.declarations.filterIsInstance<KtProperty>().mapNotNull { parseField(it) }
        val methods = klass.declarations.filterIsInstance<KtNamedFunction>().mapNotNull { parseMethod(it) }

        val result = mutableListOf(
            KtClass(pkg, fqn, kind, annotations, rawSupers, fields, methods).also { it.rawSuperTypes = rawSupers }
        )

        // nested classes
        klass.declarations.filterIsInstance<KtClassOrObject>().forEach { nested ->
            if (nested !== klass) {
                result.addAll(scanClass(nested, pkg, fqn))
            }
        }
        return result
    }

    private fun parseAnnotations(owner: KtModifierListOwner): Map<String, Map<String, String>> {
        val out = LinkedHashMap<String, Map<String, String>>()
        for (entry in owner.annotationEntries) {
            val name = entry.shortName?.asString() ?: continue
            val args = LinkedHashMap<String, String>()
            for (arg in entry.valueArguments) {
                val argName = arg.getArgumentName()?.asName?.asString() ?: "value"
                args[argName] = arg.getArgumentExpression()?.text ?: ""
            }
            out[name] = args
        }
        return out
    }

    private fun inferTypeFromInitializer(init: Any?): String? {
        // 用 PSI 文本推断类型,只处理常见字面量
        val text = (init as? org.jetbrains.kotlin.psi.KtExpression)?.text ?: return null
        if (text.endsWith("f") || text.endsWith("F")) return "Float"
        if (text.endsWith("L")) return "Long"
        if (text == "true" || text == "false") return "Boolean"
        if (text.startsWith("\"")) return "String"
        // 构造器调用: arc.util.Interval(6) -> arc.util.Interval
        val parenIdx = text.indexOf('(')
        if (parenIdx >= 0) {
            val ctor = text.substring(0, parenIdx).trim()
            // 只取类型部分(不含括号)
            return ctor
        }
        var isNumeric = text.length > 0
        for (c in text) {
            if (!(c.isDigit() || c == '-' || c == '.')) { isNumeric = false; break }
        }
        if (isNumeric && text.contains(".")) return "Double"
        if (isNumeric) return "Int"
        if (text.contains(".")) return text.substringBeforeLast(".")
        if (text[0].isUpperCase()) return text
        return null
    }

    private fun parseField(prop: KtProperty): KtField? {
        val name = prop.name ?: return null
        val mods = prop.modifierList
        val type = prop.typeReference?.text ?: inferTypeFromInitializer(prop.initializer) ?: "Any"
        return KtField(
            name = name,
            type = type,
            annotations = parseAnnotations(prop),
            isStatic = false,
            isFinal = !prop.isVar(),
            isTransient = prop.annotationEntries.any { it.shortName?.asString() == "Transient" },
            isPrivate = mods?.hasModifier(KtTokens.PRIVATE_KEYWORD) == true,
            initializer = prop.initializer?.text,
        )
    }

    private fun parseMethod(fn: KtNamedFunction): KtMethod? {
        val name = fn.name ?: return null
        val mods = fn.modifierList
        val returnType = fn.typeReference?.text ?: "Unit"
        val params = fn.valueParameters.map { p ->
            KtParameter(p.name ?: "arg", p.typeReference?.text ?: "Unit")
        }
        val bodyText = fn.bodyBlockExpression?.text?.trim()?.let { t -> if (t.isNotEmpty()) t else null }
            ?: fn.bodyExpression?.text?.trim()?.let { t -> if (t.isNotEmpty()) "{\nreturn $t\n}" else null }
        val isVoidBody = bodyText == null
        val isOverride = mods?.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.OVERRIDE_KEYWORD) == true
        return KtMethod(
            name = name,
            body = bodyText,
            returnType = returnType,
            parameters = params,
            annotations = parseAnnotations(fn),
            isStatic = false,
            isOverride = isOverride,
            isVoidBody = isVoidBody,
            isPublic = mods?.hasModifier(KtTokens.PUBLIC_KEYWORD) == true,
            isPrivate = mods?.hasModifier(KtTokens.PRIVATE_KEYWORD) == true,
            isAbstract = mods?.hasModifier(KtTokens.ABSTRACT_KEYWORD) == true,
        )
    }
}