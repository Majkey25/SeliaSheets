package com.majkeylab.seliadocs.editor

internal class PageHistory<T>(
    initial: T,
    private val limit: Int = 100,
    private val maxWeight: Int = Int.MAX_VALUE,
    private val weightOf: (T) -> Int = { 0 },
) {
    private data class Entry<T>(val value: T, val weight: Int)

    init {
        require(limit > 0 && maxWeight >= 0)
    }

    private val undo = ArrayDeque<Entry<T>>()
    private val redo = ArrayDeque<Entry<T>>()
    private var currentEntry = entry(initial)
    private var retainedWeight = currentEntry.weight.toLong()

    val current: T
        get() = currentEntry.value

    val canUndo: Boolean
        get() = undo.isNotEmpty()

    val canRedo: Boolean
        get() = redo.isNotEmpty()

    fun push(value: T) {
        val next = entry(value)
        clear(redo)
        undo.addLast(currentEntry)
        currentEntry = next
        retainedWeight += next.weight
        trimUndo()
    }

    fun undo(): T? {
        val previous = undo.removeLastOrNull() ?: return null
        redo.addLast(currentEntry)
        currentEntry = previous
        return current
    }

    suspend fun undo(apply: suspend (T) -> Unit): T? {
        val previous = undo() ?: return null
        try {
            apply(previous)
        } catch (failure: Throwable) {
            redo()
            throw failure
        }
        return previous
    }

    fun redo(): T? {
        val next = redo.removeLastOrNull() ?: return null
        undo.addLast(currentEntry)
        currentEntry = next
        return current
    }

    suspend fun redo(apply: suspend (T) -> Unit): T? {
        val next = redo() ?: return null
        try {
            apply(next)
        } catch (failure: Throwable) {
            undo()
            throw failure
        }
        return next
    }

    private fun entry(value: T): Entry<T> {
        val weight = weightOf(value)
        require(weight >= 0)
        return Entry(value, weight)
    }

    private fun clear(entries: ArrayDeque<Entry<T>>) {
        while (entries.isNotEmpty()) retainedWeight -= entries.removeLast().weight
    }

    private fun trimUndo() {
        while (undo.isNotEmpty() && (undo.size > limit || retainedWeight > maxWeight.toLong())) {
            retainedWeight -= undo.removeFirst().weight
        }
    }
}
