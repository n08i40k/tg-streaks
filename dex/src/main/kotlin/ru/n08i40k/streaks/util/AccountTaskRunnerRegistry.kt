package ru.n08i40k.streaks.util

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.ConcurrentHashMap

class AccountTaskRunnerRegistry(
    private val logger: Logger,
) {
    private data class AccountTaskRunner(
        val scope: CoroutineScope,
        val taskQueue: TaskQueue,
    )

    private val runners = ConcurrentHashMap<Int, AccountTaskRunner>()

    fun enqueue(accountId: Int, name: String, callback: suspend () -> Unit) =
        getOrCreate(accountId).taskQueue.enqueueTask(name, callback)

    fun stop(accountId: Int) {
        val runner = runners.remove(accountId) ?: return
        runner.scope.cancel()
        runner.taskQueue.stopWorker()
    }

    fun stopAll(exceptAccountId: Int? = null) {
        val accountIds = runners.keys.toList()

        accountIds.forEach { accountId ->
            if (exceptAccountId != null && accountId == exceptAccountId)
                return@forEach

            stop(accountId)
        }
    }

    private fun getOrCreate(accountId: Int): AccountTaskRunner =
        runners.computeIfAbsent(accountId) { createRunner(it) }

    private fun createRunner(accountId: Int): AccountTaskRunner {
        val scope =
            CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, exception ->
                logger.fatal(
                    "An unknown error occurred in background coroutine scope for account $accountId",
                    exception
                )
            })
        val taskQueue = TaskQueue(logger)
        taskQueue.startWorker(scope)

        return AccountTaskRunner(scope, taskQueue)
    }
}
