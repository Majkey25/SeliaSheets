package com.majkeylab.seliadocs.editor

internal class PageHistoryStore<T>(
    private val maxPages: Int = 10,
    private val stepsPerPage: Int = 100,
) {
    private val histories = LinkedHashMap<String, PageHistory<T>>(16, 0.75f, true)

    val size: Int
        get() = histories.size

    init {
        require(maxPages > 0 && stepsPerPage > 0)
    }

    fun history(pageId: String, initial: T): PageHistory<T> =
        histories[pageId]
            ?: PageHistory(initial, stepsPerPage).also { history ->
                histories[pageId] = history
                if (histories.size > maxPages) histories.remove(histories.entries.first().key)
            }

    fun existing(pageId: String): PageHistory<T>? = histories[pageId]

    fun remove(pageId: String) {
        histories.remove(pageId)
    }
}
