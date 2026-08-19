# kt-annotations Phase 3 (P3) — 计划

> 对标：`EntityProcess.java` v159.7 (c9686eb5) 第 440-880 行
> 验证：全模块 BUILD SUCCESSFUL + tests:test 通过

## 已实现 (本轮已补)

| # | 条目 | 状态 |
|---|------|------|
| 1 | add()/remove() 组注入 (`if(added) return` + group addIndex/removeIndex) | ✅ |
| 2 | Groups 类生成 (object Groups) | ✅ |
| 3 | Seq\<\*>/Array\<\*>/EntityGroup 泛型 (TypeUtils.java) | ✅ |
| 4 | writeBlock 多组件方法体合并 (// from ${impl.name}) | ✅ |
| 5 | Time.delta/self()/Vars/Mathf/Angles/min/hitSize 限名替换 | ✅ |
| 6 | self()/as() 用 TypeVariableName 消除 raw T | ✅ |
| 7 | classId() 递增计数器 | ✅ |
| 8 | companion object create() (pooled + non-pooled) | ✅ |
| 9 | Entityc 接口继承 mindustry.gen.Entityc | ✅ |
| 10 | id()/id(int) 显式方法 | ✅ |
| 11 | index__ 字段 @JvmField 标记 | ✅ |
| 12 | readSync/writeSync 带 lastUpdated/updateSpacing 追踪 | ✅ |
| 13 | interpolate() lerp 插值 | ✅ |
| 14 | snapSync()/snapInterpolation() 快照 | ✅ |
| 15 | pooled reset() + Poolable 接口 | ✅ |
| 16 | queueFree 注入 remove 尾 | ✅ |

## 待补 (P3 剩余)

### A. 方法选择与排序 (EntityProcess.java:440-481)

| # | 条目 | 状态 |
|---|------|------|
| A1 | **MethodPriority 排序** | ✅ |
| A2 | **@Replace 优先** | ✅ |
| A3 | **依赖深度排序** | ✅ |
| A4 | **歧义检测** | ✅ |
| A5 | **@InternalImpl 跳过** | ✅ |
| A10 | **抽象方法无实现报错** | ✅ |
| A6 | **@CallSuper 注解** | 低 |
| A7 | **@Nullable 注解** | 低 |
| A8 | **static 方法** | 低 |
| A9 | **类型变量 + 异常** | 低 |

### B. 实体生成细节 (EntityProcess.java:484-650)

| # | 条目 | 状态 |
|---|------|------|
| B1 | **read/write I/O (EntityIO)** | ✅ |
| B2 | **readSyncManual/writeSyncManual** | ✅ |
| B3 | **protected 构造器** | ✅ |
| B4 | **index__ 初始值 -1** | ✅ |
| B5 | **reset() 用组件初始值** | 低 |

### C. Groups 完整生命周期 (EntityProcess.java:674-736)

| # | 条目 | 状态 |
|---|------|------|
| C1 | **Groups.init()** | ✅ |
| C2 | **Groups.isClearing** | ✅ |
| C3 | **Groups.clear()** | ✅ |
| C4 | **Groups.freeQueue + queueFree** | ✅ |
| C5 | **Groups.updatePooling()** | ✅ |
| C6 | **Groups.resize()** | ✅ |
| C7 | **Groups.update()** | ✅ |

### D. EntityMapping 类 (EntityProcess.java:754-810)

| # | 条目 | 状态 |
|---|------|------|
| D1 | **EntityMapping** | ✅ |

### E. 第三轮 (EntityProcess.java:814-880)

| # | 条目 | 状态 |
|---|------|------|
| E1 | **getter/setter 自动实现** | 无需改 |
| E2 | **baseClass 的 getter/setter 提升** | 无需改 |

## 剩余低优先级

| # | 条目 | 说明 |
|---|------|------|
| A6 | @CallSuper 注解 | 方法层加 CallSuper 注解 |
| A7 | @Nullable 注解 | 返回类型 Nullable 标注 |
| A8 | static 方法 | object 内已是静态 |
| A9 | 类型变量 + 异常 | 泛型/异常声明 |
| B5 | reset() 用组件初始值 | 字段初始值回溯 |
| 全部 | 低优先级 6 项 | 非关键，可后续补

## 实施策略

1. **高优先级优先** — A1+A2 方法选择排序直接影响生成正确性；B1 read/write I/O 影响实体序列化；C4+C5 Groups queueFree+updatePooling 影响 pooled 实体生命周期；D1 EntityMapping 影响 id 分发
2. **中优先级** — A3-A5 完善方法匹配；B2 readSyncManual 数据完整性；C1+C7 Groups 完整生命周期
3. **低优先级** — 注释/静态/构造器等细节完善