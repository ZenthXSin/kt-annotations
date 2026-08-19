plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    `maven-publish`
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

group = "io.eve.ktannot"
version = "0.1.0"

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
