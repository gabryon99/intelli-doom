package me.gabryon.doomed

import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import org.intellij.markdown.lexer.pop
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.swing.JPanel
import javax.swing.Timer
import kotlin.concurrent.thread
import kotlin.math.min

// Sketch notes regarding Doom (using `doomgeneric`). We need to implement the following functions
// * DG_Init
// * DG_DrawFrame
// * DG_SleepMs
// * DG_GetTicksMs
// * DG_GetKey
// * DG_SetWindowTitle

// Most of those functions are C-callbacks, i.e., they will be called from the game at specific time.
// We need to pass some Kotlin callbacks to these functions, so one rough idea would be to:
// * Initialize the shared library
// * Pass a JVM environment to it
// * Link the C-function to the JVM methods
// So, we need both C and JVM collaboration....

// PIXELFORMAT_RGB888
// DOOMGENERIC_RESX
// DOOMGENERIC_RESX

private const val DOOMGENERIC_RESX = 640
private const val DOOMGENERIC_RESY = 400

sealed interface QueueKeyEvent {
    val keyCode: Int
    data class Pressed(override val keyCode: Int) : QueueKeyEvent
    data class Released(override val keyCode: Int) : QueueKeyEvent
}

val KeyCodeToDoomKey = mapOf<Int, Int>(
    KeyEvent.VK_ENTER to 13,
    KeyEvent.VK_ESCAPE to 27,
    KeyEvent.VK_LEFT to 0xac,
    KeyEvent.VK_RIGHT to 0xae,
    KeyEvent.VK_UP to 0xad,
    KeyEvent.VK_DOWN to 0xaf,
    KeyEvent.VK_CONTROL to 0xa3, // fire
    KeyEvent.VK_SPACE to 0xa2, // use
    KeyEvent.VK_SHIFT to (0x80 + 0x36),
    KeyEvent.VK_ALT to (0x80 + 0x38),
    KeyEvent.VK_TAB to 9,
    KeyEvent.VK_F1 to (0x80 + 0x3b),
    KeyEvent.VK_F2 to (0x80 + 0x3c),
    KeyEvent.VK_F3 to (0x80 + 0x3d),
    KeyEvent.VK_F4 to (0x80 + 0x3e),
    KeyEvent.VK_F5 to (0x80 + 0x3f),
    KeyEvent.VK_F6 to (0x80 + 0x40),
    KeyEvent.VK_F7 to (0x80 + 0x41),
    KeyEvent.VK_F8 to (0x80 + 0x42),
    KeyEvent.VK_F9 to (0x80 + 0x43),
    KeyEvent.VK_F10 to (0x80 + 0x44),
    KeyEvent.VK_F11 to (0x80 + 0x57),
    KeyEvent.VK_F12 to (0x80 + 0x58),
    KeyEvent.VK_BACK_SPACE to 0x7f,
    KeyEvent.VK_EQUALS to 0x3d,
    KeyEvent.VK_PAUSE to 0xff,
    KeyEvent.VK_MINUS to 0x2d,
)

class DoomPanel : JPanel(), DoomGeneric {

    companion object {
        val START_TICKS = System.currentTimeMillis()
        init {
            System.load("/Users/Gabriele.Pappalardo/hack/Kotlin/doomed/src/main/native/cmake-build-debug/libkdoomgeneric.dylib")
        }
    }

    private val frameBuffer: BufferedImage
    private val gameThread: Thread
    private val pixels = IntArray(DOOMGENERIC_RESX * DOOMGENERIC_RESY)

    private val inputQueue = ArrayList<QueueKeyEvent>()

    lateinit var doomTitle: String

    init {
        isFocusable = true
        requestFocusInWindow() // Request initial focus

        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent?) {
                requestFocusInWindow() // Request focus when clicked
            }
        })
        preferredSize = Dimension(DOOMGENERIC_RESX * 2, DOOMGENERIC_RESY * 2)
        minimumSize = Dimension(DOOMGENERIC_RESX, DOOMGENERIC_RESY)
        frameBuffer = UIUtil.createImage(this,
            DOOMGENERIC_RESX,
            DOOMGENERIC_RESY,
            BufferedImage.TYPE_INT_ARGB
        )

        gameThread = thread(start = true) {
            Thread.currentThread().name = "GameThread"
            create(3, listOf(
                "kdoom",
                "-iwad", "/Users/Gabriele.Pappalardo/hack/Kotlin/doomed/src/main/resources/doom1.wad"))
            while (!Thread.currentThread().isInterrupted) {
                tick()
            }
        }

        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent?) {
                super.keyPressed(e)
                e?.let {
                    e.keyCode == KeyEvent.VK_ESCAPE
                    inputQueue.add(QueueKeyEvent.Pressed(e.keyCode))
                }
            }
            override fun keyReleased(e: KeyEvent?) {
                super.keyReleased(e)
                e?.let {
                    inputQueue.add(QueueKeyEvent.Released(e.keyCode))
                }
            }
        })

        val renderTimer: Timer = Timer(1000 / 60) { e -> repaint() }
        renderTimer.start()
    }

    external override fun create(argc: Int, argv: List<String>)

    external override fun tick()

    override fun init() {
        println("initializing the game....")
    }

    override fun drawFrame(screenBuffer: ByteBuffer) {
        fun Int.swizzleColor(): Int {
            val r = (this shr 16) and 0xff
            val g = (this shr 8) and 0xff
            val b = this and 0xff
            return (0xff shl 24) or (r shl 16) or (g shl 8) or b  // ARGB
        }

        synchronized(frameBuffer) {
            screenBuffer.order(ByteOrder.LITTLE_ENDIAN)
            screenBuffer.asIntBuffer().get(pixels)

            for (i in pixels.indices) {
                pixels[i] = pixels[i].swizzleColor()
            }

            frameBuffer.setRGB(
                0, 0,
                DOOMGENERIC_RESX, DOOMGENERIC_RESY,
                pixels, 0, DOOMGENERIC_RESX
            )
        }
    }

    override fun sleepMs(ms: Long) {
        println("[info::${Thread.currentThread().name}] Sleeping for $ms ms")
        Thread.sleep(ms)
    }

    override fun getTickMs(): Long =
        System.currentTimeMillis() - START_TICKS

    override fun getKey(): Int {
        if (inputQueue.isEmpty()) return 0
        val key = inputQueue.pop()
        val pressed = when (key) {
            is QueueKeyEvent.Pressed -> 1
            is QueueKeyEvent.Released -> 0
        }
        val keyData = (pressed shl 8) or (KeyCodeToDoomKey[key.keyCode] ?: Character.toChars(key.keyCode)[0].lowercase().toInt())
        return keyData
    }

    // Do nothing, we are inside IntelliJ
    override fun setWindowTitle(title: String) {
        println("[info] :: set title = $title")
        doomTitle = title
    }

    override fun paintComponent(g: Graphics?) {
        super.paintComponent(g)

        val g2d = g as Graphics2D
        g2d.setRenderingHint(
            RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
        )
        val scale = min(
            getWidth() / DOOMGENERIC_RESX.toDouble(),
            getHeight() / DOOMGENERIC_RESY.toDouble()
        )

        val scaledWidth = (DOOMGENERIC_RESX * scale).toInt()
        val scaledHeight = (DOOMGENERIC_RESY * scale).toInt()
        val x = (getWidth() - scaledWidth) / 2
        val y = (getHeight() - scaledHeight) / 2

        synchronized(frameBuffer) {
            g2d.background = JBColor.BLACK
            g2d.drawImage(frameBuffer, x, y, scaledWidth, scaledHeight, null)
        }
    }
}