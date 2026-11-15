import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-library`
    `maven-publish`
}

// Java 8 bytecode: runs on every Android version and any desktop JVM
java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
    withSourcesJar()
    withJavadocJar()
}

kotlin {
    explicitApi = org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode.Disabled
    compilerOptions { jvmTarget.set(JvmTarget.JVM_1_8) }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

tasks.test { useJUnitPlatform() }

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "castwire"
            from(components["java"])
            pom {
                name.set("Castwire")
                description.set("Google Cast sender for Kotlin and Java, without Google's SDK or Play Services")
                url.set("https://github.com/biolend83/castwire")
                licenses {
                    license {
                        name.set("GNU Affero General Public License v3.0")
                        url.set("https://www.gnu.org/licenses/agpl-3.0.txt")
                    }
                    license {
                        name.set("Castwire Commercial License")
                        url.set("https://github.com/biolend83/castwire/blob/main/COMMERCIAL.md")
                    }
                }
                developers { developer { id.set("biolend83"); name.set("biolend83") } }
                scm { url.set("https://github.com/biolend83/castwire") }
            }
        }
    }
}
