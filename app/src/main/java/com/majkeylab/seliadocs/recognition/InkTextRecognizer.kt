package com.majkeylab.seliadocs.recognition

internal enum class RecognitionLanguage(val tag: String) {
    CZECH("cs"),
    ENGLISH("en-US"),
}

internal sealed interface RecognitionModelStatus {
    data object NotDownloaded : RecognitionModelStatus

    data object Downloading : RecognitionModelStatus

    data object Deleting : RecognitionModelStatus

    data object Ready : RecognitionModelStatus

    data class Failed(val message: String) : RecognitionModelStatus
}

internal interface InkTextRecognizer : AutoCloseable {
    suspend fun recognize(request: RecognitionRequest): List<RecognitionCandidate>
}
