package ru.n08i40k.streaks.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.Instant

class TaskQueue(private val logger: Logger) {
    data class WorkerTask(
        val name: String,
        val callback: suspend () -> Unit,
    )

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

                        logger.info("[TaskQueue] Processing task '${task.name}'...")
                        val start = Instant.now().toEpochMilli()

                        task.callback.invoke()

                        val end = Instant.now().toEpochMilli()
                        logger.info("[TaskQueue] Task '${task.name}' was finished (took ${end - start} ms.)")
                    } catch (_: CancellationException) {
                        // Suppress
                        stopWorker()
                        break
                    } catch (e: Throwable) {
                        logger.fatal("[TaskQueue] Task '${task.name}' thrown an exception", e)
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

    val isWorkerRunning get() = isWorkerStarted

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
