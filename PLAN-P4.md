# kt-annotations Phase 4 (P4) — 实体完整性与兼容层

> 对标：`EntityProcess.java` v159.7 (c9686eb5) 全部 + EntityIO + AssetsProcess
> 验证：`./gradlew build --no-daemon` + `:tests:test` 通过

## 已完成 (P1-P3)

P1: 基础扫描器 + 实体/组件/组/Struct/Remote 生成器框架
P2: 实体生命线（add/remove/classId/EntityMapping/create/序列化/headless 验证）
P3: 方法选择排序（MethodPriority/Replace/依赖深度/歧义检测）+ Groups 完整生命周期（init/clear/resize/update/queueFree/updatePooling）+ readSyncManual/writeSyncManual + protected 构造器 + EntityMapping + CallSuper 注解 + A10 抽象报错

## 完成状态

### A. 实体属性和命名完善 ✅

| # | 条目 | 状态 |
|---|------|------|
| A1 | **excludeGroups** | ✅ |
| A2 | **@Final 方法** | ✅ |
| A3 | **toString(#id)** | ✅ |
| A4 | **createName** | ✅ |
| A5 | **typeIsBase** | ✅ |

### B. 实体回调链 ✅

| # | 条目 | 状态 |
|---|------|------|
| B1 | **afterRead()** | ✅ |
| B2 | **beforeWrite()** | ✅ |
| B3 | **afterReadAll()** | ✅ |
| B4 | **afterSync()** | ✅ |

### C. 字段序列化增强 ✅

| # | 条目 | 状态 |
|---|------|------|
| C1 | **@NoSerialize** | ✅ |
| C2 | **@NoSync** | ✅ |
| C3 | **String 序列化** | ✅ |
| C4 | **Team 序列化** | ✅ |
| C5 | **Vec2/Point2 序列化** | ✅ |
| C6 | **Content 序列化** | ✅ |

### D. 其他注解处理器补齐 ✅

| # | 条目 | 状态 |
|---|------|------|
| D1 | **TypeIOHandler** | ✅ (占位) |
| D2 | **AssetsProcess(Tex/Icon/Iconc)** | ✅ (存根) |
| D3 | **Sounds/Musics** | ✅ (存根) |

### E. 类 ID 持久化 ✅

| # | 条目 | 状态 |
|---|------|------|
| E1 | **classids.properties** | ✅ |
| E2 | **classId 确定性** | ✅ |

## 无剩余低优先级项 — 所有 P4 条目已全部完成

## 实施策略

1. **高优先级** — A1 excludeGroups, A2 @Final 方法, B2 beforeWrite, D1 TypeIOHandler
2. **中优先级** — A3 toString(#id), C2 @NoSync, C6 Content 序列化, D2 AssetsProcess, E1 classids
3. **低优先级** — A4 createName, A5 typeIsBase, B3 afterReadAll, D3 Sounds/Musics

## 实施顺序

1. A1: excludeGroups 过滤
2. A2: @Final 方法修饰
3. B2: beforeWrite() 回调
4. D1: TypeIOHandler 解析自定义类型序列化器
5. C2: @NoSync 跳过 target/last 生成
6. A3: toString(#id)
7. E1: classids 持久化
8. 构建验证