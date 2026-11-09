package com.github.claudecommit

import com.intellij.ui.scale.JBUIScale
import com.intellij.ui.scale.ScaleContext
import com.intellij.util.SVGLoader
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.swing.Icon
import javax.swing.SwingUtilities
import javax.swing.Timer

/** Pure playback calculation; boundaries advance to the next frame, one-shot playback holds the final frame. */
internal fun frameIndex(
    durationsMs: List<Int>, loop: Boolean, staticFrame: Int, staticPlayback: Boolean, elapsedMs: Long,
): Int {
    require(durationsMs.isNotEmpty() && durationsMs.all { it > 0 } && staticFrame in durationsMs.indices)
    if (staticPlayback) return staticFrame
    val total = durationsMs.sumOf { it.toLong() }
    val elapsed = elapsedMs.coerceAtLeast(0)
    if (!loop && elapsed >= total) return durationsMs.lastIndex
    var offset = if (loop) elapsed % total else elapsed
    for (index in durationsMs.indices) {
        if (offset < durationsMs[index]) return index
        offset -= durationsMs[index]
    }
    return durationsMs.lastIndex
}

/**
 * A single stable Content.icon; ticks repaint the actual painted header hosts, never the terminal body.
 * State other than close()/image ownership is EDT confined. The service must close on replacement/disposal.
 *
 * Frames are rasterized at a fixed 4x density on the bounded worker. Swing's Graphics applies system scale;
 * JBUIScale supplies user-scaled logical dimensions. Thus Retina pixels are not mistaken for logical pixels.
 * Fractional and cross-monitor scales need no EDT rendering or unbounded scale cache. Above combined 4x
 * scale this deliberately trades some sharpness for a strict 1 MiB/icon, 32 MiB/global live pixel budget.
 */
class TerminalIconAnimation private constructor(
    images: List<BufferedImage>,
    private val durationsMs: List<Int>,
    private val loop: Boolean,
    private val staticFrame: Int,
    private val staticPlayback: Boolean,
    private val reservedBytes: Long,
) : Icon, AutoCloseable {
    private val frames = AtomicReference<List<BufferedImage>?>(images)
    private val closed = AtomicBoolean(false)
    private val hosts = mutableListOf<WeakReference<Component>>()
    private var elapsedBeforeResume = 0L
    private var resumedAtNanos: Long? = null
    private val totalDurationMs = durationsMs.sumOf { it.toLong() }
    private val timer = Timer(50) { tick() }.apply { isRepeats = false; isCoalesce = true }

    override fun getIconWidth(): Int = JBUIScale.scale(16)
    override fun getIconHeight(): Int = JBUIScale.scale(16)

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val available = frames.get() ?: return
        // Offscreen/printing calls must not mutate Swing state or start animation scheduling.
        val edt = SwingUtilities.isEventDispatchThread()
        if (edt && !closed.get() && c != null) {
            hosts.removeAll { it.get() == null }
            if (hosts.none { it.get() === c }) {
                if (hosts.size == MAX_HOSTS) hosts.removeAt(0)
                hosts += WeakReference(c)
            }
            if (c.isShowing && !staticPlayback && durationsMs.size > 1 && !finished()) {
                if (resumedAtNanos == null) resumedAtNanos = System.nanoTime()
                if (!timer.isRunning) scheduleNext()
            }
        }
        val index = if (edt) currentFrame() else staticFrame
        val graphics = g.create()
        try {
            if (graphics is Graphics2D) graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            graphics.drawImage(available[index], x, y, iconWidth, iconHeight, null)
        } finally {
            graphics.dispose()
        }
    }

    private fun elapsedMs(): Long = elapsedBeforeResume + (resumedAtNanos?.let { (System.nanoTime() - it) / 1_000_000 } ?: 0)

    private fun currentFrame(): Int = frameIndex(durationsMs, loop, staticFrame, staticPlayback, elapsedMs())

    // The last frame has no transition remaining, even before its nominal final duration expires.
    private fun finished(): Boolean = !loop && elapsedMs() >= totalDurationMs - durationsMs.last()

    private fun scheduleNext() {
        if (closed.get() || staticPlayback || durationsMs.size == 1 || finished()) return
        val offset = elapsedMs() % totalDurationMs
        var boundary = 0L
        for (duration in durationsMs) {
            boundary += duration
            if (boundary > offset) break
        }
        timer.initialDelay = (boundary - offset).coerceIn(1, 5000).toInt()
        timer.restart()
    }

    private fun tick() {
        if (closed.get()) return
        hosts.removeAll { it.get() == null }
        val showing = hosts.mapNotNull { it.get() }.filter { it.isShowing }
        if (showing.isEmpty()) {
            elapsedBeforeResume = elapsedMs()
            resumedAtNanos = null
            timer.stop()
            return
        }
        // Repaint only: no Content.icon reassignment, property changes, or per-frame layout invalidation.
        showing.forEach { it.repaint() }
        scheduleNext()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Drop image ownership immediately, including when disposal originates on a background worker.
        frames.getAndSet(null)
        RasterBudget.release(reservedBytes)
        val disposeUi = Runnable {
            timer.stop()
            hosts.clear()
            resumedAtNanos = null
        }
        if (SwingUtilities.isEventDispatchThread()) disposeUi.run() else SwingUtilities.invokeLater(disposeUi)
    }

    companion object {
        private const val MAX_HOSTS = 16
        private const val RASTER_SIZE = 64

        internal fun render(asset: TerminalIconAsset, staticPlayback: Boolean): TerminalIconAnimation {
            check(!SwingUtilities.isEventDispatchThread()) { "Terminal SVG rendering must run off the EDT" }
            val bytes = asset.frames.size.toLong() * RASTER_SIZE * RASTER_SIZE * 4
            RasterBudget.acquire(bytes)
            try {
                val images = asset.frames.map { frame ->
                    if (Thread.currentThread().isInterrupted) throw InterruptedException("Icon rendering cancelled")
                    TerminalSvgRenderer.rasterize(frame.svg, RASTER_SIZE)
                }
                return TerminalIconAnimation(images, asset.frames.map { it.durationMs }, asset.loop, asset.staticFrame, staticPlayback, bytes)
            } catch (error: Throwable) {
                RasterBudget.release(bytes)
                when (error) {
                    is TerminalIconException -> throw error
                    is InterruptedException -> {
                        Thread.currentThread().interrupt()
                        throw TerminalIconException("render_failed", "Icon rendering cancelled")
                    }
                    is LinkageError -> throw TerminalIconException("unsupported", "Platform SVG rendering API is unavailable")
                    is Exception -> throw TerminalIconException("render_failed", "SVG rendering failed")
                    else -> throw error
                }
            }
        }
    }
}

private object RasterBudget {
    private const val MAX_BYTES = 32L * 1024 * 1024
    private val used = AtomicLong()

    fun acquire(bytes: Long) {
        while (true) {
            val before = used.get()
            if (bytes > MAX_BYTES - before) throw TerminalIconException("resource_limit", "Live icon pixel budget exceeded")
            if (used.compareAndSet(before, before + bytes)) return
        }
    }

    fun release(bytes: Long) { used.addAndGet(-bytes) }
}

/** The only linkage to SVGLoader's Internal API. Input is always DOM-normalized by TerminalIconAssets. */
private object TerminalSvgRenderer {
    fun rasterize(normalizedSvg: String, size: Int): BufferedImage {
        val image = ByteArrayInputStream(normalizedSvg.toByteArray(Charsets.UTF_8)).use { stream ->
            SVGLoader.load(null, stream, ScaleContext.createIdentity(), size.toDouble(), size.toDouble())
        }
        // A plain, known-size raster avoids depending on SVGLoader's HiDPI Image implementation in paintIcon.
        check(image.getWidth(null) == size && image.getHeight(null) == size) { "Unexpected SVG raster dimensions" }
        val raster = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val graphics = raster.createGraphics()
        try {
            check(graphics.drawImage(image, 0, 0, size, size, null)) { "Incomplete SVG raster" }
        } finally {
            graphics.dispose()
            image.flush()
        }
        return raster
    }
}
