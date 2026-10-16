package com.mojaloss.core.data

import com.mojaloss.core.model.FavoriteState
import com.mojaloss.core.model.OperationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Desired-state writes, serialized per title. Read-back resolves a lost write response. */
internal class FavoriteTransactions {
    private val mutable = MutableStateFlow<Map<String, FavoriteState>>(emptyMap())
    val states: StateFlow<Map<String, FavoriteState>> = mutable.asStateFlow()
    private val sequence = AtomicLong()
    private val locks = ConcurrentHashMap<String, Mutex>()
    val version: Long get() = sequence.get()

    fun clear() { sequence.incrementAndGet(); mutable.value = emptyMap(); locks.clear() }
    fun seed(id: String, confirmed: Boolean, observedBefore: Long = Long.MAX_VALUE) {
        mutable.update { values ->
            val old = values[id]
            if (old?.pending == true || (old?.operation ?: 0) > observedBefore) values
            else values + (id to FavoriteState(confirmed, confirmed, operation = old?.operation ?: 0))
        }
    }

    suspend fun set(
        id: String,
        desired: Boolean,
        current: () -> Boolean,
        write: suspend (Boolean) -> Unit,
        read: suspend () -> Boolean,
        publish: ((() -> Unit) -> Boolean) = { action -> if (current()) { action(); true } else false }
    ): OperationResult<Boolean> {
        val operation = sequence.incrementAndGet()
        if (!current()) return OperationResult.Failure("Your account changed. Try again.")
        if (!publish { mutable.update { values -> values + (id to (values[id] ?: FavoriteState()).copy(
            desired = desired, pending = true, operation = operation, error = null
        )) } }) return OperationResult.Failure("Your account changed. Try again.")
        try { return locks.getOrPut(id) { Mutex() }.withLock {
            if (!current() || mutable.value[id]?.operation != operation)
                return@withLock OperationResult.Failure("A newer choice replaced this action.")
            var writeFailed = false
            try { write(desired) } catch (e: CancellationException) { throw e }
            catch (_: Exception) { writeFailed = true }
            if (!current()) return@withLock OperationResult.Failure("Your account changed. Try again.")
            val confirmed = try { read() } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                finish(id, operation, current, publish, error = "Could not confirm your change. Try again.")
                return@withLock OperationResult.Failure("Could not confirm your change. Try again.")
            }
            if (!current()) return@withLock OperationResult.Failure("Your account changed. Try again.")
            // A newer queued intent keeps its own pending/desired state while learning this read-back.
            publish { mutable.update { values ->
                val old = values[id] ?: return@update values
                if (!current()) values else values + (id to old.copy(confirmed = confirmed))
            } }
            if (mutable.value[id]?.operation != operation)
                return@withLock OperationResult.Failure("A newer choice replaced this action.")
            if (confirmed == desired) {
                finish(id, operation, current, publish, confirmed = confirmed)
                OperationResult.Success(confirmed)
            } else {
                val message = if (writeFailed) "Your change was not saved. Try again." else "The service did not confirm your change."
                finish(id, operation, current, publish, confirmed = confirmed, error = message)
                OperationResult.Failure(message)
            }
        } } catch (e: CancellationException) {
            finish(id, operation, current, publish, error = "Confirmation was interrupted. Refresh before trying again.")
            throw e
        }
    }

    private fun finish(id: String, operation: Long, current: () -> Boolean, publish: ((() -> Unit) -> Boolean), confirmed: Boolean? = null, error: String? = null) {
        publish { mutable.update { values ->
            val old = values[id]
            if (!current() || old == null || old.operation != operation) values
            else values + (id to old.copy(confirmed = confirmed ?: old.confirmed, pending = false, error = error))
        } }
    }
}
