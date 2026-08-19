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

// ============ 实体基组件(提供 add/remove/isAdded 等生命周期,使生成实体有 Entityc 接口) ============
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

    @io.eve.ktannot.InternalImpl
    abstract fun classId(): Int

    @io.eve.ktannot.InternalImpl
    abstract fun serialize(): Boolean

    @io.eve.ktannot.MethodPriority(1f)
    open fun read(reads: arc.util.io.Reads) { afterRead() }
    open fun write(writes: arc.util.io.Writes) {}
    open fun beforeWrite() {}
    open fun afterRead() {}
    open fun afterReadAll() {}
}

// ============ 完整单位组件(自包含,不依赖@Import,entity生成器可直接生成所有字段) ============
@Component
abstract class MyUnitComp : io.eve.ktannot.gen.Entityc {
    var x = 0f
    var y = 0f
    var health = 0f
    var maxHealth = 1f
    var dead = false
    var team = mindustry.game.Team.derelict
    var rotation = 0f
    var vel = arc.math.geom.Vec2()
    var hitSize = 0f
    var type: mindustry.type.UnitType = mindustry.content.UnitTypes.alpha
    var controller: mindustry.entities.units.UnitController? = null
    var isPlayer = false
    var elevation = 0f
    var spawner: mindustry.gen.Entityc? = null
    var mineTile: mindustry.world.Tile? = null
    var mounted = false
    var flag = 0.0
    var abilities: arc.struct.Seq<*> = arc.struct.Seq<Any?>()
    var ammo = 0f
    var ammoCapacity = 0f

    fun isFlying(): Boolean = elevation >= 0.09f
    fun isGrounded(): Boolean = elevation < 0.001f
}

// ============ 完整单位实体 ============
@EntityDef([MyUnitComp::class], serialize = true, isFinal = true)
abstract class MyFullUnitDef

// ============ 自定义方块 ============
class MyTestBlock : mindustry.world.Block("my-test-block") {
    init {
        solid = true
        size = 2
        update = true
        hasItems = true
        hasPower = true
        configurable = true
        consumesPower = true
        outputsPower = false
    }
}