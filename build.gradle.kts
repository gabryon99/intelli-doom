plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "me.gabryon"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

// Configure Gradle IntelliJ Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-gradle-intellij-plugin.html
intellij {
    version.set("2024.1.7")
    type.set("IC") // Target IDE Platform

    plugins.set(listOf(/* Plugin Dependencies */))
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }

    patchPluginXml {
        sinceBuild.set("241")
        untilBuild.set("243.*")
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }

    register<Exec>("compileDoomGeneric") {
        workingDir = file("src/main/native/libraries/doomgeneric/doomgeneric")
        commandLine = listOf("make")
    }

    register<Exec>("compileKDoomGeneric") {
        dependsOn("compileDoomGeneric")

        workingDir = file("src/main/native/")
        commandLine("bash", "-c", """
            set -e
            mkdir -p cmake-build-debug
            cd cmake-build-debug
            cmake ..
            cmake --build .
        """.trimIndent())
    }

    register<Copy>("moveKDoomGenericToResources") {
        dependsOn("compileKDoomGeneric")

        val osName = System.getProperty("os.name").lowercase()
        val osArch = System.getProperty("os.arch").lowercase()
        val platformDir = when {
            osName.contains("mac") -> {
                when {
                    osArch.contains("aarch64") -> "macos-aarch64"
                    else -> "macos-x86_64"
                }
            }
            osName.contains("linux") -> "linux-x64"
            osName.contains("windows") -> "windows-x64"
            else -> throw GradleException("Unsupported platform: $osName")
        }

        description = "Copy the shared library in resources folder"
        from("src/main/native/cmake-build-debug/") {
            include("libkdoomgeneric.dylib")
        }
        into("src/main/resources/native/$platformDir")
    }

    processResources {
        dependsOn("moveKDoomGenericToResources")
    }

}
