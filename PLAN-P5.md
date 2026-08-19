# kt-annotations Phase 5 (P5) — 方法体合并与元数据

> 对标：EntityProcess 方法体合并（writeBlock/blockName/break） + 元数据传播（Nullable/Deprecated/Suppress） + Scanner 静态解析
> 验证：`./gradlew build --no-daemon` + `:tests:test`

## 待补

### A. Scanner 静态成员解析

| # | 条目 | 原版行为 | 当前 | 优先级 |
|---|------|---------|------|--------|
| A1 | **字段 isStatic** | 识别 `static` 修饰符 | Kotlin 无 static 修饰符;object/companion 表达 | ✅ |
| A2 | **方法 isStatic** | 识别 `static` 修饰符 | Kotlin 无 static 修饰符;object/companion 表达 | ✅ |
| A3 | **方法 isPublic** | 识别 `public` 修饰符 | 已解析 | ✅ |
| A4 | **方法 isPrivate** | 识别 `private` 修饰符 | 已解析 | ✅ |

### B. 方法体合并增强

| # | 条目 | 原版行为 | 当前 | 优先级 |
|---|------|---------|------|--------|
| B1 | **writeBlock labeled break** | `blockName: { ... break blockName; }` → Kotlin `run {} return@run` | ✅ | **高** |
| B2 | **yield 替换** | 移除 enhanced switch 的 `yield` | ✅ | 中 |
| B3 | **`/*missing*/` → `var`** | 替换未知类型为 `var` | ✅ | 中 |
| B4 | **blockName 来源** | 组件名小写去 comp 后缀 | ✅ | 中 |

### C. 元数据传播

| # | 条目 | 原版行为 | 当前 | 优先级 |
|---|------|---------|------|--------|
| C1 | **@Nullable 传播** | 可空类型 `T?` 传播 | ✅ | 中 |
| C2 | **@Deprecated 传播** | `kotlin.Deprecated` 注解传播 | ✅ | 中 |
| C3 | **skipDeprecated** | 生成类加 `@Suppress("DEPRECATION")` | ✅ | 中 |

### D. 测试补全

| # | 条目 | 行为 | 优先级 |
|---|------|------|--------|
| D1 | **writeBlock 测试** | 验证多组件同方法体合并 | 中 |
| D2 | **静态方法/字段扫描** | 验证 Scanner 正确解析 | 中 |

## 实施顺序

1. A1/A2: Scanner 解析 isStatic
2. B1: writeBlock → Kotlin run {} return@run
3. B2/B3: yield/missing 替换
4. C1/C2/C3: 元数据传播
5. 构建验证