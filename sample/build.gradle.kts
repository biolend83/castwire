plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}


dependencies {
    implementation(project(":castwire"))
}

application {
    mainClass.set("io.github.biolend83.castwire.sample.MainKt")
}

tasks.named<JavaExec>("run") { standardInput = System.`in` }
