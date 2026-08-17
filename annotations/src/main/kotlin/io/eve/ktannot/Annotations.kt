package io.eve.ktannot

import kotlin.reflect.KClass

// Kotlin 注解定义，对标 mindustry.annotations.Annotations

/** 标记方法为重写其他方法 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER)
@Retention(AnnotationRetention.SOURCE)
annotation class Replace

/** 标记方法在实现类中应为 final */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class Final

/** 同步字段：值为 true 时线性插值，false 时按角度插值 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class SyncField(val value: Boolean, val clamped: Boolean = false)

/** 同步本地玩家的字段不会从服务器读取 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class SyncLocal

/** 不应同步到客户端的字段 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class NoSync

/** 同步但不序列化 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class NoSerialize

/** 字段从其他组件导入 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class Import

/** 只读 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class ReadOnly

/** 组件: base 表示生成基类, genInterface 表示生成接口 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class Component(val base: Boolean = false, val genInterface: Boolean = true)

/** 方法由注解处理器实现 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class InternalImpl

/** 方法优先级 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class MethodPriority(val value: Float)

/** 存在于所有实体上的组件 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class BaseComponent

/** 创建只检查带有全部组件的实体组 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class GroupDef(
    val value: Array<KClass<*>>,
    val exclude: Array<KClass<*>> = [],
    val collide: Boolean = false,
    val spatial: Boolean = false,
    val mapping: Boolean = false,
    val update: Boolean = false,
)

/** 实体定义 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class EntityDef(
    val value: Array<KClass<*>>,
    val isFinal: Boolean = true,
    val pooled: Boolean = false,
    val serialize: Boolean = true,
    val genio: Boolean = true,
    val legacy: Boolean = false,
    val excludeGroups: Array<String> = [],
)

/** 实体内部接口 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class EntityInterface

// ---- misc ----

/** 自动加载方块区域 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class Load(
    val value: String,
    val length: Int = 1,
    val lengths: IntArray = [],
    val fallback: String = "error",
)

/** 自动注册逻辑语句 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class RegisterStatement(val value: String)

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class StyleDefaults

/** 方法应总是调用其 super 版本 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class CallSuper

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class OverrideCallSuper

// ---- struct ----

/** 标记类为特殊值类型 struct。类名必须以 Struct 结尾。 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class Struct

/** 标记 struct 的字段 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class StructField(val value: Int)

// ---- remote ----

enum class PacketPriority { low, normal, high }

enum class Loc(val isServer: Boolean, val isClient: Boolean) {
    server(true, false),
    client(false, true),
    both(true, true),
    none(false, false),
}

enum class Variant(val isOne: Boolean, val isAll: Boolean) {
    one(true, false),
    all(false, true),
    both(true, true),
}

/** 远程可调用方法 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class Remote(
    val targets: Loc = Loc.server,
    val variants: Variant = Variant.all,
    val called: Loc = Loc.none,
    val forward: Boolean = false,
    val unreliable: Boolean = false,
    val priority: PacketPriority = PacketPriority.normal,
)

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class TypeIOHandler