plugins {
    `java-gradle-plugin`
    `maven-publish`
    kotlin("jvm") version "2.2.0"
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    implementation(gradleApi())
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.2.0")
    implementation("com.squareup:kotlinpoet-jvm:1.17.0")
    implementation(kotlin("stdlib", "2.2.0"))
}

group = "io.eve.ktannot"
version = "0.2.0"

gradlePlugin {
    plugins {
        register("ktannotPlugin") {
            id = "io.eve.ktannot"
            implementationClass = "io.eve.ktannot.KtAnnotationsPlugin"
        }
    }
}

publishing {
    publications {
        withType<MavenPublication>().configureEach {
            pom {
                name.set("ktannot-gradle-plugin")
            }
        }
    }
}
