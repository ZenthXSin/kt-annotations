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
                    if (r.contains(".")) r else nameToFqn[r] ?: r
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

    private fun resolveType(type: String, nameToFqn: Map<String, String>): String {
        if (type.contains(".")) return type
        return nameToFqn[type.removeSuffix("?")]?.let { fqn -> if (type.endsWith("?")) "$fqn?" else fqn } ?: type
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

    private fun parseField(prop: KtProperty): KtField? {
        val name = prop.name ?: return null
        val mods = prop.modifierList
        val type = prop.typeReference?.text ?: return null
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
        return KtMethod(
            name = name,
            returnType = returnType,
            parameters = params,
            annotations = parseAnnotations(fn),
            isStatic = false,
            isPublic = mods?.hasModifier(KtTokens.PUBLIC_KEYWORD) == true,
            isPrivate = mods?.hasModifier(KtTokens.PRIVATE_KEYWORD) == true,
            isAbstract = mods?.hasModifier(KtTokens.ABSTRACT_KEYWORD) == true,
        )
    }
}