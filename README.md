# kt-annotations

> Kotlin 注解处理器，为 Mindustry mod 开发生成实体组件、网络包、结构体、逻辑语句和贴图加载代码。  
> 对标 Mindustry 官方 `mindustry.annotations` 的纯 Kotlin 移植，**已在 v159.7 headless + full 环境运行时验证通过**。

## 能力

| 注解 | 作用 | 验证 |
|------|------|------|
| `@Struct` | 值类型位打包（如 `PackedPos`） | ✅ |
| `@EntityDef` + `@Component` + `@SyncField` | 实体组件 + 网络同步 | ✅ |
| `@GroupDef` | 实体组索引（碰撞/空间索引） | ✅ |
| `@RegisterStatement` | 自定义逻辑语句 | ✅ |
| `@Load` | 方块贴图自动加载 | ✅ |
| `@Remote` | 远程调用网络包生成 | ✅ |

## 用法

### 1. 引入插件

```kotlin
// build.gradle.kts
plugins {
    id("io.eve.ktannot") version "0.1.0"
}
```

```kotlin
// settings.gradle.kts — 添加插件仓库
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
        maven { url = uri("https://jitpack.io") }
    }
}
```

### 2. 添加依赖

```kotlin
dependencies {
    implementation("com.github.ZenthXSin.kt-annotations:annotations:0.1.0")
}
```

### 3. 配置（可选）

```kotlin
ktAnnotations {
    mindustryMode = true  // 生成对接真实 Mindustry 引擎的代码
}
```

### 4. 编写注解

```kotlin
import io.eve.ktannot.*

@Struct
class PackedPos {
    var x: Short = 0
    var y: Short = 0
    @StructField(8)
    var layer: Byte = 0
    var alive: Boolean = false
}

@Component
abstract class PosComp {
    var x: Float = 0f
    var y: Float = 0f
}

@EntityDef([PosComp::class])
abstract class MyUnit

@Remote(targets = Loc.both, variants = Variant.all)
fun hello(player: Player, message: String) {
    println("$player says $message")
}
```

### 5. 构建

```bash
./gradlew build
```

生成代码位于 `build/generated/ktannot/main/kotlin/`。

## 项目结构

```
kt-annotations/
├── annotations/          # 注解定义（运行时引用）
│   └── src/main/kotlin/io/eve/ktannot/Annotations.kt
├── buildSrc/             # Gradle 插件（注解处理器）
│   └── src/main/kotlin/io/eve/ktannot/
│       ├── KtAnnotationsPlugin.kt   # 插件入口
│       └── gen/                     # 6 个代码生成器
├── tests/                # stub 单元测试 + 桩依赖
├── realmod/              # 真实 Mindustry mod 验证项目
└── README.md
```

## 验证

项目包含多层验证：

- **stub 单元测试**：`tests/` — 纯 JVM 验证生成代码逻辑
- **headless 服务端测试**：`realmod/` — 在 v159.7 headless server 中运行 6 项自检
- **full 客户端验证**：`mindustry-mod-validator-full` — 验证贴图加载、shader 编译、无运行时错误

## 许可证

MIT