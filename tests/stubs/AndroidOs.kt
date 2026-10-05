// Minimal stand-ins for the Android classes DataManager uses, so it runs on the JVM in tests.
package android.os
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
class Looper private constructor() {
    companion object {
        val main = Looper()
        @JvmStatic fun getMainLooper(): Looper = main
        val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "main").apply { isDaemon = true } }
    }
}
class Handler(l: Looper) {
    private val tasks = java.util.Collections.synchronizedList(ArrayList<Pair<Runnable, ScheduledFuture<*>>>())
    fun post(r: Runnable): Boolean { postDelayed(r, 0); return true }
    fun postDelayed(r: Runnable, ms: Long): Boolean {
        lateinit var f: ScheduledFuture<*>
        f = Looper.exec.schedule({ tasks.removeIf { it.second === f }; r.run() }, ms, TimeUnit.MILLISECONDS)
        tasks.add(r to f); return true
    }
    fun removeCallbacks(r: Runnable) { synchronized(tasks) { tasks.filter { it.first === r }.forEach { it.second.cancel(false) }; tasks.removeIf { it.first === r } } }
}
