package io.eve.ktannot.gen

/**
 * 生成代码自我标记注解(真实 mod 环境提供,避免依赖测试桩)。
 * 运行期无行为,仅标记生成的实体/接口。
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class EntityInterface