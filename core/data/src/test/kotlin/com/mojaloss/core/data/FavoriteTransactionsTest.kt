package com.mojaloss.core.data

import com.mojaloss.core.model.OperationResult
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class FavoriteTransactionsTest {
    @Test fun lostWriteResponseIsResolvedByReadBackWithoutAnotherWrite() = runBlocking {
        val values = FavoriteTransactions()
        val writes = AtomicInteger()
        var server = false
        values.seed("movie", false)
        val result = values.set("movie", true, { true }, write = {
            writes.incrementAndGet(); server = it; throw IOException("Response lost")
        }, read = { server })
        assertEquals(OperationResult.Success(true), result)
        assertEquals(1, writes.get())
        assertTrue(values.states.value.getValue("movie").confirmed)
        assertFalse(values.states.value.getValue("movie").pending)
    }

    @Test fun newerRemoveWaitsForSaveAndCannotBeOverwrittenByItsDelayedResponse() = runBlocking {
        withTimeout(5_000) {
            val values = FavoriteTransactions()
            val saveStarted = CompletableDeferred<Unit>()
            val finishSave = CompletableDeferred<Unit>()
            var server = false
            val save = async { values.set("movie", true, { true }, write = {
                saveStarted.complete(Unit); finishSave.await(); server = it
            }, read = { server }) }
            saveStarted.await()
            val remove = async(start = CoroutineStart.UNDISPATCHED) {
                values.set("movie", false, { true }, write = { server = it }, read = { server })
            }
            assertTrue(values.states.value.getValue("movie").pending)
            assertFalse(values.states.value.getValue("movie").desired)
            finishSave.complete(Unit)
            assertTrue(save.await() is OperationResult.Failure)
            assertEquals(OperationResult.Success(false), remove.await())
            assertFalse(server)
            assertFalse(values.states.value.getValue("movie").confirmed)
            assertFalse(values.states.value.getValue("movie").pending)
        }
    }

    @Test fun accountChangeDiscardsLateReadBack() = runBlocking {
        withTimeout(5_000) {
            val values = FavoriteTransactions()
            val readStarted = CompletableDeferred<Unit>()
            val finishRead = CompletableDeferred<Unit>()
            var generation = 1
            val oldAccount = async { values.set("movie", true, { generation == 1 }, write = {}, read = {
                readStarted.complete(Unit); finishRead.await(); true
            }) }
            readStarted.await()
            generation = 2
            values.clear()
            values.seed("other-account-title", false)
            finishRead.complete(Unit)
            assertTrue(oldAccount.await() is OperationResult.Failure)
            assertFalse(values.states.value.containsKey("movie"))
            assertFalse(values.states.value.getValue("other-account-title").confirmed)
        }
    }

    @Test fun catalogReadStartedBeforeSaveCannotRevertConfirmedFavorite() = runBlocking {
        val values = FavoriteTransactions()
        values.seed("movie", false)
        val catalogVersion = values.version
        values.set("movie", true, { true }, write = {}, read = { true })
        values.seed("movie", false, observedBefore = catalogVersion)
        assertTrue(values.states.value.getValue("movie").confirmed)
    }

    @Test fun interruptedConfirmationStopsPendingButDoesNotPretendSuccess() = runBlocking {
        withTimeout(5_000) {
            val values = FavoriteTransactions()
            val started = CompletableDeferred<Unit>()
            val action = launch {
                values.set("movie", true, { true }, write = { started.complete(Unit); awaitCancellation() }, read = { true })
            }
            started.await()
            action.cancelAndJoin()
            val state = values.states.value.getValue("movie")
            assertFalse(state.pending)
            assertFalse(state.confirmed)
            assertNotNull(state.error)
        }
    }
}
