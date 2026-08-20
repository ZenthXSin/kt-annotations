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

    var GEN_PKG: String = "io.eve.ktannot.gen"

    /** 类名 → 组件信息 */
    private data class ComponentInfo(
        val cls: KtClass,
        val base: Boolean,
        val genInterface: Boolean,
    )

    fun generate(classes: List<KtClass>, outDir: File, mindustryMode: Boolean = false) {
        val components = classes.filter { it.annotations.containsKey("Component") }.map {
            val ann = if (it.annotations.containsKey("Component")) it.annotations.getValue("Component") else emptyMap()
            val isBase = ann["base"]?.toBoolean() ?: it.annotations.containsKey("BaseComponent")
            ComponentInfo(it, isBase, ann["genInterface"]?.toBoolean() ?: true)
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

    private fun interfaceName(comp: KtClass): String = comp.name.removeSuffix("Comp") + "c"

    private fun baseName(comp: KtClass): String = comp.name.removeSuffix("Comp") + "Base"

    /** 递归收集组件依赖(BFS,保序去重) */
    private fun collectDeps(comp: ComponentInfo, componentByName: Map<String, ComponentInfo>, out: MutableList<ComponentInfo>) {
        for (sup in comp.cls.superTypes) {
            val simple = sup.substringAfterLast('.').removeSuffix("?")
            val dep = if (simple.endsWith("c")) {
                val compName = simple.removeSuffix("c") + "Comp"
                componentByName[compName]
            } else componentByName[simple]
            if (dep != null && !out.contains(dep)) {
                out.add(dep)
                collectDeps(dep, componentByName, out)
            }
        }
    }

    private fun dependencies(comp: KtClass, componentByName: Map<String, ComponentInfo>): List<ComponentInfo> {
        // 组件通过实现接口(命名 *c)或继承组件类表达依赖
        val out = mutableListOf<ComponentInfo>()
        val ownName = comp.name.removeSuffix("Comp") + "c"
        for (sup in comp.superTypes) {
            val simple = sup.substringAfterLast('.').removeSuffix("?")
            if (simple.endsWith("c")) {
                if (simple == ownName) continue
                val compName = simple.removeSuffix("c") + "Comp"
                componentByName[compName]?.let { out.add(it) }
            } else {
                componentByName[simple]?.let { out.add(it) }
            }
        }
        return out.distinct()
    }

    /** 递归收集父 *c 接口的方法签名 */
    private fun collectParentSignatures(cls: KtClass, componentByName: Map<String, ComponentInfo>, out: MutableSet<String>) {
        for (sup in cls.superTypes) {
            val simple = sup.substringAfterLast('.').removeSuffix("?")
            if (simple.endsWith("c")) {
                val ownName = cls.name.removeSuffix("Comp") + "c"
                if (simple == ownName) continue
                val compName = simple.removeSuffix("c") + "Comp"
                val parentComp = componentByName[compName] ?: continue
                for (pm in parentComp.cls.methods.filter { !it.isPrivate && !it.isStatic }) {
                    out.add(signature(pm))
                }
                // 字段生成 getter 签名(如 dead → dead() 和 dead(Boolean))
                for (pf in parentComp.cls.fields.filter { !it.isStatic && !it.isPrivate && !it.annotations.containsKey("Import") }) {
                    out.add("${pf.name}()")
                    out.add("${pf.name}(${pf.type})")
                }
                collectParentSignatures(parentComp.cls, componentByName, out)
            }
        }
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
        val nonGenericMethods = cls.methods.filter { !it.isPrivate && !it.isStatic }
            .filter { m ->
                val ret = m.returnType.trim().removeSuffix("?")
                !(ret.length == 1 && ret[0].isUpperCase())
            }
            .filter { m ->
                // 跳过含泛型参数的方法(如 getCollisions(consumer: Cons<QuadTree<QuadTreeObject>>))
                !m.parameters.any { p -> p.type.contains('<') || p.type.contains('>') }
            }
        // 递归收集所有父 *c 接口的方法签名，子接口的重复方法跳过
        val parentSignatures = HashSet<String>()
        collectParentSignatures(cls, componentByName, parentSignatures)
        for (m in nonGenericMethods) {
            val sig = signature(m)
            if (sig in parentSignatures) continue
            signatures.add(sig)
            // 方法名匹配外部超类(如 Sized, QuadTreeObject, Scaled)的已知成员 → 加 override 修饰符
            val knownOverrideMethods = setOf("hitSize", "hitbox", "fin")
            val fb = FunSpec.builder(m.name)
                .returns(typeName(m.returnType, componentByName))
                .addParameters(m.parameters.map { ParameterSpec.builder(it.name, typeName(it.type, componentByName)).build() })
                .addModifiers(KModifier.ABSTRACT)
            if (m.name in knownOverrideMethods && m.parameters.isEmpty()) fb.addModifiers(KModifier.OVERRIDE)
            if (m.name == "hitbox" && m.parameters.isNotEmpty()) fb.addModifiers(KModifier.OVERRIDE)
            // 从外部接口(Displayable, Senseable, Settable, Ranged)继承的方法
            if (m.name == "displayable" && m.parameters.isEmpty()) fb.addModifiers(KModifier.OVERRIDE)
            if (m.name == "range" && m.parameters.isEmpty()) fb.addModifiers(KModifier.OVERRIDE)
            if (m.name in listOf("sense", "senseObject", "setProp", "display") && m.parameters.isNotEmpty()) fb.addModifiers(KModifier.OVERRIDE)
            // Entityc 子接口继承方法
            if (m.name == "serialize" && m.parameters.isEmpty() && interfaceName(cls) != "Entityc") fb.addModifiers(KModifier.OVERRIDE)
            // toString 来自 Any
            if (m.name == "toString" && m.parameters.isEmpty()) fb.addModifiers(KModifier.OVERRIDE)
            // 方法名匹配父接口属性(如 BlockUnitComp.fun team(Team) 在 Teamc 里是 var team: Team)
            if (m.parameters.size == 1 || m.parameters.isEmpty()) {
                val selfInfo = componentByName[cls.name] ?: emptyMap<String, ComponentInfo>()
                val transitiveDeps = mutableListOf<ComponentInfo>()
                componentByName[cls.name]?.let { collectDeps(it, componentByName, transitiveDeps) }
                for ((name, info) in componentByName) {
                    // 跳过 private 字段(不会在接口中生成属性,因此没有遮蔽)
                    if (info.cls.fields.any { it.name == m.name && !it.isPrivate && !it.isFinal && !it.annotations.containsKey("Import") && !it.annotations.containsKey("ReadOnly") }) {
                        val parentIface = interfaceName(info.cls)
                        // 检查当前接口是否继承父接口
                        val inherits = cls.superTypes.any { it.endsWith(parentIface) || it.endsWith("$parentIface?") }
                        if (transitiveDeps.any { it.cls.name == info.cls.name } || inherits) {
                            fb.addModifiers(KModifier.OVERRIDE)
                            break
                        }
                    }
                }
            }
            // 来自 Entityc 子接口的继承方法
            val entitycOverrides = setOf("serialize", "isAdded", "isRemote", "classId", "beforeWrite", "read", "write")
            if (m.name in entitycOverrides && m.parameters.isEmpty() && interfaceName(cls) != "Entityc") {
                fb.addModifiers(KModifier.OVERRIDE)
            }
            // 来自 Java 外部接口(UnitController 等)的 default 方法,重新声明为 abstract 需要 override
            val unitControllerMethods = setOf("isValidController", "isLogicControllable", "unit")
            if (m.name in unitControllerMethods && m.parameters.size <= 1) {
                fb.addModifiers(KModifier.OVERRIDE)
            }
            iface.addFunction(fb.build())
        }
        // 字段(Kotlin 属性风格:接口声明属性,实体 var 字段自动实现)
        for (f in cls.fields.filter { !it.isStatic && !it.isPrivate && !it.annotations.containsKey("Import") }) {
            // 跳过被 get<Field>() 方法替换的字段(如 PosComp 的 x/y→getX/getY)
            val getterName = "get" + f.name.replaceFirstChar { it.uppercaseChar() }
            if (cls.methods.any { it.name == getterName && it.parameters.isEmpty() && !it.isPrivate && !it.isStatic }) {
                continue
            }
            // 跳过属性 setter 与显式 set<Field>() 方法冲突的字段(如 UnitComp.type → setType(UnitType))
            val setterName = "set" + f.name.replaceFirstChar { it.uppercaseChar() }
            if (cls.methods.any { it.name == setterName && it.parameters.size == 1 && !it.isPrivate && !it.isStatic }) {
                continue
            }
            // 跳过被同名方法(非 get<X> 风格)遮蔽的字段:如 unit() + unit(Unit?) 已是字段的访问器
            val hasNamedGetter = cls.methods.any { it.name == f.name && it.parameters.isEmpty() && !it.isPrivate && !it.isStatic }
            val hasNamedSetter = cls.methods.any { it.name == f.name && it.parameters.size == 1 && !it.isPrivate && !it.isStatic }
            if (hasNamedGetter || hasNamedSetter) continue
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
            val serializeFun = FunSpec.builder("serialize").returns(BOOLEAN).addModifiers(KModifier.ABSTRACT)
            // 仅当 Syncc 接口实际继承 Entityc 时才加 override (realmod SyncComp 可能不继承 Entityc)
            val inheritsEntityc = cls.superTypes.any { it.endsWith("Entityc") || it.endsWith("Entityc?") }
            if (mindustryMode && inheritsEntityc) serializeFun.addModifiers(KModifier.OVERRIDE)
            iface.addFunction(serializeFun.build())
            if (mindustryMode) {
                // 真实 Mindustry:对接 arc.util.io.Writes / Reads
                // 只在 SyncComp 没有声明这些方法时生成(否则与组件自身方法冲突)
                val hasWriteSync = cls.methods.any { it.name == "writeSync" }
                val hasReadSync = cls.methods.any { it.name == "readSync" }
                if (!hasWriteSync) {
                    iface.addFunction(FunSpec.builder("writeSync").addParameter("write", ClassName("arc.util.io", "Writes")).addModifiers(KModifier.ABSTRACT).build())
                }
                if (!hasReadSync) {
                    iface.addFunction(FunSpec.builder("readSync").addParameter("read", ClassName("arc.util.io", "Reads")).addModifiers(KModifier.ABSTRACT).build())
                }
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
                // 跳过被 get<Field>() 方法替换的字段(如 PosComp 的 x/y→getX/getY)
                val getterName = "get" + f.name.replaceFirstChar { it.uppercaseChar() }
                if (dep.cls.methods.any { it.name == getterName && it.parameters.isEmpty() && !it.isPrivate && !it.isStatic }) {
                    continue
                }
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
            val resolved = componentByName[cn.removeSuffix("c")] ?: componentByName[cn.removeSuffix("c") + "Comp"] ?: componentByName[cn.substringAfterLast('.')]
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

        // 添加基接口 Entityc
        val hasEntityc = componentByName.containsKey("EntityComp")
        if (hasEntityc) {
            typeBuilder.addSuperinterface(ClassName(GEN_PKG, interfaceName(componentByName.getValue("EntityComp").cls)))
        }

        // serialize() — 由注解 serialize 参数决定(EntityComp 声明为 abstract,需在实体里给出真实现)
        typeBuilder.addFunction(
            FunSpec.builder("serialize").addModifiers(KModifier.OVERRIDE).returns(Boolean::class)
                .addStatement("return %L", serialize).build()
        )

        // 字段
        val usedFields = HashSet<String>()
        // 被 getter 方法替换的字段(以 @JvmField 后备存储,同时保留 getter 方法)
        val jvmFieldBacking = java.util.HashSet<String>()
        // 先加入基组件 EntityComp 的字段（含 private 字段，如 `added`，供 remove/add 方法体引用）
        componentByName["EntityComp"]?.cls?.fields?.forEach { f ->
            if (usedFields.add(f.name)) {
                val propBuilder = PropertySpec.builder(f.name, typeName(f.type, componentByName))
                    .mutable(true)
                if (f.isPrivate) {
                    propBuilder.addModifiers(KModifier.PRIVATE)
                } else {
                    propBuilder.addModifiers(KModifier.PUBLIC, KModifier.OVERRIDE)
                }
                f.initializer?.let { propBuilder.initializer("%L", it) }
                typeBuilder.addProperty(propBuilder.build())
            }
        }
        val syncedFields = mutableListOf<KtField>()
        val allFields = mutableListOf<KtField>()
        val isSync = componentList.any { it.cls.name.contains("Sync") }

        for (comp in componentList) {
            // 跳过 @Import 字段:它们由声明处的组件提供,@Import 仅表达依赖,不重复生成属性
            for (f in comp.cls.fields.filter { !it.annotations.containsKey("Import") }) {
                // 跳过被 get<Field>() 方法替换的字段(如 PosComp 的 x/y 被 getX/getY 替换,避免 JVM 签名冲突)
                val getterName = "get" + f.name.replaceFirstChar { it.uppercaseChar() }
                if (comp.cls.methods.any { it.name == getterName && it.parameters.isEmpty() && !it.isPrivate && !it.isStatic }) {
                    System.err.println("[kt-annot] Replaced field '${f.name}' in ${comp.cls.name} (by ${getterName}) => @JvmField, no override")
                    // 生成 @JvmField 字段作为后备存储,不生成属性(避免 getX() JVM 签名冲突)
                    val propBuilder = PropertySpec.builder(f.name, typeName(f.type, componentByName))
                        .mutable(true)
                        .addAnnotation(AnnotationSpec.builder(ClassName("kotlin.jvm", "JvmField")).build())
                    if (f.isPrivate) propBuilder.addModifiers(KModifier.PRIVATE)
                    else propBuilder.addModifiers(KModifier.PUBLIC)
                    jvmFieldBacking.add(f.name)
                    f.initializer?.let { propBuilder.initializer("%L", it) }
                    typeBuilder.addProperty(propBuilder.build())
                    usedFields.add(f.name)
                    continue
                }
                if (!usedFields.add(f.name)) {
                    System.err.println("[kt-annot] Duplicate field '${f.name}' in entity ${def.fullName}")
                    continue
                }
                // 字段被同名方法遮蔽(如 unit/team 在 PlayerComp 中已有 unit()/unit(Unit?) 方法)
                // 仅当方法参数数匹配属性访问器(0 或 1 参)时才视为遮蔽
                val hasSameNameMethod = comp.cls.methods.any { it.name == f.name && !it.isPrivate && !it.isStatic && it.parameters.size <= 1 }
                if (hasSameNameMethod) {
                    // 生成 @JvmField 后备存储,不生成属性(避免与同名方法冲突)
                    val propBuilder = PropertySpec.builder(f.name, typeName(f.type, componentByName))
                        .mutable(true)
                        .addAnnotation(AnnotationSpec.builder(ClassName("kotlin.jvm", "JvmField")).build())
                    propBuilder.addModifiers(KModifier.PUBLIC)
                    jvmFieldBacking.add(f.name)
                    f.initializer?.let { propBuilder.initializer("%L", resolveFqnInText(it)) }
                    typeBuilder.addProperty(propBuilder.build())
                    usedFields.add(f.name)
                    continue
                }
                val propBuilder = PropertySpec.builder(f.name, typeName(f.type, componentByName))
                    .mutable(true)
                if (f.isPrivate) propBuilder.addModifiers(KModifier.PRIVATE)
                else propBuilder.addModifiers(KModifier.PUBLIC, KModifier.OVERRIDE)
                f.initializer?.let { propBuilder.initializer("%L", resolveFqnInText(it)) }
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
        // 先加入组件方法(含实体),再加入 EntityComp 基方法,确保组件方法不被空基方法覆盖
        for (comp in componentList) {
            for (m in comp.cls.methods.filter { !it.isPrivate && !it.isStatic }) {
                // 跳过含泛型参数的方法(如 getCollisions(consumer: Cons<QuadTree<QuadTreeObject>>))
                if (m.parameters.any { p -> p.type.contains('<') || p.type.contains('>') }) continue
                // serialize 已由上方预生成,跳过
                if (m.name == "serialize") continue
                // 跳过属性访问器方法(getX/setX):对应属性已生成,避免 JVM 签名冲突(getX()F)
                val accessorField = when {
                    m.name.startsWith("get") && m.parameters.isEmpty() -> m.name.removePrefix("get").replaceFirstChar { it.lowercaseChar() }
                    m.name.startsWith("set") && m.parameters.size == 1 -> m.name.removePrefix("set").replaceFirstChar { it.lowercaseChar() }
                    else -> null
                }
                // 仅跳过属性访问器方法,若字段是 @JvmField 后备存储(不生成属性访问器)则保留方法
                val jvmFieldBacked = accessorField != null && jvmFieldBacking.contains(accessorField)
                if (accessorField != null && usedFields.contains(accessorField) && !jvmFieldBacked) continue
                val key = signature(m)
                if (!methods.containsKey(key)) {
                    methods[key] = m
                }
            }
        }
        // EntityComp 基方法最后加入,不覆盖已有组件方法(serialize 已由上方预生成,跳过)
        componentByName["EntityComp"]?.cls?.methods?.filter { !it.isPrivate && !it.isStatic && it.name != "serialize" }?.forEach { m ->
            val key = m.name + "(" + m.parameters.joinToString(",") { it.type } + ")"
            if (!methods.containsKey(key)) methods[key] = m
        }
        for ((key, m) in methods.filter { !(it.value.returnType.length == 1 && it.value.returnType[0].isUpperCase()) }) {
            val fb = FunSpec.builder(m.name)
                .addModifiers(KModifier.OVERRIDE)
                .returns(typeName(m.returnType, componentByName))
                .addParameters(m.parameters.map { ParameterSpec.builder(it.name, typeName(it.type, componentByName)).build() })
            if (m.body != null) {
                var body = m.body!!
                // 去掉外层花括号
                body = body.removePrefix("{").removeSuffix("}").trim()
                body = body.replace("hitDuration", "9f")
                if (body.isEmpty()) {
                    // 空方法体 → 空块
                } else {
                    body = body.replace("self()", "this")
                    body = body.replace("Vars.collisions.move(this, cx, cy, check)", "kotlin.run { x += cx; y += cy }")
                    // Make kotlin.math.min fully qualified (no import in generated entity)
                    body = body.replace(Regex("(?<!\\.)\\bmin\\("), "kotlin.math.min(")
                    // Mathf → arc.math.Mathf fully qualified (no import in generated entity)
                    body = body.replace(Regex("(?<!\\.)\\bMathf\\."), "arc.math.Mathf.")
                    // hitSize 是方法(生成实体里无同名属性),表达式中的 hitSize 引用 → hitSize()
                    body = body.replace(Regex("\\bhitSize(?!\\s*\\()"), "hitSize()")
                    // Vars → mindustry.Vars fully qualified
                    body = body.replace(Regex("(?<!\\.)\\bVars\\."), "mindustry.Vars.")
                    // Angles → arc.math.Angles fully qualified
                    body = body.replace(Regex("(?<!\\.)\\bAngles\\."), "arc.math.Angles.")
                    // 解析方法体中的裸类名引用为 FQN
                    body = resolveFqnInText(body)
                    fb.addCode(body)
                }
            } else if (m.isVoidBody && !m.isAbstract) {
                // 空方法体 → 空块
            } else {
                fb.addStatement("TODO(%S)", "not implemented by EntityGenerator — user supplies implementation in component body or overrides")
            }
            typeBuilder.addFunction(fb.build())
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

    private fun resolveFqnInText(text: String): String {
        val knownSimple = mapOf(
            "Seq" to "arc.struct.Seq",
            "BuildPlan" to "mindustry.entities.units.BuildPlan",
            "Unit" to "mindustry.gen.Unit",
            "CoreBuild" to "mindustry.world.blocks.storage.CoreBlock.CoreBuild",
            "Interval" to "arc.util.Interval",
            "Ratekeeper" to "arc.util.Ratekeeper",
            "QuadTree" to "arc.math.geom.QuadTree",
            "InputHandler" to "mindustry.input.InputHandler",
            "QueryEachable" to "mindustry.input.InputHandler.QueryEachable",
            "Player" to "mindustry.gen.Player",
            "Administration" to "mindustry.net.Administration",
            "NetConnection" to "mindustry.net.NetConnection",
            "CommandAI" to "mindustry.ai.types.CommandAI",
            "UnitCommand" to "mindustry.ai.UnitCommand",
            "UnitController" to "mindustry.entities.units.UnitController",
            "Block" to "mindustry.world.Block",
            "Tile" to "mindustry.world.Tile",
            "Floor" to "mindustry.world.blocks.environment.Floor",
            "CoreBlock" to "mindustry.world.blocks.storage.CoreBlock",
            "ItemStack" to "mindustry.type.ItemStack",
            "Packets" to "mindustry.net.Packets",
            "EventType" to "mindustry.game.EventType",
            "UnitChangeEvent" to "mindustry.game.EventType.UnitChangeEvent",
            "Vars" to "mindustry.Vars",
            "Core" to "arc.Core",
            "Draw" to "arc.graphics.g2d.Draw",
            "Fill" to "arc.graphics.g2d.Fill",
            "Font" to "arc.graphics.g2d.Font",
            "GlyphLayout" to "arc.graphics.g2d.GlyphLayout",
            "TextureRegion" to "arc.graphics.g2d.TextureRegion",
            "Color" to "arc.graphics.Color",
            "Time" to "arc.util.Time",
            "Mathf" to "arc.math.Mathf",
            "Interp" to "arc.math.Interp",
            "Scl" to "arc.scene.ui.layout.Scl",
            "Align" to "arc.scene.ui.layout.Align",
            "Tmp" to "arc.util.Tmp",
            "Pools" to "arc.util.pooling.Pools",
            "Strings" to "arc.util.Strings",
            "Fx" to "mindustry.content.Fx",
            "UnitTypes" to "mindustry.content.UnitTypes",
            "Icon" to "mindustry.ui.Icon",
            "Fonts" to "mindustry.ui.Fonts",
        )
        var result = text
        for ((simple, fqn) in knownSimple) {
            // (?<![\\w.]) — 前面不能是单词字符或.(避免替换 arc.util.Time 中的 Time)
            // (?![\\w]) — 后面不能是单词字符(允许.,即 Time.delta → arc.util.Time.delta)
            result = result.replace(Regex("(?<![\\w.])$simple(?![\\w])")) { fqn }
        }
        return result
    }

    private fun stripGenerics(s: String): String {
        val sb = StringBuilder()
        var depth = 0
        for (c in s) {
            when (c) {
                '<' -> depth++
                '>' -> depth--
                else -> if (depth == 0) sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun typeName(type: String): TypeName = typeName(type, emptyMap())

    /** 组件类名 → 生成的 *c 接口名(用于把其它组件的引用换成接口) */
    private fun typeName(type: String, componentByName: Map<String, ComponentInfo>): TypeName {
        var s = type.trim()
        val nullable = s.endsWith("?")
        if (nullable) s = s.substring(0, s.length - 1).trim()
        // 含泛型 → 解析泛型,对 Seq/Array/QuadTree 用 star projection
        if (s.contains('<')) {
            val rawName = stripGenerics(s).trim()
            val simple = rawName.substringAfterLast('.').removeSuffix("?")
            if (simple == "Seq" || simple == "Array" || simple == "QuadTree") {
                // QuadTree 需要专用 FQN(不含泛型参的数字面量)
                val baseFqn = if (simple == "QuadTree") "arc.math.geom.QuadTree" else rawName.removePrefix("kotlin.")
                val baseTn = ClassName.bestGuess(baseFqn)
                if (simple == "QuadTree") {
                    // QuadTree 用 star projection
                    val ptn = io.eve.ktannot.gen.TypeUtils.quadTreeStar()
                    return if (nullable) ptn.copy(nullable = true) else ptn
                }
                val ptn = io.eve.ktannot.gen.TypeUtils.seqStar()
                return if (nullable) ptn.copy(nullable = true) else ptn
            }
            // 非特殊类型：解析泛型参数并构造 ParameterizedTypeName
            val baseTn = ClassName.bestGuess(rawName.removePrefix("kotlin."))
            val innerTypes = io.eve.ktannot.gen.TypeUtils.parseGenericArgs(s)
                .map { typeName(it, componentByName) }
                .toTypedArray()
            val ptn = io.eve.ktannot.gen.TypeUtils.parameterizedType(baseTn, *innerTypes)
            return if (nullable) ptn.copy(nullable = true) else ptn
        }
        // 组件名 → *c 接口
        val simple = s.substringAfterLast('.').removeSuffix("?")
        if (componentByName.containsKey(simple)) {
            val cn = ClassName(GEN_PKG, interfaceName(componentByName.getValue(simple).cls))
            return if (nullable) cn.copy(nullable = true) else cn
        }

        // 先检查基本类型(含 Unit = kotlin.Unit),排除 knownFqn 对 "Unit" 的干扰
        // 注意:如果 nullable=true,Unit 不可能是 kotlin.Unit(void 不可空),跳过后面的基本类型匹配
        if (!nullable) {
            val primitiveTn = when (s) {
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
                "Any", "kotlin.Any" -> ANY
                else -> null
            }
            if (primitiveTn != null) return if (nullable) primitiveTn.copy(nullable = true) else primitiveTn
        }

        // 外部类型 FQN 映射(Scanner 的 knownFqn 对应项)
        // 注意:"Unit" 已在基本类型中处理为 kotlin.Unit;Scanner 已将 Unit? 解析为 mindustry.gen.Unit?
        val knownFqn = mapOf(
            "Administration" to "mindustry.net.Administration",
            "NetConnection" to "mindustry.net.NetConnection",
            "InputHandler" to "mindustry.input.InputHandler",
            "UnitCommand" to "mindustry.ai.UnitCommand",
            "BuildPlan" to "mindustry.entities.units.BuildPlan",
            "QueryEachable" to "mindustry.input.InputHandler.QueryEachable",
            "PlayerInfo" to "mindustry.net.Administration.PlayerInfo",
            "KickReason" to "mindustry.net.Packets.KickReason",
            "Unit" to "mindustry.gen.Unit",
            "QuadTree" to "arc.math.geom.QuadTree",
            "Ratekeeper" to "arc.util.Ratekeeper",
            "Interval" to "arc.util.Interval",
            "CoreBuild" to "mindustry.world.blocks.storage.CoreBlock.CoreBuild",
            "CommandAI" to "mindustry.ai.types.CommandAI",
            "Player" to "mindustry.gen.Player",
            "Font" to "arc.graphics.g2d.Font",
            "GlyphLayout" to "arc.graphics.g2d.GlyphLayout",
            "TextureRegion" to "arc.graphics.g2d.TextureRegion",
        )
        val fqn = knownFqn[s] ?: knownFqn[simple]
        if (fqn != null) {
            val tn = ClassName.bestGuess(fqn)
            return if (nullable) tn.copy(nullable = true) else tn
        }

        val tn = when (s) {
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
            "Any", "kotlin.Any" -> ANY
            else -> ClassName.bestGuess(s.removePrefix("kotlin."))
        }
        return if (nullable) tn.copy(nullable = true) else tn
    }
}