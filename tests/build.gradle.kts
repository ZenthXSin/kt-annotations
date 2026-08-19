plugins {
    id("io.eve.ktannot")
    kotlin("jvm") version "2.2.0"
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // 模拟 Mindustry 依赖(仅编译期需要;生成代码引用 mindustry.ctype / logic 等,测试时用桩代替)
    implementation("com.squareup:kotlinpoet:1.17.0")
    implementation(project(":annotations"))
    testImplementation(kotlin("test"))
}

sourceSets {
    main {
        kotlin.srcDir("src/main/kotlin")
        kotlin.srcDir("build/generated/ktannot/main/kotlin")
    }
}

tasks.named("build") {
    dependsOn("generateKtAnnotations")
}