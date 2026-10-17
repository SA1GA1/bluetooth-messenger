package com.example.bluetooth_messenger.core.coroutines

import kotlinx.coroutines.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionTasks @Inject constructor(@ApplicationScope private val applicationScope: CoroutineScope) {
    @Volatile private var sessionScope: CoroutineScope? = null
    private var sessionJob: CompletableJob? = null

    fun start() {
        check(sessionJob == null)
        val job = SupervisorJob(applicationScope.coroutineContext[Job])
        sessionJob = job
        sessionScope = CoroutineScope(applicationScope.coroutineContext + job + Dispatchers.IO)
    }

    fun launch(block: suspend CoroutineScope.() -> Unit): Job = activeScope().launch(block = block)

    suspend fun <T> run(block: suspend CoroutineScope.() -> T): T {
        val task = activeScope().async(block = block)
        return try { task.await() }
        finally { if (!currentCoroutineContext().isActive) task.cancel() }
    }

    fun cancel() {
        sessionScope = null
        sessionJob?.cancel()
    }

    suspend fun stop() {
        cancel()
        sessionJob?.join()
        sessionJob = null
    }

    private fun activeScope(): CoroutineScope = checkNotNull(sessionScope) { "Приложение заблокировано" }
}
