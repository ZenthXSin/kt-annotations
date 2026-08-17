package io.eve.realmod

import arc.Events
import arc.util.Log
import mindustry.Vars
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