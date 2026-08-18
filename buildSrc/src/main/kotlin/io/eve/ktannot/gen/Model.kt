package io.eve.ktannot.gen

/** 解析一个 Kotlin 源文件得到的类声明信息 */
data class KtClass(
    val packageName: String,
    val name: String,
    val kind: Kind,
    val annotations: Map<String, Map<String, String>>, // annotation simpleName -> (argName -> stringValue)
    var superTypes: List<String>, // 完整名称(含包名)
    var fields: List<KtField>,
    var methods: List<KtMethod>,
) {
    val fullName: String get() = if (packageName.isEmpty()) name else "$packageName.$name"
    val simpleName: String get() = name.substringAfterLast('.')

    // 非数据字段:存原始 superTypes 文本,扫描第二阶段用类名索引把简单名解析成 FQN
    var rawSuperTypes: List<String> = superTypes
}

enum class Kind { CLASS, INTERFACE, OBJECT }

/** 字段(属性) */
data class KtField(
    val name: String,
    val type: String, // 源码类型字符串,如 Int / Float / String
    val annotations: Map<String, Map<String, String>>,
    val isStatic: Boolean = false,
    val isFinal: Boolean = false,
    val isTransient: Boolean = false,
    val isPrivate: Boolean = false,
    val initializer: String? = null,
)

/** 成员函数 */
data class KtMethod(
    val body: String? = null,
    val name: String,
    val returnType: String, // 完整字符串,如 "kotlin.Int" / "kotlin.Unit"
    val parameters: List<KtParameter>,
    val annotations: Map<String, Map<String, String>>,
    val isStatic: Boolean = false,
    val isPublic: Boolean = false,
    val isPrivate: Boolean = false,
    val isAbstract: Boolean = false,
    val isOverride: Boolean = false,
    val isVoidBody: Boolean = false,
)

/** 函数参数 */
data class KtParameter(
    val name: String,
    val type: String, // 完整类型字符串
)