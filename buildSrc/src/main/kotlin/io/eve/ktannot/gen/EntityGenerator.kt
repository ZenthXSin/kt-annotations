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
        // 组件识别:支持 @Component 与别名 @EntityComponent(常用作 ent.anno 迁移)
        val components = classes.filter { it.annotations.containsKey("Component") || it.annotations.containsKey("EntityComponent") }.map {
            val ann = it.annotations["Component"] ?: it.annotations.getValue("EntityComponent")
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
        val generated = mutableListOf<GeneratedEntityInfo>()
        for (def in defs) {
            generated.add(generateEntity(def, componentByName, groups, outDir, mindustryMode))
        }
        // 3b) 字段级 @EntityDef(UnitType 字段等):生成实体类(对标 EntityAnno 字段级 EntityDef)
        for (fieldDef in collectFieldEntityDefs(classes, componentByName)) {
            generated.add(generateFieldEntity(fieldDef, componentByName, groups, outDir, mindustryMode))
        }
        // 4) EntityRegistry(注册所有生成实体 + 提供 content/register/get 方法)
        generateEntityRegistry(generated, outDir, mindustryMode)
    }

    /** 字段级 EntityDef:从类的字段/属性注解里收集。返回 (宿主类, 字段, 注解参数)。 */
    private fun collectFieldEntityDefs(classes: List<KtClass>, componentByName: Map<String, ComponentInfo>): List<FieldEntityDef> {
        val out = mutableListOf<FieldEntityDef>()
        for (c in classes) {
            for (f in c.fields) {
                val ann = f.annotations["EntityDef"] ?: continue
                out.add(FieldEntityDef(c, f, ann))
            }
        }
        return out
    }

    /** 字段级 EntityDef 信息 */
    private data class FieldEntityDef(val host: KtClass, val field: KtField, val ann: Map<String, String>)

    /** 生成的实体信息(供 EntityRegistry 生成) */
    private data class GeneratedEntityInfo(
        val name: String,
        val className: ClassName,
        val fqName: String,
        val pooled: Boolean = false,
    )

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
            if (!isComponent && supSimple != "UnitController") {
                iface.addSuperinterface(ClassName.bestGuess(sup))
            }
        }
        // 组件依赖(本地组件)
        dependencies(cls, componentByName).forEach { dep ->
            iface.addSuperinterface(ClassName(GEN_PKG, interfaceName(dep.cls)))
        }
        // 外部 *c 接口(不在 componentByName 中):保留为超类型
        cls.superTypes.filter { it.substringAfterLast('.').endsWith("c") }.forEach { sup ->
            val simpleName = sup.substringAfterLast('.').removeSuffix("?")
            val compName = simpleName.removeSuffix("c") + "Comp"
            if (!componentByName.containsKey(compName)) {
                // 外部 *c 接口(如 mindustry.gen.Drawc),直接加入；本地 PosComp 等已由本地接口表达。
                iface.addSuperinterface(ClassName.bestGuess(sup))
            }
        }
        // 组件自身携带 @EntityDef 时,其 value 列出的外部 *c 接口(如 Teamc/Drawc)也作为接口父类型
        cls.annotations["EntityDef"]?.get("value")?.let { value ->
            parseClassArray(value).forEach { cn ->
                val simple = cn.removeSuffix("c").substringAfterLast('.')
                val compName = simple.removeSuffix("c") + "Comp"
                val isLocal = componentByName.containsKey(simple) || componentByName.containsKey(compName)
                val isSelf = simple == cls.name.removeSuffix("Comp").removeSuffix("Def")
                if (!isLocal && !isSelf && cn.endsWith("c")) {
                    // 用文件 import 映射把简单名解析为 FQN(否则 KotlinPoet 生成无 import 的裸名)
                    val fqn = cls.imports[cn] ?: cn
                    iface.addSuperinterface(ClassName.bestGuess(fqn))
                }
            }
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
            // 组件实现了外部 vanilla *c 接口(Teamc/Drawc/Entityc 等)时,与这些接口同名的成员需要 override
            val vanillaMemberNames = vanillaMembersInSuperTypes(cls, m.name, componentByName)
            if (vanillaMemberNames) fb.addModifiers(KModifier.OVERRIDE)
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
                        // 跳过自引用:当前组件生成的接口不能以自己为父接口
                        if (parentIface == interfaceName(cls)) continue
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
            val unitControllerMethods = emptySet<String>()
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
    ): GeneratedEntityInfo {
        val ann = def.annotations.getValue("EntityDef")
        val isFinal = ann["isFinal"]?.toBoolean() ?: true
        val pooled = ann["pooled"]?.toBoolean() ?: false
        val serialize = ann["serialize"]?.toBoolean() ?: true
        val legacy = ann["legacy"]?.toBoolean() ?: false
        val extendsBase = ann["extends"]?.let { cleanStr(it) } ?: ""

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
            // 类级 EntityDef 可能只引用 vanilla 接口(Unitc/Payloadc 等,无本地组件)。
            // 若指定了 extends 基类(如 mindustry.gen.UnitEntity / BuildingTetherPayloadUnit),
            // 仍应生成一个继承该基类的实体类,供 EntityRegistry.content(...) 绑定。
            val extendsBase2 = ann["extends"]?.let { cleanStr(it) } ?: ""
            if (extendsBase2.isNotEmpty()) {
                val nm = def.name.removeSuffix("Def").removeSuffix("Comp")
                val tb2 = TypeSpec.classBuilder(nm).addModifiers(if (isFinal) KModifier.FINAL else KModifier.OPEN)
                tb2.superclass(ClassName.bestGuess(extendsBase2))
                tb2.addFunction(
                    FunSpec.builder("serialize").addModifiers(KModifier.OVERRIDE).returns(Boolean::class)
                        .addStatement("return %L", serialize).build()
                )
                tb2.addAnnotation(AnnotationSpec.builder(ClassName(GEN_PKG, "EntityInterface")).build())
                tb2.addType(
                    TypeSpec.companionObjectBuilder().addFunction(
                        FunSpec.builder("create")
                            .addAnnotation(AnnotationSpec.builder(ClassName("kotlin.jvm", "JvmStatic")).build())
                            .returns(ClassName(GEN_PKG, nm))
                            .addStatement("return %L()", nm).build()
                    ).build()
                )
                FileSpec.builder(GEN_PKG, nm).addType(tb2.build()).build().writeTo(outDir)
                System.err.println("[kt-annot] EntityDef ${def.fullName} has no local components; emitted extends-based entity $nm")
                return GeneratedEntityInfo(name = nm, className = ClassName(GEN_PKG, nm), fqName = "$GEN_PKG.$nm", pooled = pooled)
            }
            // 字段级/空组件时让调用方继续;类级 EntityDef 无组件时跳过
            System.err.println("[kt-annot] EntityDef ${def.fullName} has no resolvable components, skipping")
            return GeneratedEntityInfo(name = def.name, className = ClassName(GEN_PKG, def.name), fqName = "$GEN_PKG.${def.name}")
        }

        val name = def.name.removeSuffix("Def").removeSuffix("Comp")
        val finalName = if (name == baseName(componentList.first().cls)) name + "Entity" else name

        val typeBuilder = TypeSpec.classBuilder(finalName).addModifiers(if (isFinal) KModifier.FINAL else KModifier.OPEN)

        // extends 基类:实体继承 vanilla 实体(如 mindustry.gen.UnitEntity),不重复实现 Entityc
        if (extendsBase.isNotEmpty()) {
            typeBuilder.superclass(ClassName.bestGuess(extendsBase))
        }

        // 添加基接口 Entityc
        val hasEntityc = componentByName.containsKey("EntityComp") && extendsBase.isEmpty()
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

        // 实现实体依赖的外部 vanilla *c 接口(如 mindustry.gen.Teamc / Drawc / Posc / Entityc)的抽象成员:
        // 这些接口由引擎提供,生成实体若没有 extends 基类则必须自己实现全部成员。
        // 仅对 mindustryMode 且未 extends 基类的实体生效。
        if (mindustryMode && extendsBase.isEmpty()) {
            val externalInterfaces = extractExternalInterfaces(componentList, componentByName)
            emitVanillaInterfaceSurface(typeBuilder, externalInterfaces, componentList, componentByName)
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

        // create() companion：class 级 EntityDef 也生成静态工厂（对标字段级），
        // 供 mod 代码如 SpaceLaunchPayload.create() 使用。
        typeBuilder.addType(
            TypeSpec.companionObjectBuilder().addFunction(
                FunSpec.builder("create")
                    .addAnnotation(AnnotationSpec.builder(ClassName("kotlin.jvm", "JvmStatic")).build())
                    .returns(ClassName(GEN_PKG, finalName))
                    .addStatement("return %L()", finalName).build()
            ).build()
        )

        FileSpec.builder(GEN_PKG, finalName).addType(typeBuilder.build()).build().writeTo(outDir)
        return GeneratedEntityInfo(
            name = finalName,
            className = ClassName(GEN_PKG, finalName),
            fqName = "$GEN_PKG.$finalName",
            pooled = pooled,
        )
    }

    /** 生成字段级 @EntityDef 对应的实体类(对标 EntityAnno 字段级 EntityDef,如 UnitType 字段)。 */
    private fun generateFieldEntity(
        fieldDef: FieldEntityDef,
        componentByName: Map<String, ComponentInfo>,
        groups: List<KtClass>,
        outDir: File,
        mindustryMode: Boolean = false,
    ): GeneratedEntityInfo {
        val ann = fieldDef.ann
        val compNames = parseClassArray(ann["value"] ?: "")
        // 解析组件(BFS 收集依赖);本地组件优先(去掉 c 后缀 / Comp 后缀)
        val componentList = mutableListOf<ComponentInfo>()
        for (cn in compNames) {
            val key = cn.removeSuffix("c").substringAfterLast('.')
            val resolved = componentByName[key] ?: componentByName[key + "Comp"]
            if (resolved != null && !componentList.contains(resolved)) {
                componentList.add(resolved)
                collectDeps(resolved, componentByName, componentList)
            }
        }
        if (componentList.isEmpty()) {
            // 字段级 EntityDef 可能只引用 vanilla 接口(Unitc/Payloadc 等,无本地组件)。
            // 此时仍应生成实体类(默认 extends mindustry.gen.UnitEntity 或用户指定 extends),
            // 供 EntityRegistry.content(...) 绑定为 UnitType 的实体类。
            val name = fieldEntityName(compNames)
            val isFinal = ann["isFinal"]?.toBoolean() ?: true
            val serialize = ann["serialize"]?.toBoolean() ?: true
            val extendsBase = cleanStr(ann["extends"] ?: "mindustry.gen.UnitEntity")
            val tb = TypeSpec.classBuilder(name).addModifiers(if (isFinal) KModifier.FINAL else KModifier.OPEN)
            tb.superclass(ClassName.bestGuess(extendsBase))
            tb.addFunction(
                FunSpec.builder("serialize").addModifiers(KModifier.OVERRIDE).returns(Boolean::class)
                    .addStatement("return %L", serialize).build()
            )
            tb.addAnnotation(AnnotationSpec.builder(ClassName(GEN_PKG, "EntityInterface")).build())
            tb.addType(
                TypeSpec.companionObjectBuilder().addFunction(
                    FunSpec.builder("create")
                        .addAnnotation(AnnotationSpec.builder(ClassName("kotlin.jvm", "JvmStatic")).build())
                        .returns(ClassName(GEN_PKG, name))
                        .addStatement("return %L()", name).build()
                ).build()
            )
            FileSpec.builder(GEN_PKG, name).addType(tb.build()).build().writeTo(outDir)
            System.err.println("[kt-annot] field EntityDef ${fieldDef.host.name}.${fieldDef.field.name} has no local components; emitted vanilla-extending entity $name")
            return GeneratedEntityInfo(name = name, className = ClassName(GEN_PKG, name), fqName = "$GEN_PKG.$name", pooled = false)
        }

        val name = fieldEntityName(compNames)
        val isFinal = ann["isFinal"]?.toBoolean() ?: true
        val serialize = ann["serialize"]?.toBoolean() ?: true
        val pooled = ann["pooled"]?.toBoolean() ?: false
        val extendsBase = cleanStr(ann["extends"] ?: "mindustry.gen.UnitEntity")

        val typeBuilder = TypeSpec.classBuilder(name).addModifiers(if (isFinal) KModifier.FINAL else KModifier.OPEN)
        typeBuilder.superclass(ClassName.bestGuess(extendsBase))

        // serialize()
        typeBuilder.addFunction(
            FunSpec.builder("serialize").addModifiers(KModifier.OVERRIDE).returns(Boolean::class)
                .addStatement("return %L", serialize).build()
        )

        val usedFields = HashSet<String>()
        // 组件字段合并(跳过 @Import)
        for (comp in componentList) {
            for (f in comp.cls.fields.filter { !it.annotations.containsKey("Import") && !it.isStatic && !it.isPrivate }) {
                if (!usedFields.add(f.name)) continue
                val prop = PropertySpec.builder(f.name, typeName(f.type, componentByName), KModifier.PUBLIC, KModifier.OVERRIDE).mutable(true)
                f.initializer?.let { prop.initializer("%L", resolveFqnInText(it)) }
                typeBuilder.addProperty(prop.build())
            }
        }

        // 组件方法(文本合并,简化实现)
        val methods = LinkedHashMap<String, KtMethod>()
        for (comp in componentList) {
            for (m in comp.cls.methods.filter { !it.isPrivate && !it.isStatic }) {
                if (m.parameters.any { p -> p.type.contains('<') || p.type.contains('>') }) continue
                if (m.name == "serialize") continue
                val key = signature(m)
                if (!methods.containsKey(key)) methods[key] = m
            }
        }
        for ((key, m) in methods) {
            val fb = FunSpec.builder(m.name).addModifiers(KModifier.OVERRIDE)
                .returns(typeName(m.returnType, componentByName))
                .addParameters(m.parameters.map { ParameterSpec.builder(it.name, typeName(it.type, componentByName)).build() })
            if (m.body != null) {
                var body = m.body!!.removePrefix("{").removeSuffix("}").trim()
                body = body.replace("self()", "this")
                body = body.replace(Regex("(?<!\\.)\\bMathf\\."), "arc.math.Mathf.")
                body = body.replace(Regex("(?<!\\.)\\bVars\\."), "mindustry.Vars.")
                body = body.replace(Regex("(?<!\\.)\\bAngles\\."), "arc.math.Angles.")
                body = body.replace(Regex("\\bhitSize(?!\\s*\\()"), "hitSize()")
                body = resolveFqnInText(body)
                fb.addCode(body)
            } else if (m.isVoidBody && !m.isAbstract) {
                // 空块
            } else {
                fb.addStatement("TODO(%S)", "not implemented by EntityGenerator — user supplies implementation in component body or overrides")
            }
            typeBuilder.addFunction(fb.build())
        }

        typeBuilder.addAnnotation(AnnotationSpec.builder(ClassName(GEN_PKG, "EntityInterface")).build())
        for (comp in componentList) {
            if (comp.genInterface) typeBuilder.addSuperinterface(ClassName(GEN_PKG, interfaceName(comp.cls)))
        }

        // create() companion
        typeBuilder.addType(
            TypeSpec.companionObjectBuilder().addFunction(
                FunSpec.builder("create")
                    .addAnnotation(AnnotationSpec.builder(ClassName("kotlin.jvm", "JvmStatic")).build())
                    .returns(ClassName(GEN_PKG, name))
                    .addStatement("return %L()", name).build()
            ).build()
        )

        FileSpec.builder(GEN_PKG, name).addType(typeBuilder.build()).build().writeTo(outDir)
        return GeneratedEntityInfo(name = name, className = ClassName(GEN_PKG, name), fqName = "$GEN_PKG.$name", pooled = pooled)
    }

    /** 字段级 EntityDef 实体命名:组件名(去 c 后缀)去重保序拼接,以 Unit 结尾(对标 Disintegration 引用名)。 */
    private fun fieldEntityName(compNames: List<String>): String {
        val extras = compNames.map { it.removeSuffix("c").substringAfterLast('.') }
            .filter { it.isNotEmpty() && it != "Unit" }
        if (extras.isEmpty()) return "UnitEntity"
        return extras.joinToString("") + "Unit"
    }

    /** 生成 EntityRegistry(注册所有生成实体 + content/register/get)。仅 mindustryMode 下有真实意义。 */
    private fun generateEntityRegistry(generated: List<GeneratedEntityInfo>, outDir: File, mindustryMode: Boolean = false) {
        if (generated.isEmpty() || !mindustryMode) return
        val obj = TypeSpec.objectBuilder("EntityRegistry").addModifiers(KModifier.PUBLIC)

        // content(name, entityClass, creator):创建 UnitType 并绑定实体类
        obj.addFunction(
            FunSpec.builder("content").addAnnotation(AnnotationSpec.builder(ClassName("kotlin.jvm", "JvmStatic")).build())
                .addParameter("name", STRING)
                .addParameter("entityClass", TypeUtils.classExtends(ClassName("mindustry.gen", "Unit")))
                .addParameter("creator", TypeUtils.parameterizedType(ClassName("arc.func", "Func"), STRING, ClassName("mindustry.type", "UnitType")))
                .returns(ClassName("mindustry.type", "UnitType"))
                .addStatement("val type = creator.get(name)")
                .addStatement("type.constructor = arc.func.Prov { entityClass.newInstance() as mindustry.gen.Unit }")
                .addStatement("mindustry.gen.EntityMapping.nameMap.put(name, type.constructor)")
                .addStatement("return type")
                .build()
        )

        // register():把生成的实体类映射进 EntityMapping(nameMap)
        val registerFun = FunSpec.builder("register")
            .addAnnotation(AnnotationSpec.builder(ClassName("kotlin.jvm", "JvmStatic")).build())
        generated.forEach { info ->
            registerFun.addStatement("mindustry.gen.EntityMapping.nameMap.put(%S, arc.func.Prov { %L() })", info.name, info.fqName)
        }
        obj.addFunction(registerFun.build())

        FileSpec.builder(GEN_PKG, "EntityRegistry").addType(obj.build()).build().writeTo(outDir)
    }

    /** 收集实体组件实现的外部 vanilla *c 接口(FQN 简单名集合,如 Teamc/Drawc/Posc/Entityc/Healthc)。 */
    private fun extractExternalInterfaces(componentList: List<ComponentInfo>, componentByName: Map<String, ComponentInfo>): Set<String> {
        val out = LinkedHashSet<String>()
        val seen = HashSet<String>()

        // 收集某 *c 接口自身的 vanilla 父链(如 Teamc → Posc → Entityc)
        fun collectParents(iface: String) {
            if (iface in seen) return
            seen.add(iface)
            if (KNOWN_VANILLA_C.containsKey(iface)) out.add(iface)
            for (parent in VANILLA_C_PARENTS[iface].orEmpty()) {
                collectParents(parent)
            }
        }

        fun walk(comp: ComponentInfo) {
            for (sup in comp.cls.superTypes) {
                val simple = sup.substringAfterLast('.').removeSuffix("?")
                if (simple.endsWith("c")) {
                    // 本地组件实现的 *c 接口(vanilla 模块的 Posc/Teamc 等)不算外部 vanilla 接口
                    val compName = simple.removeSuffix("c") + "Comp"
                    val isLocalComp = componentByName.containsKey(compName) || componentByName.containsKey(simple.removeSuffix("c"))
                    if (isLocalComp) continue
                    if (KNOWN_VANILLA_C.containsKey(simple)) {
                        collectParents(simple)
                    }
                }
            }
            // 递归本地组件依赖
            for (sup in comp.cls.superTypes) {
                val simple = sup.substringAfterLast('.').removeSuffix("?")
                if (simple.endsWith("c")) {
                    val compName = simple.removeSuffix("c") + "Comp"
                    val dep = componentByName[simple] ?: componentByName[simple.removeSuffix("c")]
                    if (dep != null && dep !== comp) walk(dep)
                }
            }
        }
        componentList.forEach(::walk)
        return out
    }

    /** 已知 vanilla *c 接口的抽象成员(engine 提供,生成实体须实现)。key = 接口简单名。 */
    private val KNOWN_VANILLA_C: Map<String, List<String>> = mapOf(
        // Entityc
        "Entityc" to listOf(
            "self", "as", "isAdded", "isLocal", "isRemote", "serialize", "classId", "id", "add",
            "afterRead", "afterReadAll", "beforeWrite", "id(int)", "read", "remove", "update", "write"
        ),
        // Posc(继承 Entityc)
        "Posc" to listOf(
            "floorOn", "buildOn", "onSolid", "getX", "getY", "x", "y", "tileX", "tileY",
            "blockOn", "tileOn", "set(Position)", "set(float,float)", "trns(Position)", "trns(float,float)", "x(float)", "y(float)"
        ),
        // Teamc(继承 Posc)
        "Teamc" to listOf(
            "inFogTo", "cheating", "team", "closestCore", "closestEnemyCore", "core", "team(Team)"
        ),
        // Drawc(继承 Posc)
        "Drawc" to listOf("clipSize", "draw"),
        // Healthc(继承 Posc)
        "Healthc" to listOf(
            "health", "maxHealth", "dead", "health(float)", "maxHealth(float)", "dead(boolean)",
            "hitTime", "hitTime(float)", "damage", "damage(float)", "damagePierce", "damagePierce(float)",
            "damageArmorMult", "damageContinuous", "damageContinuousPierce", "heal", "heal(float)", "healFract",
            "clampHealth", "kill", "killed", "isValid", "healthf"
        ),
        // Hitboxc(继承 Posc)
        "Hitboxc" to listOf("hitSize", "hitSize(float)", "hitbox", "hitbox(Rect)", "hitboxTile", "hitboxTile(Rect)"),
        // Timedc(继承 Scaled + Entityc; 只列抽象成员, fout/fslope 等为 Scaled default)
        "Timedc" to listOf(
            "fin", "lifetime", "time", "lifetime(float)", "time(float)"
        )
    )

    /** 已知 vanilla *c 接口的父接口(用于传递收集成员)。key = 接口简单名。 */
    private val VANILLA_C_PARENTS: Map<String, List<String>> = mapOf(
        "Posc" to listOf("Entityc"),
        "Teamc" to listOf("Posc", "Entityc"),
        "Drawc" to listOf("Posc", "Entityc"),
        "Healthc" to listOf("Posc", "Entityc"),
        "Hitboxc" to listOf("Posc", "Entityc"),
        "Timedc" to listOf("Entityc"),
    )

    /** 生成外部 vanilla *c 接口的默认实现(字段后备 + 简单默认逻辑)。 */
    private fun emitVanillaInterfaceSurface(
        typeBuilder: TypeSpec.Builder,
        externalInterfaces: Set<String>,
        componentList: List<ComponentInfo>,
        componentByName: Map<String, ComponentInfo>,
    ) {
        val memberNames = LinkedHashSet<String>()
        for (iface in externalInterfaces) {
            KNOWN_VANILLA_C[iface]?.let { memberNames.addAll(it) }
        }
        if (memberNames.isEmpty()) return

        // 已由组件方法覆盖的成员名(避免重复生成)
        val present = HashSet<String>()
        for (comp in componentList) {
            for (m in comp.cls.methods) present.add(m.name)
        }
        // 实体已有字段
        // (生成器外部无法直接读取 typeBuilder 已加字段,这里通过组件字段名 + 常用字段判断)

        // 字段后备:若组件通过 @Import 引用了这些字段,且实体内没有其他组件提供,按需生成
        val fields = LinkedHashMap<String, String>() // name -> type(FQN)
        for (comp in componentList) {
            for (f in comp.cls.fields.filter { it.annotations.containsKey("Import") }) {
                if (f.name in fields || f.name in present) continue
                fields[f.name] = f.type
            }
            // 也把非 Import 但被 getter 替换的字段一并纳入(避免重复)
        }
        val addedProps = HashSet<String>()
        fun addField(name: String, type: String) {
            if (name in addedProps || name in fields) return
            fields[name] = type
        }
        if ("x" in fields || "x" in memberNames) addField("x", "kotlin.Float")
        if ("y" in fields || "y" in memberNames) addField("y", "kotlin.Float")
        if ("team" in fields || "team" in memberNames) addField("team", "mindustry.game.Team")
        if ("id" in memberNames) addField("id", "kotlin.Int")
        if ("health" in fields || "health" in memberNames) addField("health", "kotlin.Float")
        if ("maxHealth" in fields || "maxHealth" in memberNames) addField("maxHealth", "kotlin.Float")
        // Timedc 需要 time/lifetime 后备字段
        if ("time" in memberNames || "fin" in memberNames) addField("time", "kotlin.Float")
        if ("lifetime" in memberNames) addField("lifetime", "kotlin.Float")
        // isAdded/isLocal 需要的后备字段
        if ("isAdded" in memberNames || "add" in memberNames || "remove" in memberNames) {
            if (!fields.containsKey("added")) fields["added"] = "kotlin.Boolean"
        }

        // 只有尚未被组件字段提供的才生成(Kotlin 不允许重复属性)
        // 注意:@Import 字段在实体生成中被跳过,因此不算已提供;非 @Import 字段才算。
        val compFieldNames = HashSet<String>()
        for (comp in componentList) for (f in comp.cls.fields) if (!f.annotations.containsKey("Import")) compFieldNames.add(f.name)

        for ((name, type) in fields) {
            if (name in compFieldNames || name in addedProps) continue
            val defaultValue = when (type.substringAfterLast('.')) {
                "Int", "int", "Long", "long" -> "0"
                "Byte", "byte", "Short", "short" -> "0"
                "Boolean", "boolean" -> "false"
                "Float", "float" -> "0f"
                "Double", "double" -> "0.0"
                "Team" -> "mindustry.game.Team.derelict"
                else -> "0f"
            }
            // vanilla 接口的 bean 访问器(getX()/x()/x(Float))与 Kotlin 属性访问器 JVM 签名冲突,
            // 且 EntityAnno 生成的是 public Java 字段(Java 端直接 entity.field 访问)。
            // 统一用 @JvmField 后备字段 + 显式 override 方法(见下方成员循环)。
            val pb = PropertySpec.builder(name, typeName(type, componentByName), KModifier.PUBLIC).mutable(true).initializer(defaultValue)
            pb.addAnnotation(AnnotationSpec.builder(ClassName("kotlin.jvm", "JvmField")).build())
            typeBuilder.addProperty(pb.build())
            addedProps.add(name)
        }

        // 成员实现:逐条生成(仅当实体没有同名方法/字段时)
        for (member in memberNames) {
            val name = member.substringBefore('(')
            // 组件已声明同名方法则不重复生成(组件方法优先)
            if (name in present) continue
            // serialize 已由实体生成逻辑预生成(serialize() override),跳过
            if (name == "serialize") continue
            // 实体已生成同名属性(Kotlin 属性生成 getX/setX,与 x() 方法不冲突,仍需生成接口访问器)
            val args = if (member.contains('(')) member.substringAfter('(').substringBefore(')') else ""
            when {
                // ---- Entityc ----
                member == "self" -> typeBuilder.addFunction(FunSpec.builder("self").addModifiers(KModifier.OVERRIDE).addTypeVariable(TypeVariableName("T", ClassName("mindustry.gen", "Entityc"))).returns(TypeVariableName("T")).addStatement("return this as T").build())
                member == "as" -> typeBuilder.addFunction(FunSpec.builder("as").addModifiers(KModifier.OVERRIDE).addTypeVariable(TypeVariableName("T")).returns(TypeVariableName("T")).addStatement("return this as T").build())
                member == "isAdded" -> typeBuilder.addFunction(FunSpec.builder("isAdded").addModifiers(KModifier.OVERRIDE).returns(BOOLEAN).addStatement("return added").build())
                member == "isLocal" -> typeBuilder.addFunction(FunSpec.builder("isLocal").addModifiers(KModifier.OVERRIDE).returns(BOOLEAN).addStatement("return (this as? Any) === (mindustry.Vars.player as? Any)").build())
                member == "isRemote" -> typeBuilder.addFunction(FunSpec.builder("isRemote").addModifiers(KModifier.OVERRIDE).returns(BOOLEAN).addStatement("return false").build())
                member == "serialize" -> typeBuilder.addFunction(FunSpec.builder("serialize").addModifiers(KModifier.OVERRIDE).returns(BOOLEAN).addStatement("return true").build())
                member == "classId" -> typeBuilder.addFunction(FunSpec.builder("classId").addModifiers(KModifier.OVERRIDE).returns(INT).addStatement("return 0").build())
                member == "id" && args.isEmpty() -> typeBuilder.addFunction(FunSpec.builder("id").addModifiers(KModifier.OVERRIDE).returns(INT).addStatement("return id").build())
                member == "id(int)" -> typeBuilder.addFunction(FunSpec.builder("id").addModifiers(KModifier.OVERRIDE).addParameter("id", INT).addStatement("this.id = id").build())
                member == "add" -> typeBuilder.addFunction(FunSpec.builder("add").addModifiers(KModifier.OVERRIDE).addStatement("added = true").build())
                member == "remove" -> typeBuilder.addFunction(FunSpec.builder("remove").addModifiers(KModifier.OVERRIDE).addStatement("added = false").build())
                member == "update" -> typeBuilder.addFunction(FunSpec.builder("update").addModifiers(KModifier.OVERRIDE).build())
                member == "beforeWrite" -> typeBuilder.addFunction(FunSpec.builder("beforeWrite").addModifiers(KModifier.OVERRIDE).build())
                member == "afterRead" -> typeBuilder.addFunction(FunSpec.builder("afterRead").addModifiers(KModifier.OVERRIDE).build())
                member == "afterReadAll" -> typeBuilder.addFunction(FunSpec.builder("afterReadAll").addModifiers(KModifier.OVERRIDE).build())
                member == "read" -> typeBuilder.addFunction(FunSpec.builder("read").addModifiers(KModifier.OVERRIDE).addParameter("reads", ClassName("arc.util.io", "Reads")).build())
                member == "write" -> typeBuilder.addFunction(FunSpec.builder("write").addModifiers(KModifier.OVERRIDE).addParameter("writes", ClassName("arc.util.io", "Writes")).build())
                // ---- Posc ----
                member == "x" && args.isEmpty() -> typeBuilder.addFunction(FunSpec.builder("x").addModifiers(KModifier.OVERRIDE).returns(FLOAT).addStatement("return x").build())
                member == "y" && args.isEmpty() -> typeBuilder.addFunction(FunSpec.builder("y").addModifiers(KModifier.OVERRIDE).returns(FLOAT).addStatement("return y").build())
                member == "x(float)" -> typeBuilder.addFunction(FunSpec.builder("x").addModifiers(KModifier.OVERRIDE).addParameter("x", FLOAT).addStatement("this.x = x").build())
                member == "y(float)" -> typeBuilder.addFunction(FunSpec.builder("y").addModifiers(KModifier.OVERRIDE).addParameter("y", FLOAT).addStatement("this.y = y").build())
                member == "getX" -> typeBuilder.addFunction(FunSpec.builder("getX").addModifiers(KModifier.OVERRIDE).returns(FLOAT).addStatement("return x").build())
                member == "getY" -> typeBuilder.addFunction(FunSpec.builder("getY").addModifiers(KModifier.OVERRIDE).returns(FLOAT).addStatement("return y").build())
                member == "set(float,float)" -> typeBuilder.addFunction(FunSpec.builder("set").addModifiers(KModifier.OVERRIDE).addParameter("x", FLOAT).addParameter("y", FLOAT).addStatement("this.x = x; this.y = y").build())
                member == "set(Position)" -> typeBuilder.addFunction(FunSpec.builder("set").addModifiers(KModifier.OVERRIDE).addParameter("pos", ClassName("arc.math.geom", "Position")).addStatement("set(pos.getX(), pos.getY())").build())
                member == "trns(float,float)" -> typeBuilder.addFunction(FunSpec.builder("trns").addModifiers(KModifier.OVERRIDE).addParameter("x", FLOAT).addParameter("y", FLOAT).addStatement("this.x += x; this.y += y").build())
                member == "trns(Position)" -> typeBuilder.addFunction(FunSpec.builder("trns").addModifiers(KModifier.OVERRIDE).addParameter("pos", ClassName("arc.math.geom", "Position")).addStatement("trns(pos.getX(), pos.getY())").build())
                member == "tileX" -> typeBuilder.addFunction(FunSpec.builder("tileX").addModifiers(KModifier.OVERRIDE).returns(INT).addStatement("return mindustry.core.World.toTile(x)").build())
                member == "tileY" -> typeBuilder.addFunction(FunSpec.builder("tileY").addModifiers(KModifier.OVERRIDE).returns(INT).addStatement("return mindustry.core.World.toTile(y)").build())
                member == "tileOn" -> typeBuilder.addFunction(FunSpec.builder("tileOn").addModifiers(KModifier.OVERRIDE).returns(ClassName("mindustry.world", "Tile").copy(nullable = true)).addStatement("return mindustry.Vars.world.tileWorld(x, y)").build())
                member == "blockOn" -> typeBuilder.addFunction(FunSpec.builder("blockOn").addModifiers(KModifier.OVERRIDE).returns(ClassName("mindustry.world", "Block")).addStatement("return mindustry.content.Blocks.air").build())
                member == "floorOn" -> typeBuilder.addFunction(FunSpec.builder("floorOn").addModifiers(KModifier.OVERRIDE).returns(ClassName("mindustry.world.blocks.environment", "Floor")).addStatement("return mindustry.content.Blocks.air as mindustry.world.blocks.environment.Floor").build())
                member == "buildOn" -> typeBuilder.addFunction(FunSpec.builder("buildOn").addModifiers(KModifier.OVERRIDE).returns(ClassName("mindustry.gen", "Building").copy(nullable = true)).addStatement("return mindustry.Vars.world.buildWorld(x, y)").build())
                member == "onSolid" -> typeBuilder.addFunction(FunSpec.builder("onSolid").addModifiers(KModifier.OVERRIDE).returns(BOOLEAN).addStatement("return false").build())
                // ---- Teamc ----
                member == "team" && args.isEmpty() -> typeBuilder.addFunction(FunSpec.builder("team").addModifiers(KModifier.OVERRIDE).returns(ClassName("mindustry.game", "Team")).addStatement("return team").build())
                member == "team(Team)" -> typeBuilder.addFunction(FunSpec.builder("team").addModifiers(KModifier.OVERRIDE).addParameter("team", ClassName("mindustry.game", "Team")).addStatement("this.team = team").build())
                member == "cheating" -> typeBuilder.addFunction(FunSpec.builder("cheating").addModifiers(KModifier.OVERRIDE).returns(BOOLEAN).addStatement("return team.rules().cheat").build())
                member == "inFogTo" -> typeBuilder.addFunction(FunSpec.builder("inFogTo").addModifiers(KModifier.OVERRIDE).addParameter("viewer", ClassName("mindustry.game", "Team")).returns(BOOLEAN).addStatement("return false").build())
                member == "core" -> typeBuilder.addFunction(FunSpec.builder("core").addModifiers(KModifier.OVERRIDE).returns(ClassName.bestGuess("mindustry.world.blocks.storage.CoreBlock.CoreBuild").copy(nullable = true)).addStatement("return team.core()").build())
                member == "closestCore" -> typeBuilder.addFunction(FunSpec.builder("closestCore").addModifiers(KModifier.OVERRIDE).returns(ClassName.bestGuess("mindustry.world.blocks.storage.CoreBlock.CoreBuild").copy(nullable = true)).addStatement("return team.core()").build())
                member == "closestEnemyCore" -> typeBuilder.addFunction(FunSpec.builder("closestEnemyCore").addModifiers(KModifier.OVERRIDE).returns(ClassName.bestGuess("mindustry.world.blocks.storage.CoreBlock.CoreBuild").copy(nullable = true)).addStatement("return null").build())
                // ---- Drawc ----
                member == "draw" -> typeBuilder.addFunction(FunSpec.builder("draw").addModifiers(KModifier.OVERRIDE).build())
                member == "clipSize" -> typeBuilder.addFunction(FunSpec.builder("clipSize").addModifiers(KModifier.OVERRIDE).returns(FLOAT).addStatement("return 100f").build())
                // ---- Healthc ----
                member == "health" && args.isEmpty() -> typeBuilder.addFunction(FunSpec.builder("health").addModifiers(KModifier.OVERRIDE).returns(FLOAT).addStatement("return health").build())
                member == "health(float)" -> typeBuilder.addFunction(FunSpec.builder("health").addModifiers(KModifier.OVERRIDE).addParameter("health", FLOAT).addStatement("this.health = health").build())
                member == "maxHealth" && args.isEmpty() -> typeBuilder.addFunction(FunSpec.builder("maxHealth").addModifiers(KModifier.OVERRIDE).returns(FLOAT).addStatement("return maxHealth").build())
                member == "maxHealth(float)" -> typeBuilder.addFunction(FunSpec.builder("maxHealth").addModifiers(KModifier.OVERRIDE).addParameter("maxHealth", FLOAT).addStatement("this.maxHealth = maxHealth").build())
                member == "dead" && args.isEmpty() -> typeBuilder.addFunction(FunSpec.builder("dead").addModifiers(KModifier.OVERRIDE).returns(BOOLEAN).addStatement("return false").build())
                member == "dead(boolean)" -> typeBuilder.addFunction(FunSpec.builder("dead").addModifiers(KModifier.OVERRIDE).addParameter("dead", BOOLEAN).build())
                member == "damage" && args.isEmpty() -> typeBuilder.addFunction(FunSpec.builder("damage").addModifiers(KModifier.OVERRIDE).returns(FLOAT).addStatement("return 0f").build())
                member == "damage(float)" -> typeBuilder.addFunction(FunSpec.builder("damage").addModifiers(KModifier.OVERRIDE).addParameter("amount", FLOAT).build())
                member == "damagePierce" && args.isEmpty() -> typeBuilder.addFunction(FunSpec.builder("damagePierce").addModifiers(KModifier.OVERRIDE).returns(FLOAT).addStatement("return 0f").build())
                member == "damagePierce(float)" -> typeBuilder.addFunction(FunSpec.builder("damagePierce").addModifiers(KModifier.OVERRIDE).addParameter("amount", FLOAT).build())
                member == "damageArmorMult" -> typeBuilder.addFunction(FunSpec.builder("damageArmorMult").addModifiers(KModifier.OVERRIDE).addParameter("amount", FLOAT).addParameter("armorMult", FLOAT).build())
                member == "damageContinuous" -> typeBuilder.addFunction(FunSpec.builder("damageContinuous").addModifiers(KModifier.OVERRIDE).addParameter("amount", FLOAT).build())
                member == "damageContinuousPierce" -> typeBuilder.addFunction(FunSpec.builder("damageContinuousPierce").addModifiers(KModifier.OVERRIDE).addParameter("amount", FLOAT).build())
                member == "heal" && args.isEmpty() -> typeBuilder.addFunction(FunSpec.builder("heal").addModifiers(KModifier.OVERRIDE).build())
                member == "heal(float)" -> typeBuilder.addFunction(FunSpec.builder("heal").addModifiers(KModifier.OVERRIDE).addParameter("amount", FLOAT).build())
                member == "healFract" -> typeBuilder.addFunction(FunSpec.builder("healFract").addModifiers(KModifier.OVERRIDE).addParameter("amount", FLOAT).build())
                member == "clampHealth" -> typeBuilder.addFunction(FunSpec.builder("clampHealth").addModifiers(KModifier.OVERRIDE).build())
                member == "kill" -> typeBuilder.addFunction(FunSpec.builder("kill").addModifiers(KModifier.OVERRIDE).build())
                member == "killed" -> typeBuilder.addFunction(FunSpec.builder("killed").addModifiers(KModifier.OVERRIDE).build())
                member == "isValid" -> typeBuilder.addFunction(FunSpec.builder("isValid").addModifiers(KModifier.OVERRIDE).returns(BOOLEAN).addStatement("return !dead && isAdded()").build())
                member == "healthf" -> typeBuilder.addFunction(FunSpec.builder("healthf").addModifiers(KModifier.OVERRIDE).returns(FLOAT).addStatement("return health / maxHealth").build())
                member == "hitTime" && args.isEmpty() -> typeBuilder.addFunction(FunSpec.builder("hitTime").addModifiers(KModifier.OVERRIDE).returns(FLOAT).addStatement("return 0f").build())
                member == "hitTime(float)" -> typeBuilder.addFunction(FunSpec.builder("hitTime").addModifiers(KModifier.OVERRIDE).addParameter("hitTime", FLOAT).build())
                // ---- Hitboxc ----
                member == "hitSize" && args.isEmpty() -> typeBuilder.addFunction(FunSpec.builder("hitSize").addModifiers(KModifier.OVERRIDE).returns(FLOAT).addStatement("return 8f").build())
                member == "hitSize(float)" -> typeBuilder.addFunction(FunSpec.builder("hitSize").addModifiers(KModifier.OVERRIDE).addParameter("hitSize", FLOAT).build())
                member == "hitbox" && args.isEmpty() -> typeBuilder.addFunction(FunSpec.builder("hitbox").addModifiers(KModifier.OVERRIDE).returns(ClassName("arc.math.geom", "Rect")).addStatement("return arc.math.geom.Rect().setCentered(x, y, hitSize(), hitSize())").build())
                member == "hitbox(Rect)" -> typeBuilder.addFunction(FunSpec.builder("hitbox").addModifiers(KModifier.OVERRIDE).addParameter("rect", ClassName("arc.math.geom", "Rect")).addStatement("rect.setCentered(x, y, hitSize(), hitSize())").build())
                member == "hitboxTile" && args.isEmpty() -> typeBuilder.addFunction(FunSpec.builder("hitboxTile").addModifiers(KModifier.OVERRIDE).returns(ClassName("arc.math.geom", "Rect")).addStatement("return arc.math.geom.Rect().setCentered(x, y, hitSize(), hitSize())").build())
                member == "hitboxTile(Rect)" -> typeBuilder.addFunction(FunSpec.builder("hitboxTile").addModifiers(KModifier.OVERRIDE).addParameter("rect", ClassName("arc.math.geom", "Rect")).addStatement("rect.setCentered(x, y, hitSize(), hitSize())").build())
                // ---- Timedc ---- (fout/fslope/fin(Interp) 等是 Scaled default,只补齐抽象成员)
                member == "fin" && args.isEmpty() -> typeBuilder.addFunction(FunSpec.builder("fin").addModifiers(KModifier.OVERRIDE).returns(FLOAT).addStatement("return if (lifetime <= 0f) 0f else time / lifetime").build())
                member == "lifetime" && args.isEmpty() -> typeBuilder.addFunction(FunSpec.builder("lifetime").addModifiers(KModifier.OVERRIDE).returns(FLOAT).addStatement("return lifetime").build())
                member == "time" && args.isEmpty() -> typeBuilder.addFunction(FunSpec.builder("time").addModifiers(KModifier.OVERRIDE).returns(FLOAT).addStatement("return time").build())
                member == "lifetime(float)" -> typeBuilder.addFunction(FunSpec.builder("lifetime").addModifiers(KModifier.OVERRIDE).addParameter("lifetime", FLOAT).addStatement("this.lifetime = lifetime").build())
                member == "time(float)" -> typeBuilder.addFunction(FunSpec.builder("time").addModifiers(KModifier.OVERRIDE).addParameter("time", FLOAT).addStatement("this.time = time").build())
            }
        }
    }

    /** 判断方法名是否与组件 superTypes 中已知 vanilla *c 接口的成员同名(需 override)。 */
    private fun vanillaMembersInSuperTypes(cls: KtClass, methodName: String, componentByName: Map<String, ComponentInfo>): Boolean {
        for (sup in cls.superTypes) {
            // 仅匹配引擎提供的外部 vanilla *c 接口(mindustry.gen.*),避免误伤本地生成/翻译的 *c 接口(如 io.eve.vanilla.gen.Posc)
            if (!sup.startsWith("mindustry.gen.")) continue
            val simple = sup.substringAfterLast('.').removeSuffix("?")
            // 若该 *c 接口对应本地组件(如 vanilla 的 Posc→PosComp),则不是外部接口,跳过
            val compName = simple.removeSuffix("c") + "Comp"
            if (componentByName.containsKey(compName) || componentByName.containsKey(simple.removeSuffix("c"))) continue
            if (KNOWN_VANILLA_C.containsKey(simple)) {
                if (KNOWN_VANILLA_C.getValue(simple).any { it.substringBefore('(') == methodName }) return true
                for (p in VANILLA_C_PARENTS[simple].orEmpty()) {
                    if (KNOWN_VANILLA_C[p]?.any { it.substringBefore('(') == methodName } == true) return true
                }
            }
        }
        return false
    }


    /** 清理注解字符串值:去掉首尾引号与 ::class 后缀。 */
    private fun cleanStr(v: String): String = v.trim().removePrefix("\"").removeSuffix("\"").removeSuffix("::class").trim()

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
            "Tiles" to "mindustry.world.Tiles",
            "Building" to "mindustry.gen.Building",
            "Blocks" to "mindustry.content.Blocks",
            "WorldUnitType" to "disintegration.type.unit.WorldUnitType",
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
            "Units" to "mindustry.entities.Units",
            "DTGroups" to "disintegration.entities.DTGroups",
            "DTShaders" to "disintegration.graphics.DTShaders",
            "DTBlocks" to "disintegration.content.DTBlocks",
            "DTUnitTypes" to "disintegration.content.DTUnitTypes",
            "Pools" to "arc.util.pooling.Pools",
            "Strings" to "arc.util.Strings",
            "Fx" to "mindustry.content.Fx",
            "UnitTypes" to "mindustry.content.UnitTypes",
            "Icon" to "mindustry.ui.Icon",
            "Fonts" to "mindustry.ui.Fonts",
            "Layer" to "mindustry.graphics.Layer",
            "Pal" to "mindustry.graphics.Pal",
            "Drawf" to "mindustry.graphics.Drawf",
            "Pal2" to "disintegration.graphics.Pal2",
            "Events" to "arc.Events",
            "DTVars" to "disintegration.DTVars",
            "DTShaders" to "disintegration.graphics.DTShaders",
            "ItemSeq" to "mindustry.type.ItemSeq",
            "Sector" to "mindustry.type.Sector",
            "Planet" to "mindustry.type.Planet",
            "PlanetGrid" to "mindustry.graphics.g3d.PlanetGrid",
            "SpaceStation" to "disintegration.type.SpaceStation",
            "SpaceLaunchPad" to "disintegration.world.blocks.campaign.SpaceLaunchPad",
            "InterplanetaryLaunchPad" to "disintegration.world.blocks.campaign.InterplanetaryLaunchPad",
            "OrbitalLaunchPad" to "disintegration.world.blocks.campaign.OrbitalLaunchPad",
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
        if (s == "Unit" || s == "void" || s == "kotlin.Unit") return UNIT
        if (nullable) s = s.substring(0, s.length - 1).trim()
        // 含泛型 → 解析泛型,对 Seq/Array/QuadTree 优先保留泛型参数
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
                // 优先保留 Seq<ItemStack> / Array<...> 的泛型参数,避免退化成 Seq<*>
                val inner = io.eve.ktannot.gen.TypeUtils.parseGenericArgs(s)
                if (inner.isNotEmpty()) {
                    val innerTypes = inner.map { typeName(it, componentByName) }.toTypedArray()
                    val ptn = io.eve.ktannot.gen.TypeUtils.parameterizedType(baseTn, *innerTypes)
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
        if (!s.contains(".") && componentByName.containsKey(simple)) {
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