package coredevices.util.transcription

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Best-effort only: the JVM doesn't guarantee OS-level realtime scheduling from Thread.priority.
// Largely moot on this target anyway, since cactus only ships a stub (no real local inference) here.
actual suspend fun withHighPriorityThread(block: suspend () -> Unit) {
    withContext(Dispatchers.Default.limitedParallelism(1)) {
        val thread = Thread.currentThread()
        val originalPriority = thread.priority
        thread.priority = Thread.MAX_PRIORITY
        try {
            block()
        } finally {
            thread.priority = originalPriority
        }
    }
}

actual suspend fun getFreeMemoryMB(): Long {
    val runtime = Runtime.getRuntime()
    val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
    val maxMemory = runtime.maxMemory() / (1024 * 1024)
    return maxMemory - usedMemory
}

actual val PLATFORM_MIN_TRANSCRIPTION_MEMORY_MB: Long = 20
