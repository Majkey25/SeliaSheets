package com.majkeylab.seliadocs.recognition

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.CancellationException

class RecognitionModelManagerTest {
    @Test
    fun deleteWaitsForDownloadAndStaysBusyUntilDeletionCompletes() = runBlocking {
        val downloadStarted = CompletableDeferred<Unit>()
        val completeDownload = CompletableDeferred<Unit>()
        val deleteStarted = CompletableDeferred<Unit>()
        val completeDelete = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val manager = RecognitionModelManager(
            downloadModel = {
                calls += "download"
                downloadStarted.complete(Unit)
                completeDownload.await()
            },
            deleteModel = {
                calls += "delete"
                deleteStarted.complete(Unit)
                completeDelete.await()
            },
        )

        val download = async(start = CoroutineStart.UNDISPATCHED) {
            manager.download(RecognitionLanguage.CZECH)
        }
        downloadStarted.await()
        val delete = async(start = CoroutineStart.UNDISPATCHED) {
            manager.delete(RecognitionLanguage.CZECH)
        }

        assertEquals(listOf("download"), calls)
        assertEquals(RecognitionModelStatus.Downloading, manager.status.value)

        completeDownload.complete(Unit)
        assertEquals(RecognitionModelStatus.Ready, download.await())
        deleteStarted.await()
        assertEquals(listOf("download", "delete"), calls)
        assertEquals(RecognitionModelStatus.Deleting, manager.status.value)

        completeDelete.complete(Unit)
        assertEquals(RecognitionModelStatus.NotDownloaded, delete.await())
        assertEquals(RecognitionModelStatus.NotDownloaded, manager.status.value)
    }

    @Test
    fun downloadWaitsForSelectStatusRefresh() = runBlocking {
        val statusCheckStarted = CompletableDeferred<Unit>()
        val completeStatusCheck = CompletableDeferred<Boolean>()
        val downloadStarted = CompletableDeferred<Unit>()
        val manager = RecognitionModelManager(
            isModelDownloaded = {
                statusCheckStarted.complete(Unit)
                completeStatusCheck.await()
            },
            downloadModel = { downloadStarted.complete(Unit) },
        )

        val selection = async(start = CoroutineStart.UNDISPATCHED) {
            manager.select(RecognitionLanguage.CZECH)
        }
        statusCheckStarted.await()
        val download = async(start = CoroutineStart.UNDISPATCHED) {
            manager.download(RecognitionLanguage.CZECH)
        }

        assertEquals(RecognitionModelStatus.NotDownloaded, manager.status.value)
        assertEquals(false, downloadStarted.isCompleted)

        completeStatusCheck.complete(false)
        assertEquals(RecognitionModelStatus.NotDownloaded, selection.await())
        downloadStarted.await()
        assertEquals(RecognitionModelStatus.Ready, download.await())
        assertEquals(RecognitionModelStatus.Ready, manager.status.value)
    }

    @Test
    fun languageSwitchWaitsForPriorSelectAndKeepsNewestStatus() = runBlocking {
        val czechStatusCheckStarted = CompletableDeferred<Unit>()
        val completeCzechStatusCheck = CompletableDeferred<Boolean>()
        val englishStatusCheckStarted = CompletableDeferred<Unit>()
        val completeEnglishStatusCheck = CompletableDeferred<Boolean>()
        val manager = RecognitionModelManager(
            isModelDownloaded = { language ->
                when (language) {
                    RecognitionLanguage.CZECH -> {
                        czechStatusCheckStarted.complete(Unit)
                        completeCzechStatusCheck.await()
                    }
                    RecognitionLanguage.ENGLISH -> {
                        englishStatusCheckStarted.complete(Unit)
                        completeEnglishStatusCheck.await()
                    }
                }
            },
        )

        val czechSelection = async(start = CoroutineStart.UNDISPATCHED) {
            manager.select(RecognitionLanguage.CZECH)
        }
        czechStatusCheckStarted.await()
        val englishSelection = async(start = CoroutineStart.UNDISPATCHED) {
            manager.select(RecognitionLanguage.ENGLISH)
        }

        assertEquals(false, englishStatusCheckStarted.isCompleted)
        completeCzechStatusCheck.complete(true)
        assertEquals(RecognitionModelStatus.Ready, czechSelection.await())
        englishStatusCheckStarted.await()
        assertEquals(RecognitionModelStatus.NotDownloaded, manager.status.value)

        completeEnglishStatusCheck.complete(false)
        assertEquals(RecognitionModelStatus.NotDownloaded, englishSelection.await())
        assertEquals(RecognitionModelStatus.NotDownloaded, manager.status.value)
    }

    @Test
    fun cancelledDownloadRefreshesStatusBeforeRethrowing() = runBlocking {
        val downloadStarted = CompletableDeferred<Unit>()
        val neverCompletes = CompletableDeferred<Unit>()
        val manager = RecognitionModelManager(
            isModelDownloaded = { false },
            downloadModel = {
                downloadStarted.complete(Unit)
                neverCompletes.await()
            },
        )

        val download = async(start = CoroutineStart.UNDISPATCHED) {
            manager.download(RecognitionLanguage.CZECH)
        }
        downloadStarted.await()
        download.cancel()

        assertThrows(CancellationException::class.java) { runBlocking { download.await() } }
        assertEquals(RecognitionModelStatus.NotDownloaded, manager.status.value)
    }

    @Test
    fun failedDeleteUsesGenericMessage() = runBlocking {
        val manager = RecognitionModelManager(
            deleteModel = { throw IllegalStateException("backend detail") },
        )

        assertEquals(
            RecognitionModelStatus.Failed("Model deletion failed."),
            manager.delete(RecognitionLanguage.CZECH),
        )
    }

    @Test
    fun selectClearsPriorStatusWhileNextLanguageCheckIsPending() = runBlocking {
        val englishDownloaded = CompletableDeferred<Boolean>()
        val manager = RecognitionModelManager(
            isModelDownloaded = { language ->
                if (language == RecognitionLanguage.CZECH) true else englishDownloaded.await()
            },
        )

        assertEquals(RecognitionModelStatus.Ready, manager.select(RecognitionLanguage.CZECH))
        val selection = async(start = CoroutineStart.UNDISPATCHED) {
            manager.select(RecognitionLanguage.ENGLISH)
        }

        assertEquals(RecognitionModelStatus.NotDownloaded, manager.status.value)
        englishDownloaded.complete(false)
        assertEquals(RecognitionModelStatus.NotDownloaded, selection.await())
    }

    @Test
    fun downloadRethrowsCancellation() {
        val manager = RecognitionModelManager(
            isModelDownloaded = { false },
            downloadModel = { throw CancellationException("cancel") },
        )

        assertThrows(CancellationException::class.java) {
            runBlocking { manager.download(RecognitionLanguage.CZECH) }
        }
    }

    @Test
    fun deleteRethrowsCancellation() {
        val manager = RecognitionModelManager(
            isModelDownloaded = { true },
            deleteModel = { throw CancellationException("cancel") },
        )

        assertThrows(CancellationException::class.java) {
            runBlocking { manager.delete(RecognitionLanguage.CZECH) }
        }
    }

    @Test
    fun refreshRethrowsCancellation() {
        val manager = RecognitionModelManager(isModelDownloaded = { throw CancellationException("cancel") })

        assertThrows(CancellationException::class.java) {
            runBlocking { manager.select(RecognitionLanguage.CZECH) }
        }
    }
}
