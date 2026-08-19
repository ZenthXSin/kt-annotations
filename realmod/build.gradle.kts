plugins {
    id("io.eve.ktannot")
    kotlin("jvm") version "2.2.0"
}

repositories {
    mavenCentral()
    google()
    maven { url = uri("https://jitpack.io") }
}

kotlin {
    jvmToolchain(17)
}

ktAnnotations {
    mindustryMode = true
    genPackage = "io.eve.ktannot.gen"
}

dependencies {
    // 真实 Mindustry v159.7 core（含生成的 mindustry.gen.Player/Call 等），经 JitPack 解析
    implementation("com.github.Anuken.Mindustry:core:v159.7")
    // arc v159.7 对应 commit（含 arc.util.io.Writes/Reads、arc.Core 等）；core v159.7 的 POM 已传递依赖 208a754044
    implementation("com.github.Anuken.Arc:arc-core:208a754044")
    implementation(project(":annotations"))
    implementation(project(":vanilla"))
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

// mod jar:类 + Kotlin stdlib + mod.hjson + assets(排除 mindustry/arc 依赖——引擎已提供)
tasks.register<Jar>("modJar") {
    dependsOn("classes")
    archiveFileName.set("realmod.jar")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    from(sourceSets["main"].output)
    // 仅打包 Kotlin stdlib 与注解库,排除引擎已含的 mindustry/arc jar
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/MANIFEST.MF")
        exclude("mindustry/**", "arc/**", "generated/**", "org/jbox2d/**", "com/codex/**")
    }
    from("mod.hjson") { into("/") }
    from("assets") { into("assets") }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
