package io.github.jackwener.dsh.core.platform

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Shizuku user service: instantiated by Shizuku in a process running as the shell user (uid 2000),
 * which has the privileges ordinary apps lack (input injection, `svc`, `settings`, trusted displays).
 */
class ShellUserService : IShellService.Stub() {
    private val displays = HashMap<Int, Pair<VirtualDisplay, ImageReader>>()

    override fun destroy() { displays.values.forEach { it.first.release(); it.second.close() }; exitProcess(0) }

    override fun exec(cmd: String, timeoutMs: Long): String {
        val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
        // Read on a separate thread: readText() blocks until stdout closes, so a hung command would
        // never reach waitFor/destroy and would hold a Shizuku binder thread for ever.
        val out = StringBuilder()
        val reader = Thread {
            runCatching {
                p.inputStream.bufferedReader().use { r ->
                    val buf = CharArray(8192)
                    while (true) { val n = r.read(buf); if (n < 0) break; synchronized(out) { if (out.length < 64_000) out.append(buf, 0, minOf(n, 64_000 - out.length)) } }
                }
            }
        }.apply { isDaemon = true; start() }
        val done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!done) p.destroyForcibly()
        reader.join(if (done) 2_000 else 500)
        return "${if (done) p.exitValue() else 124}\n${synchronized(out) { out.toString() }}"
    }

    @SuppressLint("WrongConstant")
    override fun createDisplay(width: Int, height: Int, dpi: Int): Int {
        if (Looper.myLooper() == null) Looper.prepare()
        val ctx = SystemContext.get()
        val dm = ctx.getSystemService(DisplayManager::class.java)
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        // PUBLIC | OWN_CONTENT_ONLY | TRUSTED(1 shl 10): lets other apps' activities run on it.
        val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or (1 shl 10)
        val vd = dm.createVirtualDisplay("dsh-agent", width, height, dpi, reader.surface, flags) ?: return -1
        val id = vd.display.displayId
        displays[id] = vd to reader
        return id
    }

    override fun releaseDisplay(displayId: Int) {
        displays.remove(displayId)?.let { it.first.release(); it.second.close() }
    }

    override fun captureDisplay(displayId: Int): ByteArray? {
        val reader = displays[displayId]?.second ?: return null
        val image = reader.acquireLatestImage() ?: return null
        image.use {
            val plane = it.planes[0]
            val padding = plane.rowStride - plane.pixelStride * it.width
            val bmp = Bitmap.createBitmap(it.width + padding / plane.pixelStride, it.height, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(plane.buffer)
            val cropped = Bitmap.createBitmap(bmp, 0, 0, it.width, it.height)
            return ByteArrayOutputStream().also { o -> cropped.compress(Bitmap.CompressFormat.JPEG, 80, o) }.toByteArray()
        }
    }
}

/** Obtains a system Context inside the Shizuku process via ActivityThread (hidden API, shell has access). */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
private object SystemContext {
    fun get(): android.content.Context {
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getMethod("currentActivityThread").invoke(null) ?: at.getMethod("systemMain").invoke(null)
        return at.getMethod("getSystemContext").invoke(thread) as android.content.Context
    }
}
