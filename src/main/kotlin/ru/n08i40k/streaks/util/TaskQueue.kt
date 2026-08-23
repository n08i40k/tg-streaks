package ru.n08i40k.streaks.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.time.Clock

class TaskQueue {
    data class WorkerTask(
        val name: String,
        val callback: suspend () -> Unit,
    )

    @Volatile
    private var isWorkerStarted = false
    private val queue = Channel<WorkerTask>(Channel.UNLIMITED)

    fun startWorker(scope: CoroutineScope) {
        if (isWorkerStarted)
            throw IllegalStateException("Task queue worker is already started!")

        suspend fun worker(channel: ReceiveChannel<WorkerTask>) {
            try {
                for (task in channel) {
                    try {
                        RuntimeGuard.awaitAppForeground("task '${task.name}'")

                        Logger.info("[TaskQueue] Processing task '${task.name}'...")
                        val start = Clock.System.now().toEpochMilliseconds()

                        task.callback.invoke()

                        val end = Clock.System.now().toEpochMilliseconds()
                        Logger.info("[TaskQueue] Task '${task.name}' was finished (took ${end - start} ms.)")
                    } catch (_: CancellationException) {
                        // Suppress
                        stopWorker()
                        break
                    } catch (e: Throwable) {
                        Logger.fatal("[TaskQueue] Task '${task.name}' thrown an exception", e)
                        stopWorker()
                        break
                    }
                }
            } finally {
                isWorkerStarted = false
            }
        }

        scope.launch { worker(queue) }
        isWorkerStarted = true
    }

    fun stopWorker() {
        if (!isWorkerStarted)
            return

        isWorkerStarted = false
        queue.close()
    }

    fun enqueueTask(name: String, callback: suspend () -> Unit) {
        if (!isWorkerStarted)
            throw IllegalStateException("Task queue worker was not started!")

        runBlocking { queue.send(WorkerTask(name, callback)) }
    }
}
