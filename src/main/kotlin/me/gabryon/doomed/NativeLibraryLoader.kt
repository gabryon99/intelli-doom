package me.gabryon.doomed

import com.intellij.openapi.util.SystemInfo
import java.io.File

object NativeLibraryLoader {
    fun loadLibraryFromResources() {
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
            else -> error("Unsupported platform: $osName")
        }

        val soExtension = when {
            SystemInfo.isWindows -> "dll"
            SystemInfo.isMac -> "dylib"
            SystemInfo.isLinux -> "so"
            else -> error("Unsupported OS: $osName")
        }
        val libraryName = "libkdoomgeneric.${soExtension}"

        val tmpDir = System.getProperty("java.io.tmpdir")
        val resourcePath = "/native/$platformDir/$libraryName"
        val tmpFile = File(tmpDir, libraryName)

        javaClass.getResourceAsStream(resourcePath).use { input ->
            tmpFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        try {
            System.load(tmpFile.absolutePath)
        } catch (e: UnsatisfiedLinkError) {
            throw RuntimeException("Failed to load native Doom library. ", e)
        }

        tmpFile.deleteOnExit()
    }

    fun loadWadFile(): String {
        val tmpDir = System.getProperty("java.io.tmpdir")
        val wadPath = "/game/doom1.wad"
        val tmpFile = File(tmpDir, "doom1.wad")
        javaClass.getResourceAsStream(wadPath).use { input ->
            tmpFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        tmpFile.deleteOnExit()
        return tmpFile.absolutePath.also { println(it) }
    }
}