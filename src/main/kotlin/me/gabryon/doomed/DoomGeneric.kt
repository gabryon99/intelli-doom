package me.gabryon.doomed

import java.nio.ByteBuffer

interface DoomGeneric {
    fun create(argc: Int, argv: List<String>)
    fun tick()

    fun init()
    fun drawFrame(screenBuffer: ByteBuffer)
    fun sleepMs(ms: Long)
    fun getTickMs(): Long
    fun getKey(): Int
    fun setWindowTitle(title: String)
}
