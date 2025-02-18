package me.gabryon.doomed

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
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
import java.util.*
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

/**
 * Constants and configurations for the Doom game panel.
 */
private object DoomConfig {
    const val SCREEN_WIDTH = 640
    const val SCREEN_HEIGHT = 400
    const val TARGET_FPS = 60
}

/**
 * Represents a key event in the game's input queue.
 */
sealed interface QueueKeyEvent {
    val keyCode: Int

    /**
     * Represents a key press event.
     * @property keyCode The key code of the pressed key
     */
    data class Pressed(override val keyCode: Int) : QueueKeyEvent

    /**
     * Represents a key release event.
     * @property keyCode The key code of the released key
     */
    data class Released(override val keyCode: Int) : QueueKeyEvent
}

/**
 * Mapping of Java KeyEvent codes to Doom-specific key codes.
 */
private val KeyCodeToDoomKey = mapOf<Int, Int>(
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

/**
 * Main panel for rendering the Doom game interface.
 * This panel handles the game rendering, input processing, and communication with the native Doom engine.
 * The panel maintains a frame buffer for rendering and processes keyboard input events.
 *
 * Thread Safety: This implementation is thread-safe, using synchronized collections and proper synchronization
 * for shared resources like the frame buffer and input queue.
 */
class DoomPanel : JPanel(), DoomGeneric, Disposable {

    companion object {
        private val START_TICKS = System.currentTimeMillis()
        private val LOG = DoomPanel.thisLogger()

        init {
            NativeLibraryLoader.loadLibraryFromResources()
        }
    }

    // Performance Note: Using BufferedImage for direct pixel manipulation
    private val frameBuffer: BufferedImage
    private val gameThread: Thread

    // Performance Note: Using primitive array for better performance in pixel manipulation
    private val pixels = IntArray(DoomConfig.SCREEN_WIDTH * DoomConfig.SCREEN_HEIGHT)

    // Thread-safe queue for keyboard events
    private val inputQueue = Collections.synchronizedList(ArrayList<QueueKeyEvent>())

    private var doomTitle: String = "<unknown>"
        private set  // Protect the title from external modifications

    init {
        try {
            isFocusable = true
            requestFocusInWindow() // Request initial focus

            // Ensure focus is regained when clicked
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent?) {
                    requestFocusInWindow()
                }
            })

            // Configure panel dimensions
            preferredSize = Dimension(DoomConfig.SCREEN_WIDTH * 2, DoomConfig.SCREEN_HEIGHT * 2)
            minimumSize = Dimension(DoomConfig.SCREEN_WIDTH, DoomConfig.SCREEN_HEIGHT)

            // Initialize frame buffer
            // Performance Note: Using TYPE_INT_ARGB for optimal rendering performance
            frameBuffer = UIUtil.createImage(
                this,
                DoomConfig.SCREEN_WIDTH,
                DoomConfig.SCREEN_HEIGHT,
                BufferedImage.TYPE_INT_ARGB
            )

            // Start game thread
            gameThread = thread(start = true) {
                Thread.currentThread().name = "GameThread"
                create(
                    3, listOf(
                        "kdoom",
                        "-iwad", NativeLibraryLoader.loadWadFile()
                    )
                )
                while (!Thread.currentThread().isInterrupted) {
                    tick()
                }
            }

            // Set up keyboard input handling
            addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent?) {
                    super.keyPressed(e)
                    e?.let {
                        // Note: Checking for escape key but not handling it specially
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

            // Set up render timer
            // Performance Note: Using fixed time step for consistent frame rate
            val renderTimer = Timer(1000 / DoomConfig.TARGET_FPS) { _ -> repaint() }
            renderTimer.start()
        } catch (e: Exception) {
            throw RuntimeException("Failed to initialize Doom panel", e)
        }
    }

    /**
     * Creates and initializes the Doom game engine with the specified arguments.
     * This is a native method implemented in the Doom engine.
     *
     * @param argc Number of command line arguments
     * @param argv List of command line arguments
     */
    external override fun create(argc: Int, argv: List<String>)

    /**
     * Performs a single tick of the game engine.
     * This is a native method that updates the game state.
     */
    external override fun tick()

    /**
     * Initializes the game panel components.
     * Called by the native code during engine initialization.
     */
    override fun init() {
        LOG.info("i nitializing the Doom game engine...")
    }

    /**
     * Renders a single frame of the game.
     * This method handles color format conversion and synchronized frame buffer updates.
     *
     * @param screenBuffer Buffer containing the raw frame data from the engine
     */
    override fun drawFrame(screenBuffer: ByteBuffer) {
        /**
         * Converts RGB color format to ARGB format.
         * Performance Note: This conversion is necessary for Java's BufferedImage format,
         * but it does introduce some overhead.
         *
         * @return The color in ARGB format
         */
        fun Int.swizzleColor(): Int {
            val r = (this shr 16) and 0xff
            val g = (this shr 8) and 0xff
            val b = this and 0xff
            return (0xff shl 24) or (r shl 16) or (g shl 8) or b  // ARGB
        }

        synchronized(frameBuffer) {
            try {
                screenBuffer.order(ByteOrder.LITTLE_ENDIAN)
                screenBuffer.asIntBuffer().get(pixels)

                for (i in pixels.indices) {
                    pixels[i] = pixels[i].swizzleColor()
                }

                frameBuffer.setRGB(
                    0, 0,
                    DoomConfig.SCREEN_WIDTH, DoomConfig.SCREEN_HEIGHT,
                    pixels, 0, DoomConfig.SCREEN_WIDTH
                )
            } catch (e: Exception) {
                LOG.error("failed to render frame: ${e.message}")
            }
        }
    }

    /**
     * Suspends the current thread for the specified duration.
     * Used by the engine for timing control.
     *
     * @param ms Duration to sleep in milliseconds
     */
    override fun sleepMs(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            LOG.warn("sleep interrupted in thread ${Thread.currentThread().name}")
        }
    }

    /**
     * Returns the number of milliseconds since the game started.
     * Used by the engine for timing and animation.
     *
     * @return Elapsed time in milliseconds
     */
    override fun getTickMs(): Long =
        System.currentTimeMillis() - START_TICKS

    /**
     * Processes and returns the next key event from the input queue.
     * Performance Note: This method is called frequently by the engine,
     * so we use efficient bit operations for key code conversion.
     *
     * @return Encoded key event data or 0 if no event is available
     */
    override fun getKey(): Int {
        if (inputQueue.isEmpty()) return 0

        val event = inputQueue.removeAt(0)
        val pressed = when (event) {
            is QueueKeyEvent.Pressed -> 1
            is QueueKeyEvent.Released -> 0
        }

        val doomKeyCode = KeyCodeToDoomKey[event.keyCode]
            ?: event.keyCode.toChar().lowercase().first().code

        return (pressed shl 8) or doomKeyCode
    }

    /**
     * Updates the game window title.
     * Note: This implementation is minimal as we're running inside IntelliJ.
     *
     * @param title New window title
     */
    override fun setWindowTitle(title: String) {
        doomTitle = title
    }

    /**
     * Renders the game frame to the panel.
     * This method handles scaling and centering of the game frame to maintain aspect ratio.
     * Performance Note: Uses nearest-neighbor interpolation for crisp pixel art rendering.
     *
     * @param g The graphics context to paint on
     */
    override fun paintComponent(g: Graphics?) {
        super.paintComponent(g)

        if (g == null) return

        try {
            val g2d = g as Graphics2D
            // Use nearest-neighbor interpolation for crisp pixel art
            g2d.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
            )

            // Calculate scaling to maintain aspect ratio
            val scale = min(
                getWidth() / DoomConfig.SCREEN_WIDTH.toDouble(),
                getHeight() / DoomConfig.SCREEN_HEIGHT.toDouble()
            )

            // Calculate dimensions for centered rendering
            val scaledWidth = (DoomConfig.SCREEN_WIDTH * scale).toInt()
            val scaledHeight = (DoomConfig.SCREEN_HEIGHT * scale).toInt()
            val x = (getWidth() - scaledWidth) / 2
            val y = (getHeight() - scaledHeight) / 2

            synchronized(frameBuffer) {
                // Set background color and draw the scaled frame
                g2d.background = JBColor.BLACK
                g2d.drawImage(frameBuffer, x, y, scaledWidth, scaledHeight, null)
            }
        } catch (e: Exception) {
            LOG.error("failed to paint component: ${e.message}")
            g.color = JBColor.RED
            g.drawString("Error rendering game frame", 10, 20)
        }
    }

    override fun dispose() {
        // Send interrupting request to the game loop
        gameThread.interrupt()
    }
}
