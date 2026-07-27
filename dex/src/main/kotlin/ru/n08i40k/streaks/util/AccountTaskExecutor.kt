package ru.n08i40k.streaks.util

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.jetbrains.annotations.Blocking
import ru.n08i40k.streaks.event.eject.EjectNotifier
import java.util.concurrent.ConcurrentHashMap

object AccountTaskExecutor : EjectNotifier.Delegate {
    init {
        EjectNotifier.subscribe(this)
    }

    private data class AccountTaskRunner(
        val scope: CoroutineScope,
        val taskQueue: TaskQueue,
    ) {
        @Blocking
        fun stop() {
            scope.cancel()
            taskQueue.stopWorker()

            runBlocking { scope.coroutineContext.job.join() }
        }
    }

    private val runners = ConcurrentHashMap<Int, AccountTaskRunner>()

    override fun onEject() = stopAll()

    fun enqueue(accountId: Int, name: String, callback: suspend () -> Unit) =
        getOrCreate(accountId).taskQueue.enqueueTask(name, callback)

    @Blocking
    fun stop(accountId: Int) = runners.remove(accountId)?.stop()

    @Blocking
    fun stopAll(exceptAccountId: Int? = null) =
        runners.keys
            .toList()
            .forEach { accountId ->
                if (exceptAccountId == accountId)
                    return@forEach

                stop(accountId)
            }

    private fun getOrCreate(accountId: Int): AccountTaskRunner =
        runners.computeIfAbsent(accountId) { accountId ->
            val scope =
                CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, exception ->
                    Logger.fatal(
                        "An unknown error occurred in background coroutine scope for account $accountId",
                        exception
                    )
                })

            return@computeIfAbsent AccountTaskRunner(
                scope,
                TaskQueue().apply { startWorker(scope) }
            )
        }
}
