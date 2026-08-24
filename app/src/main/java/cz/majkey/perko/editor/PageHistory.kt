package cz.majkey.perko.editor

internal class PageHistory<T>(initial: T, private val limit: Int = 100) {
    private val undo = ArrayDeque<T>()
    private val redo = ArrayDeque<T>()

    var current: T = initial
        private set

    val canUndo: Boolean
        get() = undo.isNotEmpty()

    val canRedo: Boolean
        get() = redo.isNotEmpty()

    init {
        require(limit > 0)
    }

    fun push(value: T) {
        undo.addLast(current)
        if (undo.size > limit) undo.removeFirst()
        current = value
        redo.clear()
    }

    fun undo(): T? {
        val previous = undo.removeLastOrNull() ?: return null
        redo.addLast(current)
        current = previous
        return current
    }

    fun redo(): T? {
        val next = redo.removeLastOrNull() ?: return null
        undo.addLast(current)
        current = next
        return current
    }
}
