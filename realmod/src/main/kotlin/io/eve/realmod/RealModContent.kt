package io.eve.realmod

import io.eve.ktannot.Component
import io.eve.ktannot.EntityDef
import io.eve.ktannot.GroupDef
import io.eve.ktannot.Struct
import io.eve.ktannot.StructField
import io.eve.ktannot.SyncField
import io.eve.ktannot.Load
import io.eve.ktannot.RegisterStatement
import io.eve.ktannot.Remote
import io.eve.ktannot.Loc
import io.eve.ktannot.Variant
import mindustry.gen.Player
import mindustry.logic.LStatement
import arc.graphics.g2d.TextureRegion
import arc.util.Log

// ============ @Component:位置组件(所有实体共享) ============
@Component
abstract class PosComp {
    var x: Float = 0f
    var y: Float = 0f
}

// ============ @Component + @SyncField:带网络同步的组件 ============
@Component(base = true)
abstract class SyncComp {
    @SyncField(true)
    var hp: Float = 100f

    @SyncField(false, clamped = true)
    var angle: Float = 0f
}

// ============ @EntityDef:自定义意志实体(由组件组合) ============
@EntityDef([PosComp::class, SyncComp::class], serialize = true, isFinal = true)
abstract class TestUnitDef

// ============ @GroupDef:所有位置实体的组索引 ============
@GroupDef(value = [PosComp::class], collide = true, spatial = true)
abstract class gPosGroup

// ============ @Struct:打包坐标值类型 ============
@Struct
class PackedPosStruct {
    var x: Short = 0
    var y: Short = 0
    @StructField(8)
    var layer: Byte = 0
    var alive: Boolean = false
}

// ============ @RegisterStatement:自定义逻辑语句 ============
@RegisterStatement("testlog")
class TestLogStatement : LStatement() {
    var message: String = ""

    override fun build(table: arc.scene.ui.layout.Table) {
        table.add("testlog").padRight(8f)
        field(table, message) { message = it }.pad(4f).left()
    }

    override fun build(builder: mindustry.logic.LAssembler): mindustry.logic.LExecutor.LInstruction? = null
}

// ============ @Remote:远程调用(原语参数,真实可序列化) ============
object NetCalls {
    @Remote(targets = Loc.both, variants = Variant.all, called = Loc.both)
    fun announce(player: Player, message: String, value: Int) {
        Log.info("[remote-announce] from=@ msg=@ value=@", player?.name ?: "?", message, value)
    }

    @Remote(targets = Loc.server, variants = Variant.one)
    fun teleport(player: Player, x: Float, y: Float) {
        Log.info("[remote-teleport] player=@ to=(@,@)", player?.name ?: "?", x, y)
    }
}

// ============ @Load:方块贴图自动加载 ============
class KtTestBlock : mindustry.world.Block("kt-test-block") {
    @Load("@-top")
    var topRegion: TextureRegion = TextureRegion()

    @Load(value = "@-frames", length = 4)
    var frames = arrayOfNulls<TextureRegion>(4)

    init {
        solid = true
        size = 2
        update = true
        configurable = false
    }
}

// ============ 运行期访问生成代码的桥 ============
object GenAccess {
    @JvmStatic
    fun loadRegionsFor(content: mindustry.ctype.MappableContent) {
        io.eve.ktannot.gen.ContentRegions.loadRegions(content)
    }
}