package com.example.bluetooth_messenger.security

import com.example.bluetooth_messenger.core.coroutines.SessionTasks
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.bluetooth_messenger.data.local.database.DatabaseSession
import com.example.bluetooth_messenger.data.security.LocalDataResetter
import com.example.bluetooth_messenger.data.security.PinVault
import com.example.bluetooth_messenger.data.session.AndroidSessionController
import com.example.bluetooth_messenger.domain.repository.AppSettings
import com.example.bluetooth_messenger.domain.session.*
import com.example.bluetooth_messenger.support.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionControllerTest {
    private val context = ScopedContext(InstrumentationRegistry.getInstrumentation().targetContext)
    private val vault = PinVault(context)
    private val database = DatabaseSession(context)
    private val identity = TestIdentity()
    private val radio = TestRadio()
    private val clock = TestClock()
    private lateinit var scope: CoroutineScope
    private lateinit var tasks: SessionTasks
    private lateinit var controller: AndroidSessionController

    @Before fun create() = runBlocking<Unit> {
        withContext(Dispatchers.Main) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            tasks = SessionTasks(scope)
            controller = AndroidSessionController(context, vault, identity, identity, database, radio, radio,
                LocalDataResetter(context, vault), TestSettings(AppSettings(timeoutMinutes = 1)), clock, tasks, scope)
        }
        withTimeout(5000) { controller.state.first { it == SessionState.Onboarding } }
    }
    @After fun cleanup() = runBlocking<Unit> {
        scope.cancel()
        withContext(Dispatchers.IO) { vault.lock() }
        database.close()
        context.getSharedPreferences("vault", 0).edit().clear().commit()
        context.deleteDatabase("offgrid.db")
    }
    private suspend fun setup() = withContext(Dispatchers.Main) { controller.setup("123456".toCharArray()) }

    @Test fun wrongPinCannotOpenDatabaseAfterBackground() = runBlocking<Unit> {
        setup()
        assertEquals(SessionState.Ready, controller.state.value)
        withContext(Dispatchers.Main) { controller.background() }
        withTimeout(5000) { database.database.first { it == null } }
        withContext(Dispatchers.Main) { controller.foreground(); controller.unlock("654321".toCharArray()) }
        assertEquals(SessionState.Locked(UnlockMethod.Pin), controller.state.value)
        assertNull(database.database.value)
        assertThrows(IllegalStateException::class.java) { vault.keyCopy() }
    }
    @Test fun foregroundIdleLockCanBeUnlockedWithoutActivityRestart() = runBlocking<Unit> {
        setup()
        clock.elapsed = 60_001
        withTimeout(5000) { controller.state.first { it == SessionState.Locked(UnlockMethod.Pin) } }
        withContext(Dispatchers.Main) { controller.unlock("123456".toCharArray()) }
        assertEquals(SessionState.Ready, controller.state.value)
    }
    @Test fun backgroundDuringInitializationCannotCompleteUnlock() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        identity.generationGate = gate
        val pending = scope.launch { controller.setup("123456".toCharArray()) }
        withTimeout(5000) { controller.state.first { it == SessionState.Checking } }
        withContext(Dispatchers.Main) { controller.background() }
        gate.complete(Unit)
        pending.join()
        assertNotEquals(SessionState.Ready, controller.state.value)
        assertNull(database.database.value)
    }
    @Test fun backgroundCancelsSessionWorkBeforeDatabaseCloses() = runBlocking<Unit> {
        setup()
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        tasks.launch { started.complete(Unit); try { awaitCancellation() } finally { stopped.complete(Unit) } }
        started.await()
        withContext(Dispatchers.Main) { controller.background() }
        withTimeout(5000) { database.database.first { it == null } }
        assertTrue(stopped.isCompleted)
        assertThrows(IllegalStateException::class.java) { tasks.launch { } }
    }

    @Test fun unavailableRadioKeepsUnlockedLocalDataAccessible() = runBlocking<Unit> {
        radio.startFailure = IllegalStateException("test radio unavailable")
        setup()
        withTimeout(5000) { controller.networkError.first { it == "test radio unavailable" } }
        assertEquals(SessionState.Ready, controller.state.value)
        assertTrue(database.use { it.isOpen })
    }
}
