package com.majkeylab.seliadocs.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object LibraryMutationGate {
    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}
