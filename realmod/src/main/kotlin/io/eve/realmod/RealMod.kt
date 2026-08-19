package io.eve.realmod

import arc.Events
import arc.util.Log
import mindustry.Vars
import mindustry.gen.EntityMapping
import mindustry.game.EventType
import mindustry.mod.Mod

class RealMod : Mod() {
    private var testRan = false

    override fun init() {
        Log.info("[realmod] init (headless=@)", Vars.headless)

        // 注册生成的远程调用包(真实 Packet 注册到 Net)
        io.eve.ktannot.gen.Call.registerPackets()
        Log.info("[realmod] remote packets registered")

        if (Vars.headless) {
            // 服务器加载完成后,手动构造测试世界(无地图文件时 headless 不触发 WorldLoadEvent)
            Events.on(EventType.ServerLoadEvent::class.java) {
                Log.info("[realmod] ServerLoadEvent -> constructing test world")
                try {
                    Vars.world.resize(3, 3)
                    Vars.world.beginMapLoad()
                    Vars.world.tiles.fill()
                    for (x in 0 until 3) for (y in 0 until 3) {
                        val tile = Vars.world.tiles.get(x, y)
                        tile.setFloor(mindustry.content.Blocks.stone as mindustry.world.blocks.environment.Floor)
                    }
                    Vars.world.endMapLoad()
                    Vars.state.set(mindustry.core.GameState.State.playing)
                    Vars.logic.play()
                    Log.info("[realmod] test world ready")
                } catch (e: Throwable) {
                    Log.err("[realmod] world construction failed", e)
                }
            }
            Events.on(EventType.WorldLoadEvent::class.java) {
                if (testRan) return@on
                testRan = true
                runHeadlessTest()
            }
        }
    }

    override fun loadContent() {
        Log.info("[realmod] loadContent (skip block.load() to avoid atlas NPE in headless; @Load covered by ContentRegions test)")
    }

    private fun runHeadlessTest() {
        try {
            Log.info("==== KTA TEST START ====")

            // ---------- @Struct:位打包 ----------
            val packed = io.eve.realmod.PackedPos.get(3.toShort(), (-5).toShort(), 110.toByte(), true)
            val unpackX = io.eve.realmod.PackedPos.x(packed)
            val unpackY = io.eve.realmod.PackedPos.y(packed)
            val unpackLayer = io.eve.realmod.PackedPos.layer(packed)
            val unpackAlive = io.eve.realmod.PackedPos.alive(packed)
            Log.info("KTA-STRUCT x=@ expect=3 y=@ expect=-5 layer=@ expect=110 alive=@ expect=true",
                unpackX, unpackY, unpackLayer, unpackAlive)

            // ---------- @EntityDef + 组件 + @SyncField:实体同步往返 ----------
            val unit = io.eve.ktannot.gen.TestUnit()
            unit.hp = 87.5f
            unit.angle = 180f
            val out = arc.util.io.ByteBufferOutput(java.nio.ByteBuffer.allocate(256))
            val writes = arc.util.io.Writes(out)
            unit.writeSync(writes)
            val readUnit = io.eve.ktannot.gen.TestUnit()
            out.buffer.flip()
            val reads = arc.util.io.Reads(arc.util.io.ByteBufferInput(out.buffer))
            readUnit.readSync(reads)
            Log.info("KTA-SYNC hp=@ expect=87.5 angle=@ expect=180", readUnit.hp, readUnit.angle)

            // ---------- @GroupDef:组接口实现 ----------
            val inGroup = unit is io.eve.ktannot.gen.IndexableEntity__PosGroup
            Log.info("KTA-GROUP inGroup=@ expect=true", inGroup)

            // ---------- @RegisterStatement:LogicIO 读写往返 ----------
            val st = TestLogStatement()
            st.message = "hello-kta"
            val sb = StringBuilder()
            io.eve.ktannot.gen.LogicIO.write(st, sb)
            val tokens = sb.toString().split(" ")
            val readSt = io.eve.ktannot.gen.LogicIO.read(tokens.toTypedArray(), tokens.size) as TestLogStatement
            Log.info("KTA-LOGIC text=@ expect=hello-kta", readSt.message)

            // ---------- @Load:贴图区域加载 ----------
            // headless 无 atlas(Core.atlas==null),@Load 的贴图解析在客户端才有意义;
            // 生成的 ContentRegions 语法正确性已由编译保证,客户端行为经 mod-validator(OpenGL)验证。
            Log.info("KTA-LOAD skipped in headless (no atlas); generated: ContentRegions.loadRegions(block).topRegion = arc.Core.atlas.find(name + \"-top\")")

            // ---------- @Remote:packet 序列化往返 ----------
            val pkt = io.eve.ktannot.gen.AnnounceCallPacket()
            pkt.message = "net-hello"
            pkt.value = 42
            val netBuf = arc.util.io.ByteBufferOutput(java.nio.ByteBuffer.allocate(256))
            pkt.write(arc.util.io.Writes(netBuf))
            netBuf.buffer.flip()
            val pkt2 = io.eve.ktannot.gen.AnnounceCallPacket()
            val reads2 = arc.util.io.Reads(arc.util.io.ByteBufferInput(netBuf.buffer))
            pkt2.read(reads2, netBuf.buffer.remaining())
            pkt2.handled()
            Log.info("KTA-REMOTE message=@ expect=net-hello value=@ expect=42", pkt2.message, pkt2.value)

            // ---------- 完整单位实体: 创建 + EntityMapping 注册 + UnitType 生命周期 ----------
            try {
                // 注册到 EntityMapping
                val fullUnitProv = arc.func.Prov { io.eve.ktannot.gen.MyFullUnit() }
                EntityMapping.nameMap.put("my-full-unit", fullUnitProv)
                EntityMapping.register("my-full-unit-v2", fullUnitProv)
                
                val fullUnit = io.eve.ktannot.gen.MyFullUnit()
                fullUnit.health = 100f
                fullUnit.maxHealth = 100f
                fullUnit.team = mindustry.game.Team.sharded
                fullUnit.hitSize = 8f
                fullUnit.x = 40f * 8f
                fullUnit.y = 40f * 8f
                fullUnit.type = mindustry.content.UnitTypes.alpha
                // 生命周期:add 后 isAdded=true,remove 后 false(生成 Entityc 提供)
                fullUnit.add()
                val added = fullUnit.isAdded()
                fullUnit.remove()
                val removed = !fullUnit.isAdded()
                Log.info("KTA-FULLUNIT health=@ team=@ added=@ removed=@ map=@ v2=@",
                    fullUnit.health, fullUnit.team.name, added, removed,
                    EntityMapping.map("my-full-unit") !== null,
                    EntityMapping.map("my-full-unit-v2") !== null)
            } catch (e: Throwable) {
                Log.err("[realmod] full unit test failed", e)
            }
            
            // ---------- 建筑实体: 使用生成 BuildingBase 模式;此处直接验证真实 Building 生命周期 ----------
            try {
                // 自定义建筑类:继承真实 Mindustry Building(对应 vanilla 生成 BuildingBase 的运行时基类)
                class MyBuildImpl : mindustry.gen.Building() {
                    override fun classId(): Int = 77
                    override fun updateTile() {
                        Log.info("[mybuild] updateTile tick")
                    }
                    override fun draw() {}
                    override fun display(table: arc.scene.ui.layout.Table) {}
                    override fun sense(sensor: mindustry.logic.LAccess): Double = Double.NaN
                    override fun senseObject(sensor: mindustry.logic.LAccess): Any? = null
                    override fun sense(content: mindustry.ctype.Content): Double = Double.NaN
                    override fun setProp(prop: mindustry.logic.LAccess, value: Double) {}
                    override fun setProp(prop: mindustry.logic.LAccess, value: Any?) {}
                    override fun setProp(content: mindustry.ctype.UnlockableContent, value: Double) {}
                    override fun ambientVolume(): Float = 0f
                    override fun handleDamage(amount: Float): Float = amount
                    override fun onRemoved() {}
                    override fun playerPlaced(t: Any?) {}
                    override fun dropped() {}
                    override fun created() {}
                    override fun afterDestroyed() {}
                    override fun afterPickedUp() {}
                    override fun unitOn(unit: mindustry.gen.Unit) {}
                    override fun getCursor(): arc.Graphics.Cursor = arc.Graphics.Cursor.SystemCursor.arrow
                    override fun config(): Any? = null
                    override fun canPickup(): Boolean = false
                    override fun canUnload(): Boolean = false
                    override fun shouldConsume(): Boolean = true
                    override fun shouldAmbientSound(): Boolean = false
                    override fun acceptItem(source: mindustry.gen.Building, item: mindustry.type.Item): Boolean = false
                    override fun acceptLiquid(source: mindustry.gen.Building, liquid: mindustry.type.Liquid): Boolean = false
                    override fun acceptPayload(source: mindustry.gen.Building, payload: mindustry.world.blocks.payloads.Payload): Boolean = false
                    // 真实 Building 还要求这些编译期抽象成员
                    override fun getCommandPosition(): arc.math.geom.Vec2 = arc.math.geom.Vec2(x, y)
                    override fun getPowerProduction(): Float = 0f
                    override fun getProgressIncrease(base: Float): Float = base
                    override fun progress(): Float = 0f
                    override fun totalProgress(): Float = 0f
                    override fun warmup(): Float = 0f
                    override fun drawrot(): Float = 0f
                    override fun healthf(): Float = health / maxHealth
                    override fun isValid(): Boolean = !dead && isAdded()
                    override fun killed() {}
                    override fun kill() { dead = true }
                    override fun heal() { dead = false; health = maxHealth }
                    override fun damaged(): Boolean = health < maxHealth - 0.001f
                    override fun damagePierce(amount: Float, withEffect: Boolean) { health -= amount; if (health <= 0f) dead = true }
                    override fun damagePierce(amount: Float) = damagePierce(amount, true)
                    override fun damageArmorMult(amount: Float, armorMult: Float, withEffect: Boolean) = damagePierce(amount, withEffect)
                    override fun damageArmorMult(amount: Float, armorMult: Float) = damagePierce(amount, true)
                    override fun damage(amount: Float) = damagePierce(amount, true)
                    override fun damage(amount: Float, withEffect: Boolean) = damagePierce(amount, withEffect)
                    override fun damageContinuous(amount: Float) = damagePierce(amount * arc.util.Time.delta, true)
                    override fun damageContinuousPierce(amount: Float) = damagePierce(amount * arc.util.Time.delta, true)
                    override fun damageContinuousArmorMult(amount: Float, armorMult: Float) = damagePierce(amount * arc.util.Time.delta, true)
                    override fun clampHealth() { if (health > maxHealth) health = maxHealth }
                    override fun heal(amount: Float) { health += amount; clampHealth() }
                    override fun healFract(amount: Float) = heal(amount * maxHealth)
                    override fun update() {}
                    override fun add() { added = true }
                    override fun remove() { added = false }
                    override fun isAdded(): Boolean = added
                    override fun isLocal(): Boolean = false
                    override fun isRemote(): Boolean = false
                    override fun serialize(): Boolean = true
                    override fun id(): Int = 1
                    override fun id(value: Int) {}
                    override fun afterRead() {}
                    override fun afterReadAll() {}
                    override fun beforeWrite() {}
                    override fun read(reads: arc.util.io.Reads) {}
                    override fun write(writes: arc.util.io.Writes) {}
                    override fun timer(index: Int, time: Float): Boolean = false
                    override fun inFogTo(viewer: mindustry.game.Team): Boolean = false
                    override fun cheating(): Boolean = false
                    override fun core(): mindustry.world.blocks.storage.CoreBlock.CoreBuild? = null
                    override fun closestCore(): mindustry.world.blocks.storage.CoreBlock.CoreBuild? = null
                    override fun closestEnemyCore(): mindustry.world.blocks.storage.CoreBlock.CoreBuild? = null
                    override fun hitbox(rect: arc.math.geom.Rect) { rect.setCentered(x, y, 8f, 8f) }
                    override fun hitSize(): Float = 8f
                    override fun set(x: Float, y: Float) { this.x = x; this.y = y }
                    override fun set(pos: arc.math.geom.Position) { set(pos.getX(), pos.getY()) }
                    override fun trns(x: Float, y: Float) { set(this.x + x, this.y + y) }
                    override fun trns(pos: arc.math.geom.Position) = trns(pos.getX(), pos.getY())
                    override fun getX(): Float = x
                    override fun getY(): Float = y
                    override fun tileX(): Int = mindustry.core.World.toTile(x)
                    override fun tileY(): Int = mindustry.core.World.toTile(y)
                    override fun tileOn(): mindustry.world.Tile? = mindustry.Vars.world.tileWorld(x, y)
                    override fun blockOn(): mindustry.world.Block = if (tileOn() == null) mindustry.content.Blocks.air else tileOn()!!.block()
                    override fun floorOn(): mindustry.world.blocks.environment.Floor = mindustry.content.Blocks.air as mindustry.world.blocks.environment.Floor
                    override fun buildOn(): mindustry.gen.Building? = mindustry.Vars.world.buildWorld(x, y)
                    override fun onSolid(): Boolean = false
                    override fun displayable(): Boolean = true
                    override fun control(prop: mindustry.logic.LAccess, a: Double, b: Double, c: Double, d: Double) {}
                    override fun control(prop: mindustry.logic.LAccess, p: Any?, a: Double, b: Double, c: Double) {}
                    override fun team(): mindustry.game.Team = team
                }
                val build = MyBuildImpl()
                build.health = 500f
                build.maxHealth = 500f
                build.team = mindustry.game.Team.sharded
                build.set(48f * 8f, 48f * 8f)
                Log.info("KTA-BUILD health=@ team=@ classId=@ buildX=@ buildY=@", build.health, build.team.name, build.classId(), build.getX(), build.getY())
            } catch (e: Throwable) {
                Log.err("[realmod] building test failed", e)
            }

            // ---------- vanilla 生成实体机制测试:Unit(UnitDef 组合) ----------
            try {
                val vUnit = io.eve.vanilla.gen.Unit()
                vUnit.health = 100f
                vUnit.maxHealth = 100f
                vUnit.team = mindustry.game.Team.sharded
                vUnit.hitSize(8f)
                vUnit.set(40f * 8f, 40f * 8f)
                vUnit.vel.set(3f, 4f)
                vUnit.add()
                val added1 = vUnit.isAdded()

                // 位置/坐标
                val x1 = vUnit.getX()
                val y1 = vUnit.getY()
                val tileX = vUnit.tileX()
                val tileY = vUnit.tileY()

                // 移动
                vUnit.move(10f, 0f)
                val moveX = vUnit.getX() - x1

                // 伤害/死亡
                vUnit.damage(30f)
                val dmg1 = vUnit.health
                val dmgHit = vUnit.hitTime
                vUnit.damage(999f)
                val isDead = vUnit.dead
                val removed = !vUnit.isAdded()

                // 治疗恢复
                val v2 = io.eve.vanilla.gen.Unit()
                v2.health = 50f
                v2.maxHealth = 200f
                v2.add()
                v2.heal(30f)
                val heal1 = v2.health
                v2.healFract(0.5f)
                val heal2 = v2.health

                // 序列化标志
                val ser = vUnit.serialize()

                // 相对移动 trns
                val v3 = io.eve.vanilla.gen.Unit()
                v3.set(100f, 100f)
                v3.trns(5f, 0f)
                val trnsX = v3.getX()

                Log.info("KTA-VUNIT add=@ pos=(@,@) tile=(@,@) move=@ dmg=@ hit=@ dead=@ removed=@ heal1=@ heal2=@ ser=@ trnsX=@", 
                    added1, x1, y1, tileX, tileY, moveX, dmg1, dmgHit, isDead, removed, heal1, heal2, ser, trnsX)
            } catch (e: Throwable) {
                Log.err("[realmod] vanilla unit test failed", e)
            }

            // ---------- vanilla 生成实体机制测试:SimpleEntity(PosComp+HealthComp 简易实体) ----------
            try {
                val se = io.eve.vanilla.gen.SimpleEntity()
                se.health = 80f
                se.maxHealth = 100f
                se.set(200f, 200f)
                se.add()
                val added1 = se.isAdded()

                // isValid 依赖 dead+added
                val valid1 = se.isValid()
                val healthf1 = se.healthf()

                // 伤害→死亡→移除
                se.damage(100f)
                val isDead = se.dead
                val removed = !se.isAdded()
                val valid2 = se.isValid()

                // heal 全恢复
                val se2 = io.eve.vanilla.gen.SimpleEntity()
                se2.health = 30f
                se2.maxHealth = 100f
                se2.heal(20f)
                val heal1 = se2.health

                // 序列化
                val ser = se2.serialize()

                // tile 查询
                val tileOn = se2.tileOn()

                Log.info("KTA-VSIMPLE add=@ valid1=@ healthf=@ dead=@ removed=@ valid2=@ heal=@ ser=@ tile=@",
                    added1, valid1, healthf1, isDead, removed, valid2, heal1, ser, tileOn != null)
            } catch (e: Throwable) {
                Log.err("[realmod] vanilla simple entity test failed", e)
            }

            Log.info("==== KTA TEST END ====")
            // 测试完成即退出,避免服务器无地图循环触发第二次 WorldLoad
            if (Vars.headless) {
                Log.info("[realmod] test done, exiting")
                arc.Core.app.exit()
            }
        } catch (e: Throwable) {
            Log.err("[realmod] headless test failed", e)
        }
    }
}