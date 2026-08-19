# kt-annotations Phase 6 (P6) — 补齐剩余 8 个原版组件

> 对标：剩余 8 个 v159.7 原版组件翻译
> 验证：`./gradlew build --no-daemon` + `:tests:test`

## 剩余组件

| # | 组件 | 原版行数 | 复杂度 | 状态 |
|---|------|---------|--------|------|
| P6-1 | **WorldLabelComp** | 137 | 低 | ✅ |
| P6-2 | **PuddleComp** | 154 | 低 | ✅ |
| P6-3 | **SegmentComp** | 158 | 中 | ✅ |
| P6-4 | **BulletComp** | 402 | 高 | ✅ |
| P6-5 | **PlayerComp** | 495 | 高 | ✅ |
| P6-6 | **UnitComp** | 987 | 极高 | ✅ |
| P6-7 | **BuildingComp** | 2315 | 极高 | ✅ |
| P6-8 | **PosTeamDef** | 15 | 低 | ✅ |

## 实施顺序

按依赖 + 复杂度排：

1. 小件（WorldLabelComp / PuddleComp / SegmentComp / PosTeamDef）— 独立无依赖，快
2. BulletComp — 依赖 Timedc/Damagec/Hitboxc/Teamc/Posc/Drawc/Shielderc/Ownerc/Bulletc/Timerc/Senseable/Settable（均已翻译）
3. PlayerComp — 依赖 UnitController/Entityc/Syncc/Timerc/Drawc（均已翻译）
4. UnitComp — 大件，依赖多（Healthc/Physicsc/Hitboxc/Statusc/Teamc/Itemsc/Rotc/Unitc/Weaponsc/Drawc/Syncc/Shieldc/Displayable/Ranged/Minerc/Builderc/Senseable/Settable）
5. BuildingComp — 最大件，2315 行，依赖 Posc/Teamc/Healthc/Buildingc/Timerc + 多个接口

## 验证

每补一个跑一次 `./gradlew build --no-daemon`，全部完成后跑 `:tests:test`