plugins {
    `java-library`
    kotlin("jvm") version "2.2.0"
    `maven-publish`
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
