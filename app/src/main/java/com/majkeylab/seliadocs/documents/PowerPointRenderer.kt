package com.majkeylab.seliadocs.documents

import android.annotation.SuppressLint
import android.graphics.pdf.PdfDocument
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.majkeylab.seliadocs.data.MAX_PDF_IMPORT_BYTES
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** A private, network-disabled WebView. Untrusted Office content never receives a native JS bridge. */
internal object PowerPointRenderer {
    private const val ORIGIN = "https://presentation.invalid/"
    private const val BATCH_PIXELS = 8_000_000

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun render(host: ViewGroup, source: File, output: File, info: PowerPointMetadata): List<File> =
        withContext(Dispatchers.Main) {
            withTimeout(120_000) {
                check(host.isAttachedToWindow) { "Reopen the notebook to import slides" }
                val context = host.context
                val webView = WebView(context)
                val rendererFailure = CompletableDeferred<Unit>()
                val crashWatcher = launch {
                    rendererFailure.await()
                    throw IOException("Presentation renderer stopped. Export this presentation as PDF first.")
                }
                val outputs = mutableListOf<File>()
                var completed = false
                val renderJob = currentCoroutineContext().job
                val attachment = object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(view: View) = Unit
                    override fun onViewDetachedFromWindow(view: View) { renderJob.cancel() }
                }
                try {
                    webView.settings.apply {
                        javaScriptEnabled = true
                        allowFileAccess = false
                        allowContentAccess = false
                        blockNetworkLoads = true
                        domStorageEnabled = false
                        cacheMode = WebSettings.LOAD_NO_CACHE
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        setSupportMultipleWindows(false)
                        javaScriptCanOpenWindowsAutomatically = false
                        offscreenPreRaster = true
                    }
                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                            val path = request.url.toString().removePrefix(ORIGIN)
                            if (request.method == "GET" && request.url.toString().startsWith(ORIGIN)) {
                                val mime = when (path) {
                                    "index.html" -> "text/html"
                                    "import.js", "renderer.js" -> "text/javascript"
                                    "source.pptx" -> "application/octet-stream"
                                    else -> null
                                }
                                if (mime != null) return try {
                                    WebResourceResponse(mime, "UTF-8", 200, "OK", mapOf("Cache-Control" to "no-store"),
                                        if (path == "source.pptx") source.inputStream()
                                        else context.assets.open("presentation/$path"))
                                } catch (_: IOException) {
                                    blocked()
                                }
                            }
                            return blocked()
                        }

                        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                            rendererFailure.complete(Unit)
                            return true
                        }
                    }
                    // Up to 192 dpi, bounded by one 8 MP batch and a 3072 px edge.
                    val scalePercent = minOf(200.0, 307200.0 / maxOf(info.widthCssPx, info.heightCssPx),
                        sqrt(BATCH_PIXELS / (info.widthCssPx * info.heightCssPx)) * 100).toInt().coerceAtLeast(1)
                    val scale = scalePercent / 100.0
                    webView.setInitialScale(scalePercent)
                    webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    webView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    webView.isFocusable = false
                    webView.isClickable = false
                    val width = ceil(info.widthCssPx * scale).toInt()
                    val height = ceil(info.heightCssPx * scale).toInt()
                    val pagesPerBatch = (BATCH_PIXELS / (width.toLong() * height)).toInt().coerceAtLeast(1)
                    // Behind the editor; attached capture is part of WebView's visual-state contract.
                    host.addView(webView, 0, ViewGroup.LayoutParams(width, height))
                    webView.addOnAttachStateChangeListener(attachment)
                    webView.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    webView.layout(0, 0, width, height)
                    webView.loadUrl(ORIGIN + "index.html")
                    suspend fun awaitState(expected: String): JSONObject {
                        while (true) {
                            val status = JSONObject(evaluate(webView, "JSON.stringify(window.presentationResult || {state:'loading'})"))
                            when (status.getString("state")) {
                                "error" -> throw IOException(status.optString("message", "Presentation could not be rendered"))
                                expected -> return status
                            }
                            delay(50)
                        }
                    }
                    val loaded = awaitState("loaded")
                    check(loaded.getInt("count") == info.slideCount) { "Slide count changed during conversion" }
                    check(kotlin.math.abs(loaded.getDouble("width") - info.widthCssPx) < 1 &&
                        kotlin.math.abs(loaded.getDouble("height") - info.heightCssPx) < 1) { "Slide dimensions changed during conversion" }
                    var writtenBytes = 0L
                    for (first in 0 until info.slideCount step pagesPerBatch) {
                        val document = PdfDocument()
                        try {
                            for (index in first until minOf(first + pagesPerBatch, info.slideCount)) {
                                webView.evaluateJavascript("window.renderPresentationSlide($index);", null)
                                check(awaitState("ready").getInt("index") == index) { "Slide order changed during conversion" }
                                visualReady(webView, index.toLong())
                                val page = document.startPage(PdfDocument.PageInfo.Builder(
                                    (info.widthCssPx * 0.75).roundToInt(), (info.heightCssPx * 0.75).roundToInt(), index - first + 1).create())
                                try {
                                    page.canvas.scale((0.75 / scale).toFloat(), (0.75 / scale).toFloat())
                                    webView.draw(page.canvas)
                                } finally {
                                    document.finishPage(page)
                                }
                            }
                            val destination = if (outputs.isEmpty()) output else File(output.parentFile, "${output.nameWithoutExtension}-${outputs.size + 1}.pdf")
                            check(document.pages.size == minOf(pagesPerBatch, info.slideCount - first)) { "Presentation conversion lost a slide" }
                            check(!destination.exists()) { "Presentation output already exists" }
                            outputs += destination
                            writtenBytes += writeBounded(document, destination, MAX_PDF_IMPORT_BYTES - writtenBytes)
                        } finally {
                            document.close()
                        }
                    }
                    completed = true
                    outputs.toList()
                } finally {
                    crashWatcher.cancel()
                    webView.removeOnAttachStateChangeListener(attachment)
                    host.removeView(webView)
                    webView.stopLoading()
                    webView.destroy()
                    if (!completed) withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { outputs.forEach { it.delete() } }
                }
            }
        }

    private suspend fun writeBounded(document: PdfDocument, file: File, limit: Long): Long = withContext(Dispatchers.IO) {
        val coroutine = currentCoroutineContext()
        var count = 0L
        file.outputStream().use { destination ->
            val bounded = object : FilterOutputStream(destination) {
                override fun write(value: Int) {
                    coroutine.ensureActive()
                    if (count >= limit) throw IOException("Converted presentation exceeds 256 MiB")
                    out.write(value)
                    count++
                }
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    coroutine.ensureActive()
                    if (length > limit - count) throw IOException("Converted presentation exceeds 256 MiB")
                    out.write(bytes, offset, length)
                    count += length
                }
            }
            document.writeTo(bounded)
        }
        count
    }

    private fun blocked() = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private suspend fun evaluate(view: WebView, script: String): String = suspendCancellableCoroutine { continuation ->
        view.evaluateJavascript(script) { json ->
            if (continuation.isActive) {
                try {
                    continuation.resume(org.json.JSONTokener(json).nextValue() as String)
                } catch (error: Exception) {
                    continuation.resumeWithException(IOException("Presentation renderer returned an invalid result", error))
                }
            }
        }
    }

    private suspend fun visualReady(view: WebView, index: Long) = suspendCancellableCoroutine { continuation ->
        view.postVisualStateCallback(index, object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) {
                if (continuation.isActive) continuation.resume(Unit)
            }
        })
    }
}
