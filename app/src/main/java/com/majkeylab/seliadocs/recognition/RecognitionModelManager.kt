package com.majkeylab.seliadocs.recognition

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class RecognitionModelManager(
    private val isModelDownloaded: suspend (RecognitionLanguage) -> Boolean = { language ->
        RemoteModelManager.getInstance().isModelDownloaded(digitalInkModel(language)).awaitTask()
    },
    private val downloadModel: suspend (RecognitionLanguage) -> Unit = { language ->
        RemoteModelManager.getInstance().download(
            digitalInkModel(language),
            DownloadConditions.Builder().build(),
        ).awaitTask()
    },
    private val deleteModel: suspend (RecognitionLanguage) -> Unit = { language ->
        RemoteModelManager.getInstance().deleteDownloadedModel(digitalInkModel(language)).awaitTask()
    },
    private val recognizerFactory: (RecognitionLanguage) -> InkTextRecognizer = ::MlKitInkTextRecognizer,
) {
    private val statusMutex = Mutex()
    private val mutableStatus = MutableStateFlow<RecognitionModelStatus>(RecognitionModelStatus.NotDownloaded)

    val status: StateFlow<RecognitionModelStatus> = mutableStatus.asStateFlow()

    suspend fun select(language: RecognitionLanguage): RecognitionModelStatus = statusMutex.withLock {
        mutableStatus.value = RecognitionModelStatus.NotDownloaded
        try {
            refresh(language)
        } catch (error: CancellationException) {
            refreshAfterCancellation(language)
            throw error
        }
    }

    suspend fun download(language: RecognitionLanguage): RecognitionModelStatus = statusMutex.withLock {
        mutableStatus.value = RecognitionModelStatus.Downloading
        try {
            downloadModel(language)
            update(RecognitionModelStatus.Ready)
        } catch (error: CancellationException) {
            refreshAfterCancellation(language)
            throw error
        } catch (error: Exception) {
            update(RecognitionModelStatus.Failed("Model download failed."))
        }
    }

    suspend fun delete(language: RecognitionLanguage): RecognitionModelStatus = statusMutex.withLock {
        mutableStatus.value = RecognitionModelStatus.Deleting
        try {
            deleteModel(language)
            update(RecognitionModelStatus.NotDownloaded)
        } catch (error: CancellationException) {
            refreshAfterCancellation(language)
            throw error
        } catch (error: Exception) {
            update(RecognitionModelStatus.Failed("Model deletion failed."))
        }
    }

    suspend fun createRecognizer(language: RecognitionLanguage): InkTextRecognizer {
        check(isModelDownloaded(language)) {
            "Digital ink model for ${language.tag} is not downloaded."
        }
        return recognizerFactory(language)
    }

    private suspend fun refresh(language: RecognitionLanguage): RecognitionModelStatus {
        return try {
            val status = if (isModelDownloaded(language)) {
                RecognitionModelStatus.Ready
            } else {
                RecognitionModelStatus.NotDownloaded
            }
            update(status)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            update(RecognitionModelStatus.Failed("Model status check failed."))
        }
    }

    private suspend fun refreshAfterCancellation(language: RecognitionLanguage) {
        withContext(NonCancellable) {
            try {
                refresh(language)
            } catch (_: CancellationException) {
                update(RecognitionModelStatus.NotDownloaded)
            }
        }
    }

    private fun update(status: RecognitionModelStatus): RecognitionModelStatus {
        mutableStatus.value = status
        return status
    }
}
