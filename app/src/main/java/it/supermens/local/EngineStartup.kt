// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.

package it.supermens.local

/** A cleanup error must never hide initialization failure or prevent the next backend. */
internal object EngineStartup {
    fun <C, E> initialize(configs: List<C>, create: (C) -> E, initialize: (E) -> Unit,
                         close: (E) -> Unit, onFailure: (Int, Throwable) -> Unit = { _, _ -> }): Pair<E, Int> {
        var lastError: Throwable? = null
        configs.forEachIndexed { index, config ->
            var candidate: E? = null
            try {
                val created = create(config)
                candidate = created
                initialize(created)
                return created to index
            } catch (error: Throwable) {
                candidate?.let { runCatching { close(it) }.exceptionOrNull()?.let(error::addSuppressed) }
                onFailure(index, error)
                lastError = error
            }
        }
        throw IllegalStateException(lastError?.message ?: "No inference backend available", lastError)
    }
}
