# kt-annotations

> 纯 Kotlin 实现的 Mindustry 注解处理器：用注解驱动编译期生成实体组件、网络包、结构体、逻辑语句与贴图加载代码。
> 对标 Mindustry 官方 `mindustry.annotations`（EntityAnno）的 Kotlin 移植，**已通过 stub 单测 + v159.7 headless server 实机自检 + full OpenGL 客户端验证**。

- 处理方式：Gradle 插件内嵌 **Kotlin PSI** 扫描器 + **KotlinPoet** 代码生成器，不依赖 javac/kapt，无需注解处理器的 JVM 配置
- 两种模式：`mindustryMode=true` 生成对接真实引擎的代码；`false` 生成对接内置桩的独立可运行代码（便于纯 JVM 单测）
- 版本：插件 `io.eve.ktannot` / 注解库 `io.eve.ktannot:annotations`，`0.1.0`

---

## 目录

1. [核心概念](#一核心概念)
2. [环境要求](#二环境要求)
3. [快速开始（学习篇）](#三快速开始学习篇)
4. [注解参考](#四注解参考)
5. [生成代码详解](#五生成代码详解)
6. [实战篇：真实 Mindustry mod](#六实战篇真实-mindustry-mod)
7. [vanilla 组件库](#七vanilla-组件库)
8. [插件配置参考](#八插件配置参考)
9. [项目结构与开发](#九项目结构与开发)
10. [验证与测试](#十验证与测试)
11. [已知限制](#十一已知限制)
12. [FAQ](#十二faq)
13. [许可证](#十三许可证)

---

## 一、核心概念

### 1.1 它解决什么问题

Mindustry 官方用 Java 注解处理器（EntityAnno）在编译期做「组件 → 实体」的装配：

```java
@EntityDef({Unitc.class, UnitEntityc.class})
public abstract class UnitEntity { ... }
```

kt-annotations 把这条链路搬到 Kotlin：你写 `@Component` / `@EntityDef` 注解的 Kotlin 类，构建时插件扫描源码、生成实体类/接口/网络包等代码，和你的手写代码一起编译进 mod。

### 1.2 处理流水线

```
src/**/*.kt
   │  ContentScanner（Kotlin PSI 解析：类/字段/方法/注解，类型名→FQN）
   ▼
KtClass / KtField / KtMethod 模型
   │  5 个生成器
   ▼
build/generated/ktannot/main/kotlin/   ← 生成代码
   │  与手写代码一起编译（需手动把该目录加进 kotlin srcDir）
   ▼
mod jar
```

### 1.3 两种模式

| | `mindustryMode = false`（默认） | `mindustryMode = true` |
|---|---|---|
| 生成代码对接 | 内置桩：`io.eve.ktannot.gen.Packet/ByteBuf/Writes/Reads/Net/Player/Core` | 真实引擎：`mindustry.net.Packet`、`arc.util.io.Writes/Reads`、`arc.Core.atlas`、`mindustry.io.TypeIO`、`mindustry.Vars.net` |
| 用途 | 纯 JVM 单元测试（`tests/` 模块） | 真实 Mindustry mod（`realmod/`、`vanilla/` 模块） |
| 实体同步 IO | `ByteBuf.putFloat/getFloat` | `Writes.f()/Reads.f()` |
| 贴图加载 | `Core.atlas.find(...)` | `arc.Core.atlas.find(...)` |
| 网络包 | 桩 `Packet` 子类 | `mindustry.net.Packet` 子类，注册到 `Net.registerPacket` |

### 1.4 生成代码的位置与包

- 默认输出：`build/generated/ktannot/main/kotlin/`
- 默认包：`io.eve.ktannot.gen`（可通过 `ktAnnotations { genPackage = "..." }` 修改）
- **重要**：生成代码会给接口/实体打上 `@EntityInterface` 注解，该注解引用 `genPackage` 包下的 `EntityInterface` —— 消费模块必须自己在 gen 包中声明一个（见 [8.3](#83-genpackage-与-entityinterface)）。

---

## 二、环境要求

- JDK 17+
- Gradle 8.11+（仓库自带 wrapper 8.11.1）
- Kotlin 2.2.0（KGP 与生成器内部 kotlin-compiler-embeddable 同版本）
- Mindustry 目标版本：v159.7（验证基线）

---

## 三、快速开始（学习篇）

### 3.1 发布到本地仓库（首次）

```bash
./gradlew :annotations:publishToMavenLocal :buildSrc:publishToMavenLocal
```

产物（`~/.m2/repository/io/eve/ktannot/`）：

| 坐标 | 内容 |
|---|---|
| `io.eve.ktannot:buildSrc:0.1.0` | 插件实现 |
| `io.eve.ktannot:io.eve.ktannot.gradle.plugin:0.1.0` | 插件 marker |
| `io.eve.ktannot:annotations:0.1.0` | 注解库（运行时依赖） |

### 3.2 在消费项目接入

`settings.gradle.kts`：

```kotlin
pluginManagement {
    repositories {
        mavenLocal()            // ← 本地发布后从这里解析插件
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}
```

`build.gradle.kts`：

```kotlin
plugins {
    kotlin("jvm") version "2.2.0"
    id("io.eve.ktannot") version "0.1.0"
}

ktAnnotations {
    mindustryMode = true        // 生成对接真实 Mindustry 引擎的代码
    genPackage = "my.mod.gen"   // 默认 io.eve.ktannot.gen
}

dependencies {
    implementation("io.eve.ktannot:annotations:0.1.0")
    // 真实 mod 还需要引擎依赖（见实战篇）
}

// 生成目录加入 Kotlin 源码集（插件只自动加 java srcDir 与任务依赖，Kotlin 目录需手动声明）
sourceSets {
    main {
        kotlin.srcDir("build/generated/ktannot/main/kotlin")
    }
}

tasks.named("build") { dependsOn("generateKtAnnotations") }
```

### 3.3 写第一个注解

```kotlin
package my.mod

import io.eve.ktannot.*

@Component
abstract class PosComp {
    var x: Float = 0f
    var y: Float = 0f
}

@EntityDef([PosComp::class])
abstract class TestUnitDef
```

### 3.4 构建

```bash
./gradlew build
```

生成结果（`build/generated/ktannot/main/kotlin/my/mod/gen/`）：

- `Posc.kt` —— 接口：`public interface Posc { public var x: Float; public var y: Float }`
- `TestUnit.kt` —— 实体类：实现 `Posc` 等接口，字段合并、`serialize()`、组件方法体、`toString()`

```kotlin
val u = my.mod.gen.TestUnit()
u.x = 10f
u.y = 20f
```

---

## 四、注解参考

> 全部注解定义在 `annotations/src/main/kotlin/io/eve/ktannot/Annotations.kt`，`@Retention(SOURCE)`，运行时无反射开销。

### 4.1 命名规则（生成器约定）

| 源码声明 | 生成物 |
|---|---|
| `XxxComp`（`@Component`） | 接口 `Xxxc`；`base=true` 时另生成抽象基类 `XxxBase` |
| `XxxDef`（`@EntityDef`） | 实体类 `Xxx`（去 `Def`/`Comp` 后缀；若与首组件基类同名则加 `Entity`） |
| `gXxx`（`@GroupDef`） | 组索引接口 `IndexableEntity__Xxx` |
| `XxxStruct`（`@Struct`） | 值类型 object `Xxx` |
| `fun xxx(...)`（`@Remote`） | 包类 `XxxCallPacket` + `Call.xxx(...)` 调用方法 |

### 4.2 实体体系

#### `@Component(base = false, genInterface = true)` —— 组件声明（类级）

- 组件类通常写成 `abstract class`；字段会生成到实体类，方法体会合并进实体类。
- `base=true`（或加 `@BaseComponent`）表示该组件还生成**抽象基类** `XxxBase`，包含自身与依赖组件的字段，供手写子类复用。
- 组件依赖两种表达方式：
  - 继承另一个组件类：`abstract class UnitComp : PosComp()`
  - 实现生成的 `*c` 接口：`abstract class MyUnitComp : Entityc`（组件依赖通过接口递归收集）

#### `@BaseComponent` —— 基组件标记（类级）

等价 `@Component(base = true)`。示例中的 `EntityComp` 用它声明，生成实体获得 Entityc 生命周期：

```kotlin
@Component
@io.eve.ktannot.BaseComponent
abstract class EntityComp {
    @kotlin.jvm.Transient private var added = false
    @kotlin.jvm.Transient var id: Int = mindustry.entities.EntityGroup.nextId()
    fun isAdded(): Boolean = added
    open fun update() {}
    open fun remove() { added = false }
    open fun add() { added = true }
    fun isLocal(): Boolean = (this as? Any) === (mindustry.Vars.player as? Any)
    fun isRemote(): Boolean = false
    @Suppress("UNCHECKED_CAST")
    fun <T : mindustry.gen.Entityc> self(): T = this as T
    @Suppress("UNCHECKED_CAST")
    fun <T> `as`(): T = this as T
    @io.eve.ktannot.InternalImpl abstract fun classId(): Int
    @io.eve.ktannot.InternalImpl abstract fun serialize(): Boolean
    @io.eve.ktannot.MethodPriority(1f)
    open fun read(reads: arc.util.io.Reads) { afterRead() }
    open fun write(writes: arc.util.io.Writes) {}
    open fun beforeWrite() {}
    open fun afterRead() {}
    open fun afterReadAll() {}
}
```

只要组件集合中存在 `EntityComp`，**所有** `@EntityDef` 实体都会实现 `Entityc` 接口并合并这些生命周期方法。

#### `@EntityDef(value, isFinal = true, pooled = false, serialize = true, genio = true, legacy = false, excludeGroups = [])` —— 实体定义（类级）

```kotlin
@EntityDef([PosComp::class, SyncComp::class], serialize = true, isFinal = true)
abstract class TestUnitDef
```

- `value`：组件列表（写 `PosComp::class` 或 `Posc::class` 均可），依赖组件递归收集、保序去重。
- `isFinal`：实体类 `final`（默认）或 `open`。
- `serialize`：实体 `serialize()` 返回值（默认 true）。
- `pooled` / `genio` / `legacy` / `excludeGroups`：当前版本已解析但**尚未参与生成逻辑**（见 [11. 已知限制](#十一已知限制)）。
- 生成实体内容：组件字段合并（去重；`@Import` 跳过；被 `getX()` 方法或同名方法替代的字段转 `@JvmField` 后备存储）、组件方法合并（签名去重，组件方法优先于 EntityComp 基方法）、`serialize()`、同步方法、`toString()`（默认返回类名）、组接口实现。

#### `@GroupDef(value, exclude = [], collide = false, spatial = false, mapping = false, update = false)` —— 实体组（类级）

```kotlin
@GroupDef(value = [PosComp::class], collide = true, spatial = true)
abstract class gPosGroup
```

- 生成 `IndexableEntity__PosGroup` 接口；实体若包含 `value` 全部组件且不包含 `exclude` 组件，则实现该接口，并生成 `protected var index_PosGroup` 与 `override fun setIndex__PosGroup(index: Int)`。
- `collide/spatial/mapping/update` 参数当前**未参与生成**（仅 value/exclude 决定实体归属）。

#### `@EntityInterface` —— 生成代码标记注解

生成器给所有生成的接口与实体类自动加 `@EntityInterface`。消费模块需在 `genPackage` 包下自行声明同名注解（SOURCE retention 即可）。

#### `@SyncField(value, clamped = false)` —— 同步字段（字段级）

- 仅当实体组件列表中存在**类名含 `Sync`** 的组件时生效（如 `SyncComp`）。
- 字段类型必须是 `Float`（否则打印错误并跳过）。
- 生成：`xxx_TARGET_` / `xxx_LAST_` 两个私有字段 + `writeSync/readSync`（字段按名称排序；mindustry 模式用 `arc.util.io.Writes/Reads`，stub 模式用 `ByteBuf`）。
- `value`（线性/角度插值）与 `clamped` 参数当前未区分，注解存在即同步。

#### `@Import` —— 导入字段（字段级）

标记字段由其他组件提供，本组件不重复生成该属性，仅表达依赖。

#### `@ReadOnly` —— 只读字段（字段/方法级）

接口中生成 `val`（不可变属性），基类中跳过该字段。

#### 已声明但生成器尚未接入的注解

`@Replace`、`@Final`、`@SyncLocal`、`@NoSync`、`@NoSerialize`、`@InternalImpl`、`@MethodPriority`、`@CallSuper`、`@OverrideCallSuper`、`@StyleDefaults`、`@TypeIOHandler` —— 已定义在注解库中（对齐原版 API），当前生成器未消费，使用时不会报错但也不产生行为差异。

### 4.3 值类型：`@Struct` + `@StructField(bits)`

对标原版 `@Struct`：把一组字段**位打包**进一个整数基元，生成 `object` 提供 `get/set` 与构造函数。

```kotlin
@Struct
class PackedPosStruct {          // 类名必须以 Struct 结尾
    var x: Short = 0             // 16 bit
    var y: Short = 0             // 16 bit
    @StructField(8)
    var layer: Byte = 0          // 8 bit
    var alive: Boolean = false   // 1 bit
}
```

- 支持字段类型与位宽：`Boolean`(1) / `Byte`(8) / `Short`(16) / `Float`(32) / `Int`(32) / `Long`(64)。
- 总位宽决定存储类型：`≤8 → Byte`、`≤16 → Short`、`≤32 → Int`、`>32 → Long`。
- 生成 `PackedPos` object：

```kotlin
val packed = PackedPos.get(3.toShort(), (-5).toShort(), 110.toByte(), true)  // 构造
PackedPos.x(packed)          // 读 → 3
PackedPos.x(packed, 100)     // 写 → 新值
PackedPos.alive(packed)      // 读 → true
```

- 限制：空字段会报错；非法字段类型抛 `IllegalArgumentException`。

### 4.4 网络：`@Remote(targets, variants, called, forward, unreliable, priority)`

对标原版 `RemoteProcess + CallGenerator`：为一个远程可调用方法生成网络包类、`Call` 静态调用门面与注册逻辑。

```kotlin
object NetCalls {              // ⚠️ 必须放在 object 中（生成器要求静态成员）
    @Remote(targets = Loc.both, variants = Variant.all, called = Loc.both)
    fun announce(player: Player, message: String, value: Int) { ... }

    @Remote(targets = Loc.server, variants = Variant.one)
    fun teleport(player: Player, x: Float, y: Float) { ... }
}
```

参数类型白名单（mindustry 模式）：**原语（Int/Float/Boolean/Long/Double/Short/Byte/Char/String）+ `Player`**。其他类型生成时抛错。

- `targets: Loc` —— 允许触发的端：`server` / `client` / `both`（`none` 非法，生成时报错跳过）。
- `variants: Variant` —— 生成调用变体：`one`（单连接：`Call.xxx(playerConnection, ...)`）、`all`（广播）、`both`（两者）。
- `called: Loc` —— 是否在本地也直接调用（`server`/`client`/`both`/`none`）。
- `forward` —— 额外生成 `Call.xxx__forward(exceptConnection, ...)` 转发方法。
- `unreliable` —— 发送时可靠标志取反（`send(packet, !unreliable)`）。
- `priority` —— 已解析，当前未参与生成。

生成物（`genPackage` 下）：

- `AnnounceCallPacket`：`mindustry.net.Packet`（mindustry 模式）子类，含 `write(WRITE: Writes)` / `read(READ: Reads, LENGTH: Int)` / `handled()` / `handleClient()` / `handleServer(con: NetConnection)`。
- `Call` object：`registerPackets()`（`Net.registerPacket { XxxCallPacket() }`）+ 各调用方法。
- 序列化细节：
  - `Player` 参数 mindustry 模式用 `mindustry.io.TypeIO.writeEntity/readEntity`。
  - `targets = both` 且首个参数是 `player` 时：`write` 只在 `net.server()` 写、`handled` 只在 `net.client()` 读（对齐原版 `writePlayerSkipCheck`）。
  - `handleServer` 开头校验 `con.player == null || con.kicked` 直接 return；`handleClient` 校验 `net.active()`。
- 使用：mod `init()` 里调用 `Call.registerPackets()`（见实战篇）。

枚举：

```kotlin
enum class Loc(val isServer: Boolean, val isClient: Boolean) {
    server(true, false), client(false, true), both(true, true), none(false, false)
}
enum class Variant(val isOne: Boolean, val isAll: Boolean) {
    one(true, false), all(false, true), both(true, true)
}
enum class PacketPriority { low, normal, high }
```

### 4.5 逻辑：`@RegisterStatement(name)` —— 自定义逻辑语句（类级）

对标原版 `LogicStatementProcessor`：注册一个 `LStatement` 子类，生成 `LogicIO` 的读写与语句表。

```kotlin
@RegisterStatement("testlog")
class TestLogStatement : LStatement() {
    var message: String = ""
    override fun build(table: Table) { ... }
    override fun build(builder: LAssembler): LExecutor.LInstruction? = null
}
```

生成 `LogicIO` object：

```kotlin
LogicIO.allStatements          // Seq<Prov<LStatement>>：Seq.with(Prov { TestLogStatement() }, ...)
LogicIO.write(obj, out)        // 按类匹配写 "testlog message"
LogicIO.read(tokens, length)   // tokens[0]=="testlog" 时构造对象，逐字段 valueOf 还原，调用 afterRead()
```

- 序列化字段：非 `static`、非 `@Transient` 的字段按声明顺序读写。
- 读取用类型对应的 `toInt/toFloat/toBoolean/toLong/toShort/toByte/toString`；越界 token 安全跳过。

### 4.6 资源：`@Load(value, length = 1, lengths = [], fallback = "error")` —— 贴图自动加载（字段级）

对标原版 `LoadRegionProcessor`：为方块字段生成 `ContentRegions.loadRegions(content)` 中的图集查找。

```kotlin
class KtTestBlock : mindustry.world.Block("kt-test-block") {
    @Load("@-top")
    var topRegion: TextureRegion = TextureRegion()

    @Load(value = "@-frames", length = 4)
    var frames = arrayOfNulls<TextureRegion>(4)
}
```

- 占位符：`@` → `content.name`；`@size` → `(content as Block).size`；`#` / `#1` → 第一维下标；`#2` → 第二维下标。
- `length` / `lengths`：数组字段，生成 `for (INDEX0 in 0 until len)` 循环（`lengths` 多维多下标当前仅判定未完整展开）。
- `fallback`：非 `"error"` 时作为 `atlas.find(name, fallback)` 的回退参数。
- mindustry 模式生成 `arc.Core.atlas.find(...)`；headless 无 atlas，`@Load` 仅在客户端有意义。
- 调用入口：生成的 `ContentRegions.loadRegions(content: MappableContent)`，由 mod 在方块 `load()` 中调用（示例见实战篇）。

---

## 五、生成代码详解

以 `realmod`（`genPackage = io.eve.ktannot.gen`）为例，`@EntityDef([PosComp::class, SyncComp::class]) abstract class TestUnitDef` 生成：

### 5.1 组件接口（`Posc.kt`）

```kotlin
package io.eve.ktannot.gen

@EntityInterface
public interface Posc {
  public var x: Float
  public var y: Float
}
```

接口生成规则：组件方法 → `abstract fun`（跳过 private/static/含泛型参数的方法，父接口重复签名去重）；字段 → `var` 属性（`@ReadOnly` 变 `val`）；与外部接口（`Sized/QuadTreeObject/Scaled/Displayable/Senseable/Settable/Ranged/UnitController/Entityc`）同名的成员自动加 `override`。

### 5.2 实体类（`TestUnit.kt`）

```kotlin
@EntityInterface
public final class TestUnit : Entityc, Posc, Syncc, IndexableEntity__PosGroup {
  public override var id: Int = mindustry.entities.EntityGroup.nextId()
  public override var x: Float = 0f
  public override var y: Float = 0f
  public override var hp: Float = 100f
  private var hp_TARGET_: Float = 0f
  private var hp_LAST_: Float = 0f
  protected var index_PosGroup: Int = 0

  override fun serialize(): Boolean = true
  override fun isAdded(): Boolean = added
  override fun add() { added = true }
  override fun remove() { added = false }
  override fun classId(): Int { TODO("not implemented by EntityGenerator — user supplies implementation in component body or overrides") }

  override fun writeSync(write: Writes) {
    write.f(this.angle)
    write.f(this.hp)
  }
  override fun readSync(read: Reads) {
    this.angle = read.f()
    this.hp = read.f()
  }
}
```

要点：

- 字段按「EntityComp 基字段 → 各组件字段」合并，重名去重；`@SyncField` 字段额外生成 `_TARGET_/_LAST_` 私有字段与 `writeSync/readSync`（按字段名排序）。
- 组件方法体被**文本合并**进实体：自动做 `self()→this`、`Vars→mindustry.Vars`、`Mathf→arc.math.Mathf`、`min→kotlin.math.min`、`hitSize→hitSize()`、常见简单名→FQN 等替换。
- 没有方法体的抽象方法生成 `TODO(...)` 占位，需在组件体实现或实体侧覆写。
- 组归属：实体包含 `gPosGroup` 的 `value` 全部组件 → 实现 `IndexableEntity__PosGroup` 并生成 `index_PosGroup` 字段。

### 5.3 组索引接口（`IndexableEntity__PosGroup.kt`）

```kotlin
public interface IndexableEntity__PosGroup {
  public abstract fun setIndex__PosGroup(index: Int)
}
```

### 5.4 Struct（`PackedPos.kt`）

```kotlin
public object PackedPos {
  public val bitMaskX: Int = (0xFFFFL).toInt()
  public fun x(packed: Int): Short = ((packed ushr 0) and 0xFFFFL).toShort()
  public fun x(packed: Int, `value`: Short): Int = ...
  public fun `get`(x: Short, y: Short, layer: Byte, alive: Boolean): Int { ... }
}
```

### 5.5 Remote（`AnnounceCallPacket.kt` + `Call.kt`）

```kotlin
public class AnnounceCallPacket : Packet() {
  public var message: String = ""
  public var value: Int = 0
  private var DATA: ByteArray = NODATA
  override fun write(WRITE: Writes) { ... }          // both 模式首 player 仅 server 写
  override fun read(READ: Reads, LENGTH: Int) { DATA = READ.b(LENGTH) }
  override fun handled() { BAIS.setBytes(DATA); ... } // both 模式首 player 仅 client 读
  override fun handleClient() { if (!mindustry.Vars.net.active()) return; NetCalls.announce(mindustry.Vars.player, message, value) }
  override fun handleServer(con: NetConnection) { if (con.player == null || con.kicked) return; NetCalls.announce(con.player, message, value) }
}

public object Call {
  public fun registerPackets() {
    mindustry.net.Net.registerPacket { AnnounceCallPacket() }
    mindustry.net.Net.registerPacket { TeleportCallPacket() }
  }
  public fun announce(player: Player, message: String, value: Int) { ... }
  public fun teleport(playerConnection: NetConnection, x: Float, y: Float) { ... }
}
```

### 5.6 Logic（`LogicIO.kt`）

```kotlin
public object LogicIO {
  public val allStatements: Seq<Prov<LStatement>> = Seq.with(Prov { TestLogStatement() })
  public fun write(obj: Any, out: StringBuilder) { ... }
  public fun read(tokens: Array<String>, length: Int): LStatement? { ... }
}
```

### 5.7 资源（`ContentRegions.kt`）

```kotlin
public object ContentRegions {
  public fun loadRegions(content: MappableContent) {
    if (content is KtTestBlock) {
      content.topRegion = arc.Core.atlas.find(content.name + "-top")
      for (INDEX0 in 0 until 4) {
        content.frames[INDEX0] = arc.Core.atlas.find(content.name + "-frames" + INDEX0)
      }
    }
  }
}
```

---

## 六、实战篇：真实 Mindustry mod

> 完整可运行示例见仓库 `realmod/` 模块（v159.7 实机验证通过）。以下按步骤讲解。

### 6.1 工程骨架

```
realmod/
├── build.gradle.kts          # 插件 + mindustryMode + modJar
├── mod.hjson
└── src/main/kotlin/io/eve/
    ├── ktannot/gen/EntityInterface.kt   # gen 包标记注解（必须自备）
    └── realmod/
        ├── RealMod.kt                   # Mod 入口：注册包 + headless 自检
        └── RealModContent.kt            # 全部注解使用示例
```

`build.gradle.kts` 关键点：

```kotlin
plugins {
    id("io.eve.ktannot")
    kotlin("jvm") version "2.2.0"
}
ktAnnotations { mindustryMode = true; genPackage = "io.eve.ktannot.gen" }
dependencies {
    implementation("com.github.Anuken.Mindustry:core:v159.7")   // 引擎核心
    implementation("com.github.Anuken.Arc:arc-core:208a754044") // arc（core 传递依赖同 commit）
    implementation("io.eve.ktannot:annotations:0.1.0")
}
sourceSets { main { kotlin.srcDir("src/main/kotlin"); kotlin.srcDir("build/generated/ktannot/main/kotlin") } }
```

### 6.2 Mod 入口与注册

```kotlin
class RealMod : Mod() {
    override fun init() {
        io.eve.ktannot.gen.Call.registerPackets()   // 注册 @Remote 生成的包
    }
    override fun loadContent() {
        // @Load 生成物在方块 load() 里调用（headless 无 atlas 需跳过）
    }
}
```

### 6.3 自检模式（headless 服务器）

`realmod` 在 `ServerLoadEvent` 后手动构造 3×3 测试世界（`world.resize + beginMapLoad + tiles.fill + endMapLoad + state.set(playing) + logic.play()`），随后跑 9 项 KTA 自检：Struct 位打包往返、Sync 同步往返、Group instanceof、LogicIO 字符串往返、Remote 包往返、FullUnit 生命周期 + EntityMapping 注册、Building 生命周期、vanilla Unit/SimpleEntity 生命周期。全部通过后 `arc.Core.app.exit()`。

```bash
java -jar server-159.7-release.jar   # config/mods 放 realmod.jar，观察 KTA-* 日志
```

### 6.4 实体与 EntityMapping

当前版本**不自动生成** `create()`/EntityMapping 注册表（区别于原版 EntityAnno），需手动注册：

```kotlin
val prov = arc.func.Prov { io.eve.ktannot.gen.MyFullUnit() }
EntityMapping.nameMap.put("my-full-unit", prov)
EntityMapping.register("my-full-unit-v2", prov)   // 自定义 id 别名
val u = io.eve.ktannot.gen.MyFullUnit()          // 直接构造
u.add(); u.remove()                              // Entityc 生命周期
```

### 6.5 打包 mod jar

```kotlin
tasks.register<Jar>("modJar") {
    dependsOn("classes")
    from(sourceSets["main"].output)
    // 合并运行时 classpath（Kotlin stdlib + 注解库），排除引擎已含的 mindustry/arc
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/MANIFEST.MF")
        exclude("mindustry/**", "arc/**", "generated/**", "org/jbox2d/**", "com/codex/**")
    }
    from("mod.hjson") { into("/") }
    from("assets") { into("assets") }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
```

产物 `build/libs/realmod.jar` 直接放入服务器的 `config/mods/`。

### 6.6 完整验证链路（推荐流程）

1. `./gradlew :tests:test` —— stub 单测（纯 JVM，3 项）
2. `./gradlew :realmod:modJar` + headless server 跑 KTA 自检（9 项）
3. `mindustry-mod-validator-full`（OpenGL full 客户端）验证贴图加载、shader 编译、无运行时错误

---

## 七、vanilla 组件库

`vanilla/` 模块提供对齐 Mindustry v159.7 原版的 **44 个 Kotlin 组件**（`io.eve.vanilla.comp`），开箱即用：

```
EntityComp  PosComp  TeamComp  HealthComp  HitboxComp  VelComp  RotComp  DrawComp
TimedComp  TimerComp  SyncComp  DamageComp  ShieldComp  StatusComp  ItemsComp
WeaponsComp  BuilderComp  MinerComp  MechComp  LegsComp  CrawlComp  TankComp
WaterMoveComp  WaterCrawlComp  ElevationMoveComp  UnderwaterMoveComp  PhysicsComp
BulletComp  PlayerComp  UnitComp  BuildingComp  BlockUnitComp  ... 等
```

- 组件间依赖通过继承/实现 `*c` 接口表达（如 `UnitComp` 依赖 `Healthc/Physicsc/Hitboxc/Statusc/Teamc/...`）。
- `entity/EntityDefs.kt` 给出两个组装示例：

```kotlin
@EntityDef([PosComp::class, TeamComp::class, HealthComp::class, HitboxComp::class,
           VelComp::class, RotComp::class, DrawComp::class, TimedComp::class])
abstract class UnitDef            // → io.eve.vanilla.gen.Unit

@EntityDef([PosComp::class, HealthComp::class])
abstract class SimpleEntityDef    // → io.eve.vanilla.gen.SimpleEntity

@GroupDef([PosComp::class], spatial = true, mapping = true)
abstract class gpos               // → IndexableEntity__pos
```

- `vanilla` 使用独立 gen 包 `io.eve.vanilla.gen`（同样自备 `EntityInterface.kt`），与 `realmod` 的 `io.eve.ktannot.gen` 互不干扰。
- 注意：`comp/PosTeamDef.kt` 是占位目标定义（残留 `mindustry.annotations.Annotations.*` 引用，当前不参与生成）。

---

## 八、插件配置参考

### 8.1 `ktAnnotations {}` 扩展

| 属性 | 默认值 | 说明 |
|---|---|---|
| `sourceDir` | `src/main/kotlin` | 扫描的 Kotlin 源码目录（工程目录结构不同时修改，如 `src`） |
| `outputDir` | `build/generated/ktannot/main/kotlin` | 生成代码输出目录 |
| `mindustryMode` | `false` | true 生成对接真实 Mindustry/arc 的代码 |
| `genPackage` | `io.eve.ktannot.gen` | 生成代码的包名 |

### 8.2 任务与自动接线

- 任务：`generateKtAnnotations`（group `kt-annotations`），扫描 → 依次运行 5 个生成器（Entity/Struct/Region/Remote/Logic）→ 输出。
- 插件 `afterEvaluate` 自动：
  - 把 `outputDir` 加入 `main` 的 **java** srcDir；
  - 让 `compileKotlin` 依赖生成目录（`dependsOn(outputDir)`）。
- **不自动做**：把生成目录加入 **kotlin** srcDir（插件刻意不引用 KGP 的 KotlinSourceSet 类型），消费模块需自行：

```kotlin
sourceSets { main { kotlin.srcDir("build/generated/ktannot/main/kotlin") } }
tasks.named("build") { dependsOn("generateKtAnnotations") }
```

### 8.3 `genPackage` 与 `EntityInterface`

生成器会给每个接口/实体加 `@EntityInterface`（引用 `genPackage` 包）。因此消费模块必须在 gen 包下声明它：

```kotlin
// 例：src/main/kotlin/io/eve/ktannot/gen/EntityInterface.kt
package io.eve.ktannot.gen
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class EntityInterface
```

### 8.4 生成器一览

| 生成器 | 消费注解 | 产物 |
|---|---|---|
| `EntityGenerator` | `@Component/@BaseComponent/@EntityDef/@GroupDef/@SyncField/@Import/@ReadOnly` | `*c` 接口、`*Base` 基类、实体类、`IndexableEntity__*` 组接口 |
| `StructGenerator` | `@Struct/@StructField` | 位打包 `object` |
| `RemoteGenerator` | `@Remote` | `*CallPacket` + `Call` |
| `LogicGenerator` | `@RegisterStatement` | `LogicIO` |
| `RegionGenerator` | `@Load` | `ContentRegions` |
| `AssetsGenerator`（已实现未接线） | — | `Tex/Sounds/Musics` 存根 |

---

## 九、项目结构与开发

```
kt-annotations/
├── annotations/            # 注解定义（运行时依赖 io.eve.ktannot:annotations）
│   └── src/main/kotlin/io/eve/ktannot/Annotations.kt
├── buildSrc/               # Gradle 插件（Kotlin PSI 扫描 + 5 生成器）
│   ├── KtAnnotationsPlugin.kt
│   └── gen/  Scanner.kt Model.kt EntityGenerator.kt StructGenerator.kt
│            RemoteGenerator.kt LogicGenerator.kt RegionGenerator.kt
│            AssetsGenerator.kt TypeUtils.java
├── tests/                  # stub 单测模块（桩 arc/mindustry 依赖 + 3 个测试）
├── realmod/                # 真实 Mindustry v159.7 mod 验证模块
├── vanilla/                # 原版组件库 + 实体组装
├── cursedmod/              # 纯 hjson 演示 mod（未使用注解）
└── PLAN-P2..P6.md          # 各阶段设计文档
```

常用命令：

```bash
./gradlew build --no-daemon                          # 全量构建
./gradlew :tests:test --rerun-tasks                  # 强制重跑生成+编译+单测
./gradlew :annotations:publishToMavenLocal :buildSrc:publishToMavenLocal   # 发布到本地仓库
./gradlew :realmod:modJar                            # 打 mod jar
```

技术栈：Kotlin 2.2.0 / Gradle 8.11.1 / `kotlin-compiler-embeddable`（PSI 解析）/ `kotlinpoet-jvm 1.17.0`（代码生成）/ JDK 17。

> 构建时会出现 KGP 关于 `kotlin-compiler-embeddable` 出现在 build classpath 的警告——这是生成器使用 PSI 的预期代价，不影响构建结果。

---

## 十、验证与测试

| 层级 | 位置 | 内容 | 状态 |
|---|---|---|---|
| stub 单测 | `tests/` | 位打包往返 / Packet write-read / LogicIO 注册 | ✅ 3/3 |
| headless 实机 | `realmod/` | KTA-STRUCT/SYNC/GROUP/LOGIC/REMOTE/FULLUNIT/BUILD/VUNIT/VSIMPLE 共 9 项自检 | ✅ |
| full 客户端 | `mindustry-mod-validator-full` | 贴图加载、shader 编译、无运行时错误 | ✅ |
| 单元测试记录 | `GenRuntimeTest.kt` | `coordBitPacking` / `packetWriteRead` / `logicStatementRegistered` | ✅ |

---

## 十一、已知限制

1. **实体管线独立，不合并进 `mindustry.gen.Unit`**。原版 EntityAnno 的 `@EntityDef({Unitc.class, ...})` 会把 mod 组件注入官方实体体系；kt-annotations 生成的是 mod 本地独立实体类。因此生成的单位实体**不能直接作为 `UnitType` 的实体类型**（引擎要求 `UnitType` 实体是 `mindustry.gen.Unit` 子类）。这是与 EntityAnno 最核心的差异（如 Aeronautics 迁移时发现的根本阻塞点）。
2. **无 `@Remove` 等价注解**：无法从合并实体中移除某组件的方法。
3. **已声明未接入的注解**：`@Replace`、`@Final`、`@SyncLocal`、`@NoSync`、`@NoSerialize`、`@InternalImpl`、`@MethodPriority`、`@CallSuper`、`@OverrideCallSuper`、`@StyleDefaults`、`@TypeIOHandler`。
4. **参数未完整实现**：`@EntityDef(pooled/genio/legacy/excludeGroups)`、`@GroupDef(collide/spatial/mapping/update)`、`@SyncField(value/clamped)`、`@Remote(priority)`、`@Load(lengths)` 已解析但未参与生成。
5. **类型解析依赖白名单**：Scanner 用 `knownFqn` 表把简单类型名解析为 FQN，白名单外的类型请写全限定名，或扩展 `Scanner.knownFqn`。
6. **含泛型参数的方法被跳过**（如 `getCollisions(consumer: Cons<QuadTree<...>>)`）。
7. **方法体合成为文本替换**：`self()/Vars/Mathf/min/hitSize` 等替换有正则边界保护，但复杂表达式仍建议构建后检查生成结果。
8. **`AssetsGenerator`（Tex/Sounds/Musics 存根）已实现但未接入 `GenerateTask`**。
9. **`@Load` 在 headless 无 atlas 环境不可用**（仅客户端有意义）。
10. 生成实体**不自动生成** `create()`/EntityMapping 注册表，需手动注册（见 6.4）。

---

## 十二、FAQ

**Q：插件解析不到 `io.eve.ktannot`？**
A：先执行 `./gradlew :annotations:publishToMavenLocal :buildSrc:publishToMavenLocal`，并在消费项目 `settings.gradle.kts` 的 `pluginManagement.repositories` 加 `mavenLocal()`；`plugins` 块带版本 `id("io.eve.ktannot") version "0.1.0"`。

**Q：生成代码没出现 / 目录为空？**
A：检查 ① `ktAnnotations.sourceDir` 是否指向真实源码目录（默认 `src/main/kotlin`）；② `build` 是否依赖 `generateKtAnnotations`；③ 生成目录是否已加入 `kotlin.srcDir`。

**Q：编译报 `EntityInterface` 未定义？**
A：`genPackage` 包下需自备 `EntityInterface` 注解声明（见 8.3）。

**Q：`@Remote` 方法没生成 packet？**
A：方法必须放在 `object` 中；`targets` 不能为 `none`；参数只能是原语/String/Player。

**Q：实体里字段重复/被覆盖？**
A：组件间重名字段会被去重（构建日志打印 `Duplicate field`）；跨组件共享字段请在声明处用 `@Import` 表达。

**Q：`kotlin-compiler-embeddable` 警告要紧吗？**
A：不要紧，生成器内部用 PSI 解析需要它（见第九章说明）。

**Q：能像 EntityAnno 一样把自定义组件塞进官方 `Unit` 实体吗？**
A：当前不能（见限制 1）。若需要该能力，需扩展 EntityGenerator 让生成的实体直接实现 `mindustry.gen.Unitc` 并接入官方组管线。

---

## 十三、许可证

MIT License — Copyright (c) 2026 ZXS (ZenthXSin)
