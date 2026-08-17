package io.eve.ktannot.gen

import com.squareup.kotlinpoet.*
import java.io.File

/**
 * 对标 EntityProcess:
 *  - 组件接口生成(Component → Xxxc 接口)
 *  - 基类生成(Component base=true → XxxBase 抽象类)
 *  - 实体类生成(EntityDef/Component → Xxx 实体类,含 serialization/sync/toString)
 *  - 组生成(GroupDef → IndexableEntity__xxx + 组访问器索引)
 *
 * 简化:不生成完整 EntityIO revision 兼容层(class 定义里带 int version),序列化用最简单字段顺序,
 * 不对接 arc 的 Writes/Reads,而是生成 Kotlin 版 write/read 到自定义 ByteBuf。这保证生成代码可独立编译运行。
 */
object EntityGenerator {

    const val GEN_PKG = "io.eve.ktannot.gen"

    /** 类名 → 组件信息 */
    private data class ComponentInfo(
        val cls: KtClass,
        val base: Boolean,
        val genInterface: Boolean,
    )

    fun generate(classes: List<KtClass>, outDir: File, mindustryMode: Boolean = false) {
        val components = classes.filter { it.annotations.containsKey("Component") }.map {
            val ann = it.annotations.getValue("Component")
            ComponentInfo(it, ann["base"]?.toBoolean() ?: false, ann["genInterface"]?.toBoolean() ?: true)
        }
        val componentByName = components.associateBy { it.cls.name.substringAfterLast('.') }
        val defs = classes.filter { it.annotations.containsKey("EntityDef") }
        val groups = classes.filter { it.annotations.containsKey("GroupDef") }

        // 1) 组件接口 + 基类
        for (comp in components) {
            generateInterface(comp, componentByName, outDir, mindustryMode)
            if (comp.base && !comp.cls.annotations.containsKey("EntityDef")) {
                generateBaseClass(comp, componentByName, outDir)
            }
        }

        // 2) 组索引接口
        for (g in groups) {
            val ann = g.annotations.getValue("GroupDef")
            val name = g.name.removePrefix("g")
            val idx = TypeSpec.interfaceBuilder("IndexableEntity__$name")
                .addFunction(FunSpec.builder("setIndex__$name").addModifiers(KModifier.ABSTRACT).addParameter("index", Int::class).build())
                .build()
            FileSpec.builder(GEN_PKG, "IndexableEntity__$name").addType(idx).build().writeTo(outDir)
        }

        // 3) 实体类
        for (def in defs) {
            generateEntity(def, componentByName, groups, outDir, mindustryMode)
        }
    }

    private fun interfaceName(comp: KtClass): String = comp.name + "c"

    private fun baseName(comp: KtClass): String = comp.name + "Base"

    /** 递归收集组件依赖(BFS,保序去重) */
    private fun collectDeps(comp: ComponentInfo, componentByName: Map<String, ComponentInfo>, out: MutableList<ComponentInfo>) {
        for (sup in comp.cls.superTypes) {
            val simple = sup.substringAfterLast('.').removeSuffix("?")
            val dep = if (simple.endsWith("c")) componentByName[simple.removeSuffix("c")]
            else componentByName[simple]
            if (dep != null && !out.contains(dep)) {
                out.add(dep)
                collectDeps(dep, componentByName, out)
            }
        }
    }

    private fun dependencies(comp: KtClass, componentByName: Map<String, ComponentInfo>): List<ComponentInfo> {
        // 组件通过实现接口(命名 *c)或继承组件类表达依赖
        val out = mutableListOf<ComponentInfo>()
        for (sup in comp.superTypes) {
            val simple = sup.substringAfterLast('.').removeSuffix("?")
            if (simple.endsWith("c")) {
                componentByName[simple.removeSuffix("c")]?.let { out.add(it) }
            } else {
                componentByName[simple]?.let { out.add(it) }
            }
        }
        return out.distinct()
    }

    private fun generateInterface(comp: ComponentInfo, componentByName: Map<String, ComponentInfo>, outDir: File, mindustryMode: Boolean = false) {
        val cls = comp.cls
        val iface = TypeSpec.interfaceBuilder(interfaceName(cls))
            .addAnnotation(AnnotationSpec.builder(ClassName(GEN_PKG, "EntityInterface")).build())

        // 非组件接口的父接口(如普通 interface);组件类本身跳过(依赖通过 *c 接口表达)
        cls.superTypes.filter { !it.substringAfterLast('.').endsWith("c") }.forEach { sup ->
            val supSimple = sup.substringAfterLast('.').removeSuffix("?")
            val isComponent = componentByName.containsKey(supSimple) || componentByName.containsKey(sup.substringAfterLast('.').removeSuffix("Comp"))
            if (!isComponent) {
                iface.addSuperinterface(ClassName.bestGuess(sup))
            }
        }
        // 组件依赖
        dependencies(cls, componentByName).forEach { dep ->
            iface.addSuperinterface(ClassName(GEN_PKG, interfaceName(dep.cls)))
        }

        // 方法
        val signatures = HashSet<String>()
        for (m in cls.methods.filter { !it.isPrivate && !it.isStatic }) {
            signatures.add(signature(m))
            iface.addFunction(
                FunSpec.builder(m.name)
                    .returns(typeName(m.returnType, componentByName))
                    .addParameters(m.parameters.map { ParameterSpec.builder(it.name, typeName(it.type, componentByName)).build() })
                    .addModifiers(KModifier.ABSTRACT)
                    .build()
            )
        }
        // 字段(Kotlin 属性风格:接口声明属性,实体 var 字段自动实现)
        for (f in cls.fields.filter { !it.isStatic && !it.isPrivate && !it.annotations.containsKey("Import") }) {
            if (!signatures.contains("${f.name}()") && !signatures.contains("${f.name}(${f.type})")) {
                iface.addProperty(PropertySpec.builder(f.name, typeName(f.type, componentByName)).mutable(!f.isFinal && !f.annotations.containsKey("ReadOnly")).build())
            } else {
                // 方法已声明同名 getter/setter,接口属性会被遮蔽,跳过
                if (!signatures.contains("${f.name}()")) {
                    iface.addFunction(FunSpec.builder(f.name).returns(typeName(f.type, componentByName)).addModifiers(KModifier.ABSTRACT).build())
                }
                if (!f.isFinal && !f.annotations.containsKey("ReadOnly") && !signatures.contains("${f.name}(${f.type})")) {
                    iface.addFunction(FunSpec.builder(f.name).addParameter(f.name, typeName(f.type, componentByName)).addModifiers(KModifier.ABSTRACT).build())
                }
            }
        }
        // Sync 组件接口追加同步生命周期方法(实体 override serialize/writeSync/readSync 需要声明)
        if (cls.name.contains("Sync")) {
            iface.addFunction(FunSpec.builder("serialize").returns(BOOLEAN).addModifiers(KModifier.ABSTRACT).build())
            if (mindustryMode) {
                // 真实 Mindustry:对接 arc.util.io.Writes / Reads
                iface.addFunction(FunSpec.builder("writeSync").addParameter("write", ClassName("arc.util.io", "Writes")).addModifiers(KModifier.ABSTRACT).build())
                iface.addFunction(FunSpec.builder("readSync").addParameter("read", ClassName("arc.util.io", "Reads")).addModifiers(KModifier.ABSTRACT).build())
            } else {
                iface.addFunction(FunSpec.builder("writeSync").addParameter("buffer", ClassName(GEN_PKG, "ByteBuf")).addModifiers(KModifier.ABSTRACT).build())
                iface.addFunction(FunSpec.builder("readSync").addParameter("buffer", ClassName(GEN_PKG, "ByteBuf")).addModifiers(KModifier.ABSTRACT).build())
            }
        }

        FileSpec.builder(GEN_PKG, interfaceName(cls)).addType(iface.build()).build().writeTo(outDir)
    }

    private fun generateBaseClass(comp: ComponentInfo, componentByName: Map<String, ComponentInfo>, outDir: File) {
        val cls = comp.cls
        val deps = dependencies(cls, componentByName) + comp
        val type = TypeSpec.classBuilder(baseName(cls))
            .addModifiers(KModifier.ABSTRACT, KModifier.PUBLIC)

        for (dep in deps) {
            for (f in dep.cls.fields.filter { !it.isStatic && !it.isPrivate && !it.annotations.containsKey("Import") && !it.annotations.containsKey("ReadOnly") }) {
                val prop = PropertySpec.builder(f.name, typeName(f.type), KModifier.PUBLIC, KModifier.OVERRIDE)
                    .mutable(true)
                f.initializer?.let { prop.initializer("%L", it) }
                type.addProperty(prop.build())
            }
            type.addSuperinterface(ClassName(GEN_PKG, interfaceName(dep.cls)))
        }
        FileSpec.builder(GEN_PKG, baseName(cls)).addType(type.build()).build().writeTo(outDir)
    }

    private fun generateEntity(
        def: KtClass,
        componentByName: Map<String, ComponentInfo>,
        groups: List<KtClass>,
        outDir: File,
        mindustryMode: Boolean = false,
    ) {
        val ann = def.annotations.getValue("EntityDef")
        val isFinal = ann["isFinal"]?.toBoolean() ?: true
        val pooled = ann["pooled"]?.toBoolean() ?: false
        val serialize = ann["serialize"]?.toBoolean() ?: true
        val legacy = ann["legacy"]?.toBoolean() ?: false

        // 组件解析:EntityDef(value=[...]) 指向组件(名字去掉 c),递归收集依赖组件
        val componentList = mutableListOf<ComponentInfo>()
        val valueSpec = ann["value"] ?: ""
        val compNames = parseClassArray(valueSpec)
        for (cn in compNames) {
            val resolved = componentByName[cn.removeSuffix("c")] ?: componentByName[cn.substringAfterLast('.')]
            if (resolved != null && !componentList.contains(resolved)) {
                componentList.add(resolved)
                collectDeps(resolved, componentByName, componentList)
            }
        }
        if (componentList.isEmpty()) {
            System.err.println("[kt-annot] EntityDef ${def.fullName} has no resolvable components, skipping")
            return
        }

        val name = def.name.removeSuffix("Def").removeSuffix("Comp")
        val finalName = if (name == baseName(componentList.first().cls)) name + "Entity" else name

        val typeBuilder = TypeSpec.classBuilder(finalName).addModifiers(if (isFinal) KModifier.FINAL else KModifier.OPEN)

        // serialize()
        typeBuilder.addFunction(
            FunSpec.builder("serialize").addModifiers(KModifier.OVERRIDE).returns(Boolean::class)
                .addStatement("return %L", serialize).build()
        )

        // 字段
        val usedFields = HashSet<String>()
        val syncedFields = mutableListOf<KtField>()
        val allFields = mutableListOf<KtField>()
        val isSync = componentList.any { it.cls.name.contains("Sync") }

        for (comp in componentList) {
            for (f in comp.cls.fields.filter { !it.annotations.containsKey("Import") }) {
                if (!usedFields.add(f.name)) {
                    System.err.println("[kt-annot] Duplicate field '${f.name}' in entity ${def.fullName}")
                    continue
                }
                val propBuilder = PropertySpec.builder(f.name, typeName(f.type, componentByName))
                    .mutable(true)
                if (f.isPrivate || f.annotations.containsKey("ReadOnly")) propBuilder.addModifiers(KModifier.PROTECTED, KModifier.OVERRIDE)
                else propBuilder.addModifiers(KModifier.PUBLIC, KModifier.OVERRIDE)
                f.initializer?.let { propBuilder.initializer("%L", it) }
                typeBuilder.addProperty(propBuilder.build())
                allFields.add(f)

                if (f.annotations.containsKey("SyncField") && isSync && !legacy) {
                    if (f.type != "Float" && f.type != "float" && f.type != "kotlin.Float") {
                        System.err.println("[kt-annot] SyncField must be Float, got ${f.type} in ${def.fullName}")
                    } else {
                        syncedFields.add(f)
                        typeBuilder.addProperty(PropertySpec.builder("${f.name}_TARGET_", Float::class, KModifier.PRIVATE).mutable(true).initializer("0f").build())
                        typeBuilder.addProperty(PropertySpec.builder("${f.name}_LAST_", Float::class, KModifier.PRIVATE).mutable(true).initializer("0f").build())
                    }
                }
            }
        }
        syncedFields.sortBy { it.name }

        // 方法
        val methods = LinkedHashMap<String, KtMethod>()
        for (comp in componentList) {
            for (m in comp.cls.methods.filter { !it.isPrivate && !it.isStatic }) {
                methods[m.name] = m
            }
        }
        for (m in methods.values) {
            typeBuilder.addFunction(
                FunSpec.builder(m.name)
                    .addModifiers(KModifier.OVERRIDE)
                    .returns(typeName(m.returnType, componentByName))
                    .addParameters(m.parameters.map { ParameterSpec.builder(it.name, typeName(it.type, componentByName)).build() })
                    .addStatement("TODO(%S)", "not implemented by EntityGenerator — user supplies implementation in component body or overrides")
                    .build()
            )
        }

        // sync methods
        if (syncedFields.isNotEmpty()) {
            if (mindustryMode) {
                typeBuilder.addFunction(
                    FunSpec.builder("writeSync").addModifiers(KModifier.OVERRIDE).addParameter("write", ClassName("arc.util.io", "Writes"))
                        .apply {
                            syncedFields.forEach { addStatement("write.f(this.%L)", it.name) }
                        }.build()
                )
                typeBuilder.addFunction(
                    FunSpec.builder("readSync").addModifiers(KModifier.OVERRIDE).addParameter("read", ClassName("arc.util.io", "Reads"))
                        .apply {
                            syncedFields.forEach { addStatement("this.%L = read.f()", it.name) }
                        }.build()
                )
            } else {
                typeBuilder.addFunction(
                    FunSpec.builder("writeSync").addModifiers(KModifier.OVERRIDE).addParameter("buffer", ClassName(GEN_PKG, "ByteBuf"))
                        .apply {
                            syncedFields.forEach { addStatement("buffer.putFloat(this.%L)", it.name) }
                        }.build()
                )
                typeBuilder.addFunction(
                    FunSpec.builder("readSync").addModifiers(KModifier.OVERRIDE).addParameter("buffer", ClassName(GEN_PKG, "ByteBuf"))
                        .apply {
                            syncedFields.forEach { addStatement("this.%L = buffer.getFloat()", it.name) }
                        }.build()
                )
            }
        }

        // toString
        if (!methods.containsKey("toString")) {
            typeBuilder.addFunction(
                FunSpec.builder("toString").addModifiers(KModifier.OVERRIDE).returns(String::class)
                    .addStatement("return %S", finalName).build()
            )
        }

        // 组件接口:实体实现每个组件的 *c 接口(方法 override 才能成立)
        typeBuilder.addAnnotation(AnnotationSpec.builder(ClassName(GEN_PKG, "EntityInterface")).build())
        for (comp in componentList) {
            if (comp.genInterface) {
                typeBuilder.addSuperinterface(ClassName(GEN_PKG, interfaceName(comp.cls)))
            }
        }

        // groups
        for (g in groups) {
            val gann = g.annotations.getValue("GroupDef")
            val gname = g.name.removePrefix("g")
            val groupComps = parseClassArray(gann["value"] ?: "")
            // 实体包含组所有组件 → 实现索引接口
            val exclude = parseClassArray(gann["exclude"] ?: "")
            val hasAll = groupComps.all { gc ->
                val cn = gc.removeSuffix("c").substringAfterLast('.')
                componentList.any { it.cls.name.substringAfterLast('.') == cn }
            } && !exclude.any { ec -> componentList.any { it.cls.name.substringAfterLast('.') == ec.removeSuffix("c").substringAfterLast('.') } }
            if (hasAll) {
                typeBuilder.addSuperinterface(ClassName(GEN_PKG, "IndexableEntity__$gname"))
                // 组索引字段 + setIndex 实现(IndexableEntity 抽象成员)
                val indexField = "index_$gname"
                if (!usedFields.contains(indexField)) {
                    typeBuilder.addProperty(PropertySpec.builder(indexField, INT, KModifier.PROTECTED).mutable(true).initializer("0").build())
                    usedFields.add(indexField)
                }
                typeBuilder.addFunction(
                    FunSpec.builder("setIndex__$gname").addModifiers(KModifier.OVERRIDE).addParameter("index", INT)
                        .addStatement("this.$indexField = index").build()
                )
            }
        }

        FileSpec.builder(GEN_PKG, finalName).addType(typeBuilder.build()).build().writeTo(outDir)
    }

    /** 解析 "[A::class, B::class]" 形式的 Class[] 参数 */
    private fun parseClassArray(str: String): List<String> {
        val cleaned = str.removePrefix("[").removeSuffix("]").trim()
        if (cleaned.isEmpty()) return emptyList()
        return cleaned.split(",").map { it.trim().removeSuffix("::class").trim() }
    }

    private fun signature(m: KtMethod): String =
        "${m.name}(${m.parameters.joinToString(",") { it.type }})"

    private fun typeName(type: String): TypeName = typeName(type, emptyMap())

    /** 组件类名 → 生成的 *c 接口名(用于把其它组件的引用换成接口) */
    private fun typeName(type: String, componentByName: Map<String, ComponentInfo>): TypeName {
        val simple = type.substringAfterLast('.').removeSuffix("?")
        if (componentByName.containsKey(simple)) {
            return ClassName(GEN_PKG, interfaceName(componentByName.getValue(simple).cls))
        }
        // 基本类型映射
        return when (type) {
            "Int", "int", "kotlin.Int" -> INT
            "Float", "float", "kotlin.Float" -> FLOAT
            "Boolean", "boolean", "kotlin.Boolean" -> BOOLEAN
            "Long", "long", "kotlin.Long" -> LONG
            "Double", "double", "kotlin.Double" -> DOUBLE
            "Short", "short", "kotlin.Short" -> SHORT
            "Byte", "byte", "kotlin.Byte" -> BYTE
            "Char", "char", "kotlin.Char" -> CHAR
            "String", "kotlin.String" -> STRING
            "Unit", "void", "kotlin.Unit" -> UNIT
            else -> ClassName.bestGuess(type.removePrefix("kotlin."))
        }
    }
}