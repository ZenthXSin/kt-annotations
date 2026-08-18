plugins {
    id("io.eve.ktannot")
    kotlin("jvm") version "2.0.21"
}

repositories {
    mavenCentral()
    google()
    maven { url = uri("https://jitpack.io") }
}

kotlin {
    jvmToolchain(17)
    sourceSets {
        main {
            kotlin.srcDir("src/main/kotlin")
            kotlin.exclude("**/comp/**", "**/entity/**")
            kotlin.srcDir("build/generated/ktannot/main/kotlin")
        }
    }
}

ktAnnotations {
    mindustryMode = true
    genPackage = "io.eve.vanilla.gen"
}

dependencies {
    implementation("com.github.Anuken.Mindustry:core:v159.7")
    implementation("com.github.Anuken.Arc:arc-core:208a754044")
    implementation(project(":annotations"))
    implementation(kotlin("stdlib"))
}

tasks.named("build") {
    dependsOn("generateKtAnnotations")
}