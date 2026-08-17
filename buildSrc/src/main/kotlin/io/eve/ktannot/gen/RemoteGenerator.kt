package io.eve.ktannot.gen

import com.squareup.kotlinpoet.*
import java.io.File

/**
 * 对标 RemoteProcess + CallGenerator:
 * 为每个 @Remote 方法生成 XxxCallPacket 类(Packet 子类),生成 Call 静态类,
 * 生成 write/read/handled 与 handleServer/handleClient 方法,并在注册表登记。
 *
 * 简化:生成 Kotlin 版网络栈(自定义 Packet 基类 + Net 调度),保证可独立运行。
 */
object RemoteGenerator {

    const val CALL_PKG = "io.eve.ktannot.gen"

    private data class MethodEntry(
        val name: String,
        val className: String, // 目标类全名
        val packetName: String,
        val targets: String, // Loc
        val variants: String, // Variant
        val called: String, // Loc
        val unreliable: Boolean,
        val forward: Boolean,
        val id: Int,
        val params: List<KtParameter>,
        val priority: String,
    )

    fun generate(classes: List<KtClass>, outDir: File, mindustryMode: Boolean = false) {
        val methods = mutableListOf<MethodEntry>()
        var lastId = 0
        val packetNames = HashSet<String>()

        for (cls in classes) {
            for (m in cls.methods.filter { it.annotations.containsKey("Remote") }) {
                val ann = m.annotations.getValue("Remote")
                // Kotlin 中 object/companion 的成员天然是静态;顶层函数也需要 public。
                // 对标原版 "public static":object 成员 / 显式 isStatic(isPublic 由 Kotlin 默认)。
                val inObject = cls.kind == Kind.OBJECT
                val publicOk = m.isPublic || !m.isPrivate
                if (!inObject && (!m.isStatic || !m.isPublic)) {
                    System.err.println("[kt-annot] All @Remote methods must be public static: ${cls.fullName}.${m.name}")
                    continue
                }
                val targets = normalizeAnn(ann["targets"]) ?: "server"
                if (targets == "none") {
                    System.err.println("[kt-annot] A @Remote method's targets() cannot be 'none': ${cls.fullName}.${m.name}")
                    continue
                }
                var packetName = m.name.replaceFirstChar { it.uppercase() } + "CallPacket"
                var idx = 1
                while (!packetNames.add(packetName)) {
                    packetName = m.name.replaceFirstChar { it.uppercase() } + "CallPacket" + (idx + 1)
                    idx++
                }
                methods.add(
                    MethodEntry(
                        name = m.name,
                        className = cls.fullName,
                        packetName = packetName,
                        targets = targets,
                        variants = normalizeAnn(ann["variants"]) ?: "all",
                        called = normalizeAnn(ann["called"]) ?: "none",
                        unreliable = ann["unreliable"]?.toBoolean() ?: false,
                        forward = ann["forward"]?.toBoolean() ?: false,
                        id = lastId++,
                        params = m.parameters,
                        priority = ann["priority"]?.lowercase() ?: "normal",
                    )
                )
            }
        }
        if (methods.isEmpty()) return

        val callBuilder = TypeSpec.objectBuilder("Call")

        val register = FunSpec.builder("registerPackets").addModifiers(KModifier.PUBLIC)
        val registerBody = StringBuilder()

        for (ent in methods) {
            // packet class:先加参数字段(public),再 DATA
            val packet = TypeSpec.classBuilder(ent.packetName)
                .addModifiers(KModifier.PUBLIC)
                .superclass(packetBase(mindustryMode))
            ent.params.forEachIndexed { i, p ->
                val skipFirst = !ent.targets.targetsServer() && i == 0
                if (!skipFirst) {
                    packet.addProperty(
                        PropertySpec.builder(p.name, typeName(p.type, mindustryMode), KModifier.PUBLIC).mutable(true)
                            .initializer(defaultValue(p.type)).build()
                    )
                }
            }
            if (!mindustryMode) {
                // stub 模式:DATA 继承自 Packet 桩(默认 Packet.NODATA),不重复声明
            } else {
                // 真实 Mindustry 模式:原版 CallGenerator 在 packet 里声明私有 DATA = NODATA
                packet.addProperty(
                    PropertySpec.builder("DATA", ByteArray::class, KModifier.PRIVATE)
                        .mutable(true)
                        .initializer("NODATA")
                        .build()
                )
            }

            // write
            val write = FunSpec.builder("write")
                .addModifiers(KModifier.OVERRIDE)
                .addParameter("WRITE", writesType(mindustryMode))
            ent.params.forEachIndexed { i, p ->
                val skipFirst = !ent.targets.targetsServer() && i == 0
                if (!skipFirst) {
                    // where=both 且首 player:仅服务端写 player(客户端写空)
                    if (ent.targets == "both" && i == 0) {
                        write.beginControlFlow("if (${if (mindustryMode) "mindustry.Vars.net.server()" else "net.isServer()"})")
                        write.addStatement(writeStmt(p, mindustryMode))
                        write.endControlFlow()
                    } else {
                        write.addStatement(writeStmt(p, mindustryMode))
                    }
                }
            }
            packet.addFunction(write.build())

            // read
            val read = FunSpec.builder("read")
                .addModifiers(KModifier.OVERRIDE)
                .addParameter("READ", readsType(mindustryMode))
                .addParameter("LENGTH", Int::class)
                .addStatement("DATA = READ.b(LENGTH)")
                .build()
            packet.addFunction(read)

            // handled
            // 对标原版 CallGenerator:BAIS.setBytes(DATA) 后逐个字段反序列化(player 参数跳过/条件读取)
            val handled = FunSpec.builder("handled")
                .addModifiers(KModifier.OVERRIDE)
                .addStatement("BAIS.setBytes(DATA)")
            ent.params.forEachIndexed { i, p ->
                val skipFirst = !ent.targets.targetsServer() && i == 0
                if (!skipFirst) {
                    // Loc.both 且第一个参数是 player:仅客户端读取该字段(client 才知道 caller)
                    if (ent.targets == "both" && i == 0) {
                        handled.beginControlFlow("if (${if (mindustryMode) "mindustry.Vars.net.client()" else "net.isClient()"})")
                        handled.addStatement("${p.name} = ${readStmt(p, mindustryMode)}")
                        handled.endControlFlow()
                    } else {
                        handled.addStatement("${p.name} = ${readStmt(p, mindustryMode)}")
                    }
                }
            }
            packet.addFunction(handled.build())
            // handleServer / handleClient
            packet.addFunction(handleMethod(ent, isClient = true, mindustryMode))
            packet.addFunction(handleMethod(ent, isClient = false, mindustryMode))

            val fs = FileSpec.builder(CALL_PKG, ent.packetName)
            if (mindustryMode) fs.addImport("mindustry.io", "TypeIO")
            fs.addType(packet.build()).build().writeTo(outDir)

            registerBody.append("${netRegister(mindustryMode)} { ${ent.packetName}() }\n")

            // call methods
            if (ent.targets.isClient() || ent.variantIsAll()) {
                callBuilder.addFunction(callMethod(ent, toAll = true, forwarded = false, mindustryMode))
            }
            if (ent.targets.isServer() && ent.variantIsOne()) {
                callBuilder.addFunction(callMethod(ent, toAll = false, forwarded = false, mindustryMode))
            }
            if (ent.targets.isServer() && ent.forward) {
                callBuilder.addFunction(callMethod(ent, toAll = true, forwarded = true, mindustryMode))
            }
        }

        register.addCode(registerBody.toString())
        callBuilder.addFunction(register.build())
        FileSpec.builder(CALL_PKG, "Call").addType(callBuilder.build()).build().writeTo(outDir)
    }

    private fun normalizeAnn(raw: String?): String? {
        if (raw == null) return null
        // @Remote(targets = Loc.both) → "Loc.both" → "both";Variant.all → "all"
        val v = raw.substringAfterLast('.')
        return if (v in setOf("server", "client", "both", "none", "one", "all")) v else raw
    }

    private fun callMethodParamType(p: KtParameter, mindustryMode: Boolean): TypeName {
        // 原版 callMethod 参数保留原类型(非空);Player 在 both 下请求端非空,仅序列化时条件处理
        return typeName(p.type, mindustryMode).let { if (it.isNullable) it.copy(nullable = false) else it }
    }

    private fun handleMethod(ent: MethodEntry, isClient: Boolean, mindustryMode: Boolean): FunSpec {
        val name = if (isClient) "handleClient" else "handleServer"
        val builder = FunSpec.builder(name).addModifiers(KModifier.OVERRIDE)
        if (!isClient) {
            builder.addParameter("con", conType(mindustryMode))
            builder.beginControlFlow("if (con.player == null || con.kicked)")
                .addStatement("return")
                .endControlFlow()
            builder.addStatement("val player = con.player")
        } else {
            builder.beginControlFlow("if (!${if (mindustryMode) "mindustry.Vars.net" else "net"}.active())").addStatement("return").endControlFlow()
        }
        // invoke target
        val args = ent.params.joinToString(", ") { p ->
            if (!isClient && p.name == "player") "player" // server: con.player 局部变量
            else if (isClient && p.name == "player") {
                if (mindustryMode) "mindustry.Vars.player" else "player()" // client: 顶层 player() 访问器
            } else p.name
        }
        builder.addStatement("%T.%N($args)", ClassName.bestGuess(ent.className), ent.name)
        return builder.build()
    }

    private fun callMethod(ent: MethodEntry, toAll: Boolean, forwarded: Boolean, mindustryMode: Boolean): FunSpec {
        val builder = FunSpec.builder(ent.name + if (forwarded) "__forward" else "")
            .addModifiers(KModifier.PUBLIC)
            .returns(Unit::class)
        if (!forwarded) builder.addModifiers(KModifier.PUBLIC)

        if (forwarded) builder.addParameter("exceptConnection", conType(mindustryMode))
        if (!toAll && !forwarded) builder.addParameter("playerConnection", conType(mindustryMode))

        // Call 方法参数 = 原方法参数中跳过「单侧客户端时首 player」(对标原版:
        // 仅当 where 非 server 时跳过第一个 player 参数;where=both 保留 player 参数)
        val callParams = ent.params.filterIndexed { i, _ ->
            !(!ent.targets.targetsServer() && i == 0)
        }
        callParams.forEach { p ->
            builder.addParameter(p.name, callMethodParamType(p, mindustryMode))
        }

        // local call
        if (!forwarded && ent.called != "none") {
            if (ent.called != "both") {
                builder.beginControlFlow("if (${checkString(ent.called, mindustryMode)} || !${if (mindustryMode) "mindustry.Vars.net" else "net"}.active())")
            }
            builder.addStatement("%T.%N(${ent.params.joinToString(", ") { if (it.name == "player" && ent.targets.isClient() && !ent.targets.targetsServer()) (if (mindustryMode) "mindustry.Vars.player" else "net.player()") else it.name }})", ClassName.bestGuess(ent.className), ent.name)
            if (ent.called != "both") builder.endControlFlow()
        }

        builder.beginControlFlow("if (${checkString(ent.targets, mindustryMode)})")
        builder.addStatement("val packet = ${ent.packetName}()")
        ent.params.forEachIndexed { i, p ->
            val skipFirst = !ent.targets.targetsServer() && i == 0
            if (!skipFirst) {
                // where=both 且首 player:仅服务端写 player(客户端知道调用者为空)
                if (ent.targets == "both" && i == 0) {
                    builder.beginControlFlow("if (${if (mindustryMode) "mindustry.Vars.net.server()" else "net.isServer()"})")
                    builder.addStatement("packet.${p.name} = ${p.name}")
                    builder.endControlFlow()
                } else {
                    builder.addStatement("packet.${p.name} = ${p.name}")
                }
            }
        }
        val send = when {
            forwarded -> if (ent.called.isClient()) "${sendExcept(mindustryMode)}(exceptConnection, " else "${sendNet(mindustryMode)}("
            toAll -> "${sendNet(mindustryMode)}("
            else -> "playerConnection.send("
        }
        builder.addStatement("$send packet, ${!ent.unreliable})")
        builder.endControlFlow()
        return builder.build()
    }

    private fun checkString(loc: String, mindustryMode: Boolean): String = when (loc) {
        "client" -> if (mindustryMode) "mindustry.Vars.net.client()" else "net.isClient()"
        "server" -> if (mindustryMode) "mindustry.Vars.net.server()" else "net.isServer()"
        "both" -> if (mindustryMode) "mindustry.Vars.net.server() || mindustry.Vars.net.client()" else "net.isServer() || net.isClient()"
        else -> "false"
    }

    // ---- 类型/常量解析(mindustry 模式 → 真实类型) ----
    private fun packetBase(mindustryMode: Boolean): TypeName =
        if (mindustryMode) ClassName("mindustry.net", "Packet") else ClassName(CALL_PKG, "Packet")

    private fun writesType(mindustryMode: Boolean): TypeName =
        if (mindustryMode) ClassName("arc.util.io", "Writes") else ClassName(CALL_PKG, "Writes")

    private fun readsType(mindustryMode: Boolean): TypeName =
        if (mindustryMode) ClassName("arc.util.io", "Reads") else ClassName(CALL_PKG, "Reads")

    private fun conType(mindustryMode: Boolean): TypeName =
        if (mindustryMode) ClassName("mindustry.net", "NetConnection") else ClassName(CALL_PKG, "NetConnection")

    private fun netRegister(mindustryMode: Boolean): String =
        if (mindustryMode) "mindustry.net.Net.registerPacket" else "Net.registerPacket"

    private fun sendNet(mindustryMode: Boolean): String =
        if (mindustryMode) "mindustry.Vars.net.send" else "net.send"

    private fun sendExcept(mindustryMode: Boolean): String =
        if (mindustryMode) "mindustry.Vars.net.sendExcept" else "net.sendExcept"

    private fun String.targetsServer() = this == "server" || this == "both"
    private fun String.targetsClient() = this == "client" || this == "both"
    private fun String.isClient() = this == "client" || this == "both"
    private fun String.isServer() = this == "server" || this == "both"
    private fun MethodEntry.variantIsAll() = variants == "all" || variants == "both"
    private fun MethodEntry.variantIsOne() = variants == "one" || variants == "both"

    private fun typeName(type: String, mindustryMode: Boolean): TypeName = when {
        type.endsWith("Player") || type.contains("Player") -> if (mindustryMode) ClassName("mindustry.gen", "Player").copy(nullable = true) else ClassName(CALL_PKG, "Player").copy(nullable = true)
        type == "Int" || type == "int" || type == "kotlin.Int" -> INT
        type == "Float" || type == "float" || type == "kotlin.Float" -> FLOAT
        type == "Boolean" || type == "boolean" || type == "kotlin.Boolean" -> BOOLEAN
        type == "Long" || type == "long" || type == "kotlin.Long" -> LONG
        type == "Double" || type == "double" || type == "kotlin.Double" -> DOUBLE
        type == "Short" || type == "short" || type == "kotlin.Short" -> SHORT
        type == "Byte" || type == "byte" || type == "kotlin.Byte" -> BYTE
        type == "Char" || type == "char" || type == "kotlin.Char" -> CHAR
        type == "String" || type == "kotlin.String" -> STRING
        type == "Unit" || type == "void" || type == "kotlin.Unit" -> UNIT
        else -> ClassName.bestGuess(type.removePrefix("kotlin."))
    }

    private fun defaultValue(type: String): String = when (type) {
        "Int", "int", "kotlin.Int" -> "0"
        "Float", "float", "kotlin.Float" -> "0f"
        "Boolean", "boolean", "kotlin.Boolean" -> "false"
        "Long", "long", "kotlin.Long" -> "0L"
        "Double", "double", "kotlin.Double" -> "0.0"
        "Short", "short", "kotlin.Short" -> "0"
        "Byte", "byte", "kotlin.Byte" -> "0"
        "Char", "char", "kotlin.Char" -> "'\\u0000'"
        "String", "kotlin.String" -> "\"\""
        else -> if (type.endsWith("Player") || type.contains("Player")) "null" else "null"
    }

    // REAL 模式:arc Writes/Reads 无 obj();仅支持原语与 String。Player 由 TypeIO 实体序列化
    // 生成写语句:Player 用 TypeIO 实体序列化,其余原语用 WRITE.x()
    private fun writeStmt(p: KtParameter, mindustryMode: Boolean): String {
        if (!mindustryMode) return "WRITE.${writeOp(p.type, false)}(${p.name})"
        val op = writeOp(p.type, true)
        return if (p.type.endsWith("Player") || p.type.contains("Player")) {
            "TypeIO.writeEntity(WRITE, ${p.name})"
        } else {
            "WRITE.$op(${p.name})"
        }
    }

    // 生成读语句:Player 用 TypeIO 实体反序列化
    private fun readStmt(p: KtParameter, mindustryMode: Boolean): String {
        if (!mindustryMode) {
            return if (p.type.endsWith("Player") || p.type.contains("Player")) "READ.playerObj()" else "READ.${readOp(p.type, false)}()"
        }
        val op = readOp(p.type, true)
        return if (p.type.endsWith("Player") || p.type.contains("Player")) {
            "TypeIO.readEntity(READ)"
        } else {
            "READ.$op()"
        }
    }

    private fun writeOp(type: String, mindustryMode: Boolean): String = when {
        type.endsWith("Player") || type.contains("Player") -> "obj"
        type == "Int" || type == "int" || type == "kotlin.Int" -> "i"
        type == "Float" || type == "float" || type == "kotlin.Float" -> "f"
        type == "Boolean" || type == "boolean" || type == "kotlin.Boolean" -> "bool"
        type == "Long" || type == "long" || type == "kotlin.Long" -> "l"
        type == "Short" || type == "short" || type == "kotlin.Short" -> "s"
        type == "Byte" || type == "byte" || type == "kotlin.Byte" -> "b"
        type == "String" || type == "kotlin.String" -> "str"
        else -> if (mindustryMode) error("[kt-annot] real mode @Remote param type not serializable: $type (only primitives/String/Player)") else "obj"
    }
    private fun readOp(type: String, mindustryMode: Boolean): String = when {
        type.endsWith("Player") || type.contains("Player") -> if (mindustryMode) "TypeIO.readEntity(READ)" else "obj"
        type == "Int" || type == "int" || type == "kotlin.Int" -> "i"
        type == "Float" || type == "float" || type == "kotlin.Float" -> "f"
        type == "Boolean" || type == "boolean" || type == "kotlin.Boolean" -> "bool"
        type == "Long" || type == "long" || type == "kotlin.Long" -> "l"
        type == "Short" || type == "short" || type == "kotlin.Short" -> "s"
        type == "Byte" || type == "byte" || type == "kotlin.Byte" -> "b"
        type == "String" || type == "kotlin.String" -> "str"
        else -> if (mindustryMode) error("[kt-annot] real mode @Remote param type not serializable: $type (only primitives/String/Player)") else "obj"
    }
}