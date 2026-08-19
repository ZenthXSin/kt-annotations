# kt-annotations Phase 2 (P2) — 实体生命线

> 目标：让 kt-annotations 生成的实体能 `create()` → `add()` 进世界 → `update()` 被组驱动 → `remove()` 反注册 → `write/read` 序列化。
> 对标：`mindustry/annotations/entity/EntityProcess.java` v159.7 (c9686eb5)
> 验证：2026-08-18 headless 实机全部通过

## 完成状态

### 1. add()/remove() 组注入 ✅
- SimpleEntity: `add()` → `Groups.pos.addIndex(this)`, `remove()` → `Groups.pos.removeIndex(this, index__pos)`
- Unit: 同上
- 组索引字段 `protected transient int index__pos = -1`

### 2. classId() + EntityMapping ✅
- SimpleEntity.classId() = 1, Unit.classId() = 0
- EntityMapping: idMap[id] = Prov { Xxx.create() }, nameMap["name"] = Prov { Xxx.create() }
- register() 动态注册 + customIdMap

### 3. create() + pooled 生命周期 ✅ (非池化)
- 所有实体生成 `companion object { @JvmStatic fun create() }`
- pooled 暂未启用（需 `Pools.obtain` + `Poolable.reset`）

### 4. Groups 类生成 ✅
- vanilla: `Groups.pos` (EntityGroup<Entityc>, spatial=true, mapping=true)
- realmod: `Groups.PosGroup` (EntityGroup<mindustry.gen.Entityc>, spatial=true, collide=true)
- init/clear/update/resize/queueFree/updatePooling

### 5. 序列化 IO ✅
- write/read 按字段顺序生成，跳过 transient/@NoSerialize
- 支持 Int/Float/Boolean/Short/Byte/Double/String/Team/Vec2
- writeSync/readSync 已通过 @SyncField 生成

### 6. headless 验证 ✅
- KTA-P2-ADD: posGroupSize=1, firstHp=999.0 ✓
- KTA-P2-REMOVE: afterRemoveEmpty=0 ✓
- KTA-P2-CLASSID: cid=1, mapped!=null=true, nameMapped!=null=true ✓
- KTA-P2-CLASSID-CONSTRUCT: health=42.0 ✓
- KTA-P2-SER: health=999.0 ✓

## 修复记录

### 编译错误修复 (2026-08-18)
1. **EntityBase.kt stale** — 非 mindustry 模式产物残留，删除
2. **afterWrite override** — 真实 `mindustry.gen.Entityc` 无此方法，从 Kotlin EntityComp 移除 + 生成器改为条件性 `override`
3. **self()/as() 过滤** — 泛型返回类型 `T` 被 `returnType.length==1` 过滤，加入 `knownGenericMethods` 白名单 + 类型变量支持 + `T` 解析为 TypeVariableName
4. **write/read/classId override** — 非 EntityComp 实体（如 realmod TestUnit）误加 `override`，改为条件性控制
5. **afterWrite/afterRead 引用** — 非 EntityComp 实体写入 `/* no afterWrite */ / * no afterRead */`

## 剩余 (P3 候选)
- pooled 实体：`Pools.obtain` + `Poolable.reset()` + `queueFree` 组注入
- SyncField 插值：`interpolate()` / `snapSync()` / `snapInterpolation()`
- 组件自身 add/remove override 链式调用（HitboxComp.add 覆盖 EntityComp.add）