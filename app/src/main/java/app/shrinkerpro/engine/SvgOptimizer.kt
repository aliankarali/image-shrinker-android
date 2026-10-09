package app.shrinkerpro.engine

import android.content.Context
import com.whl.quickjs.android.QuickJSLoader
import com.whl.quickjs.wrapper.QuickJSContext
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * Runs svgo 4.1.0 — the same optimiser, at the same version, as the desktop
 * app — inside an embedded QuickJS engine. No WebView and no network.
 *
 * QuickJS contexts are bound to the thread that made them, so every call runs
 * on one dedicated thread with a deep stack (svgo recurses over the XML tree).
 */
class SvgOptimizer(private val context: Context) {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(null, runnable, "svgo", 16L * 1024 * 1024).apply { isDaemon = true }
    }
    private var js: QuickJSContext? = null

    fun optimize(svg: String): String = try {
        executor.submit(Callable { run(svg) }).get()
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    }

    private fun run(svg: String): String {
        val js = this.js ?: load().also { this.js = it }
        val global = js.globalObject
        global.setProperty("__shrinkerInput", svg)
        try {
            val result = js.evaluate("globalThis.svgo.optimize(globalThis.__shrinkerInput).data")
            return (result as? String)?.takeIf { it.isNotEmpty() }
                ?: throw IllegalStateException("svgo returned no output")
        } finally {
            global.setProperty("__shrinkerInput", "")
        }
    }

    private fun load(): QuickJSContext {
        QuickJSLoader.init()
        val js = QuickJSContext.create()
        js.setMaxStackSize(8 * 1024 * 1024)
        val source = context.assets.open("svgo.js").bufferedReader().use { it.readText() }
        js.evaluate(source, "svgo.js")
        check(js.evaluate("typeof globalThis.svgo === 'object'") == true) { "svgo did not register itself" }
        return js
    }
}
