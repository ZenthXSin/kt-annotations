plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.0.21")
    implementation("com.squareup:kotlinpoet-jvm:1.17.0")
    implementation(kotlin("stdlib"))
}