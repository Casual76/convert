package com.p2r3.convert.data.bridge

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.net.toUri
import androidx.webkit.WebViewAssetLoader
import com.p2r3.convert.BuildConfig
import com.p2r3.convert.data.engine.NativeCommonConversionEngine
import com.p2r3.convert.model.ConversionPreview
import com.p2r3.convert.model.ConversionRequest
import com.p2r3.convert.model.EngineDiagnostics
import com.p2r3.convert.model.EngineRuntimeKind
import com.p2r3.convert.model.FormatDescriptor
import com.p2r3.convert.model.PerformancePreset
import com.p2r3.convert.model.PreviewKind
import com.p2r3.convert.model.RoutePreview
import com.p2r3.convert.model.RouteStep
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

@Singleton
class WebViewCompatibilityBridge @Inject constructor(
    @ApplicationContext private val context: Context,
    private val nativeEngine: NativeCommonConversionEngine
) : CompatibilityBridge {

    private class BridgeMethodException(message: String) : IllegalStateException(message)

    private data class RegisteredInput(
        val uri: String,
        val fileName: String,
        val mimeType: String?
    )

    private data class OutputBuffer(
        val fileName: String,
        val mimeType: String,
        val bytes: ByteArrayOutputStream = ByteArrayOutputStream()
    )

    private data class PendingBridgeCall(
        val deferred: CompletableDeferred<JSONObject>,
        val outputs: MutableMap<Int, OutputBuffer> = linkedMapOf()
    )

    private val initMutex = Mutex()
    private val pendingCalls = ConcurrentHashMap<String, PendingBridgeCall>()
    private val registeredInputs = ConcurrentHashMap<String, RegisteredInput>()
    private var runtimeReady = CompletableDeferred<Unit>()
    private var webView: WebView? = null

    private val assetLoader by lazy {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .addPathHandler("/bridge-input/", BridgeInputPathHandler())
            .build()
    }

    override suspend fun loadCatalog(): List<FormatDescriptor> {
        val result = invokeBridge("loadCatalog")
        return result.optJSONArray("result")?.let(::parseFormats).orEmpty()
    }

    override suspend fun detectFormat(fileName: String?, mimeType: String?): FormatDescriptor? {
        val payload = JSONObject()
            .put("fileName", fileName)
            .put("mimeType", mimeType)
        val result = invokeBridge("detectFormat", payload)
        return result.optJSONObject("result")?.let(::parseFormat)
    }

    override suspend fun listTargets(sourceFormatId: String): List<FormatDescriptor> {
        val payload = JSONObject().put("sourceFormatId", sourceFormatId)
        val result = invokeBridge("listTargets", payload)
        return result.optJSONArray("result")?.let(::parseFormats).orEmpty()
    }

    override suspend fun planRoute(
        sourceFormatId: String,
        targetFormatId: String,
        fileCount: Int,
        totalBytes: Long,
        performancePreset: PerformancePreset,
        maxParallelJobs: Int,
        batteryFriendlyMode: Boolean
    ): RoutePreview? {
        val payload = basePolicyPayload(
            sourceFormatId = sourceFormatId,
            targetFormatId = targetFormatId,
            fileCount = fileCount,
            totalBytes = totalBytes,
            performancePreset = performancePreset,
            maxParallelJobs = maxParallelJobs,
            batteryFriendlyMode = batteryFriendlyMode
        )
        val result = invokeBridge("planRoute", payload)
        return result.optJSONObject("result")?.let(::parseRoutePreview)
    }

    override suspend fun generatePreview(
        inputUri: String,
        fileName: String?,
        mimeType: String?,
        sourceFormatId: String,
        targetFormatId: String,
        routeToken: String?,
        performancePreset: PerformancePreset,
        maxParallelJobs: Int,
        batteryFriendlyMode: Boolean,
        previewLimitBytes: Long
    ): ConversionPreview {
        val input = registerInput(inputUri, fileName ?: resolveDisplayName(inputUri.toUri()), mimeType)
        return try {
            val payload = basePolicyPayload(
                sourceFormatId = sourceFormatId,
                targetFormatId = targetFormatId,
                fileCount = 1,
                totalBytes = resolveFileSize(inputUri.toUri()),
                performancePreset = performancePreset,
                maxParallelJobs = maxParallelJobs,
                batteryFriendlyMode = batteryFriendlyMode
            ).put("routeToken", routeToken)
                .put("previewLimitBytes", previewLimitBytes)
                .put(
                    "input",
                    JSONObject()
                        .put("url", input["url"])
                        .put("name", input["name"])
                        .put("mimeType", input["mimeType"])
                )
            val result = invokeBridge("generatePreview", payload)
            parsePreview(result.optJSONObject("result"))
        } finally {
            unregisterInputs(listOf(input["token"].toString()))
        }
    }

    override suspend fun runConversion(request: ConversionRequest): BridgeRunResult {
        val registered = request.inputUris.map { uri ->
            registerInput(uri, resolveDisplayName(uri.toUri()), context.contentResolver.getType(uri.toUri()))
        }

        return try {
            val payload = basePolicyPayload(
                sourceFormatId = request.sourceFormatId ?: "",
                targetFormatId = request.targetFormatId,
                fileCount = registered.size,
                totalBytes = registered.sumOf { resolveFileSize(it["uri"].toString().toUri()) },
                performancePreset = request.performancePreset,
                maxParallelJobs = request.maxParallelJobs,
                batteryFriendlyMode = request.batteryFriendlyMode
            ).put("routeToken", request.routeToken)

            val inputs = JSONArray()
            registered.forEach { input ->
                inputs.put(
                    JSONObject()
                        .put("url", input["url"])
                        .put("name", input["name"])
                        .put("mimeType", input["mimeType"])
                )
            }
            payload.put("inputs", inputs)

            val callId = UUID.randomUUID().toString()
            val pending = PendingBridgeCall(CompletableDeferred())
            pendingCalls[callId] = pending
            payload.put("callId", callId)

            val result = invokeBridge("runConversion", payload, callId = callId, existingPending = pending)
            val savedOutputUris = pending.outputs.toSortedMap().values.map { output ->
                nativeEngine.saveOutput(
                    fileName = output.fileName,
                    mimeType = output.mimeType,
                    bytes = output.bytes.toByteArray(),
                    outputDirectoryUri = request.outputDirectoryUri
                ).toString()
            }

            BridgeRunResult(
                message = result.optJSONObject("result")?.optString("message")
                    ?: "Conversion completed in the compatibility runtime.",
                outputUris = savedOutputUris,
                routePreview = result.optJSONObject("result")
                    ?.optJSONObject("routePreview")
                    ?.let(::parseRoutePreview)
            )
        } finally {
            unregisterInputs(registered.map { it["token"].toString() })
        }
    }

    override suspend fun diagnostics(): EngineDiagnostics {
        val result = invokeBridge("diagnostics")
        return parseDiagnostics(result.optJSONObject("result"))
    }

    private suspend fun ensureRuntime() {
        runCatching {
            ensureRuntimeOnce()
        }.getOrElse { firstError ->
            resetRuntime("initialization failed", firstError)
            ensureRuntimeOnce()
        }
    }

    private suspend fun ensureRuntimeOnce() {
        if (runtimeReady.isCompleted && webView != null) return

        initMutex.withLock {
            if (runtimeReady.isCompleted && webView != null) return
            runtimeReady = CompletableDeferred()
            withContext(Dispatchers.Main) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && BuildConfig.DEBUG) {
                    WebView.setWebContentsDebuggingEnabled(true)
                }
                if (webView == null) {
                    webView = WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.mediaPlaybackRequiresUserGesture = false
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? {
                                return url?.toUri()?.let(assetLoader::shouldInterceptRequest)
                            }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: android.webkit.WebResourceRequest?
                            ): WebResourceResponse? {
                                return request?.url?.let(assetLoader::shouldInterceptRequest)
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: android.webkit.WebResourceRequest?,
                                error: android.webkit.WebResourceError?
                            ) {
                                super.onReceivedError(view, request, error)
                                if (request?.isForMainFrame == true && !runtimeReady.isCompleted) {
                                    runtimeReady.completeExceptionally(
                                        IllegalStateException(
                                            "Caricamento runtime legacy fallito: ${error?.description ?: "errore sconosciuto"}"
                                        )
                                    )
                                }
                            }
                        }
                        webChromeClient = object : android.webkit.WebChromeClient() {
                            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                                android.util.Log.d("LegacyBridge", consoleMessage.message())
                                return super.onConsoleMessage(consoleMessage)
                            }
                        }
                        addJavascriptInterface(RuntimeHost(), "AndroidBridgeHost")
                    }
                }
                webView?.loadUrl("https://appassets.androidplatform.net/assets/legacy/android-bridge.html")
            }
        }

        withTimeout(5.minutes) {
            runtimeReady.await()
        }
    }

    private suspend fun resetRuntime(reason: String, cause: Throwable? = null) {
        android.util.Log.w("LegacyBridge", "Resetting runtime: $reason", cause)
        pendingCalls.entries.forEach { entry ->
            entry.value.deferred.completeExceptionally(
                IllegalStateException("Runtime compatibile resettato: $reason", cause)
            )
        }
        pendingCalls.clear()
        runtimeReady = CompletableDeferred()
        withContext(Dispatchers.Main) {
            webView?.removeJavascriptInterface("AndroidBridgeHost")
            webView?.destroy()
            webView = null
        }
    }

    private suspend fun invokeBridge(
        method: String,
        payload: JSONObject? = null,
        callId: String = UUID.randomUUID().toString(),
        existingPending: PendingBridgeCall? = null
    ): JSONObject {
        val pending = existingPending ?: PendingBridgeCall(CompletableDeferred())
        try {
            ensureRuntime()
            pendingCalls[callId] = pending

            val payloadJson = payload?.toString() ?: ""
            withContext(Dispatchers.Main) {
                val runtime = webView ?: error("Runtime compatibile non disponibile.")
                runtime.evaluateJavascript(
                    "window.__androidBridgeHandle(${jsonLiteral(callId)}, ${jsonLiteral(method)}, ${jsonLiteral(payloadJson)});",
                    null
                )
            }

            val result = withTimeout(10.minutes) {
                pending.deferred.await()
            }
            if (!result.optBoolean("ok")) {
                val message = result.optJSONObject("result")?.optString("message")
                    ?: "Legacy bridge call failed."
                throw BridgeMethodException(message)
            }
            return result
        } catch (error: Throwable) {
            pendingCalls.remove(callId)
            if (error !is BridgeMethodException) {
                resetRuntime("bridge call $method failed", error)
            }
            val message = when (error) {
                is BridgeMethodException -> error.message
                is TimeoutCancellationException -> "Il motore di compatibilita non ha risposto in tempo durante $method."
                else -> buildString {
                    append("Il motore di compatibilita non e pronto per $method.")
                    error.message?.takeIf { it.isNotBlank() }?.let {
                        append(' ')
                        append(it)
                    }
                }
            } ?: "Legacy bridge call failed."
            throw IllegalStateException(message, error)
        }
    }

    private fun basePolicyPayload(
        sourceFormatId: String,
        targetFormatId: String,
        fileCount: Int,
        totalBytes: Long,
        performancePreset: PerformancePreset,
        maxParallelJobs: Int,
        batteryFriendlyMode: Boolean
    ): JSONObject = JSONObject()
        .put("sourceFormatId", sourceFormatId)
        .put("targetFormatId", targetFormatId)
        .put("fileCount", fileCount)
        .put("totalBytes", totalBytes)
        .put("performancePreset", when (performancePreset) {
            PerformancePreset.BATTERY -> "BATTERY"
            PerformancePreset.BALANCED -> "BALANCED"
            PerformancePreset.PERFORMANCE -> "PERFORMANCE"
        })
        .put("maxParallelJobs", maxParallelJobs)
        .put("batteryFriendlyMode", batteryFriendlyMode)

    private fun registerInput(uri: String, fileName: String, mimeType: String?): Map<String, String?> {
        val token = UUID.randomUUID().toString()
        registeredInputs[token] = RegisteredInput(
            uri = uri,
            fileName = fileName,
            mimeType = mimeType
        )
        return mapOf(
            "token" to token,
            "uri" to uri,
            "name" to fileName,
            "mimeType" to mimeType,
            "url" to "https://appassets.androidplatform.net/bridge-input/$token/${Uri.encode(fileName)}"
        )
    }

    private fun unregisterInputs(tokens: List<String>) {
        tokens.forEach(registeredInputs::remove)
    }

    private fun parseFormats(array: JSONArray): List<FormatDescriptor> =
        buildList {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let { add(parseFormat(it)) }
            }
        }

    private fun parseFormat(json: JSONObject): FormatDescriptor = FormatDescriptor(
        id = json.optString("id"),
        displayName = json.optString("displayName"),
        shortLabel = json.optString("shortLabel"),
        extension = json.optString("extension"),
        mimeType = json.optString("mimeType"),
        categories = buildList {
            val categories = json.optJSONArray("categories")
            if (categories != null) {
                for (index in 0 until categories.length()) {
                    add(categories.optString(index))
                }
            }
        },
        supportsInput = json.optBoolean("supportsInput"),
        supportsOutput = json.optBoolean("supportsOutput"),
        handlerName = json.optString("handlerName"),
        lossless = json.optBoolean("lossless"),
        availableRuntimeKinds = buildList {
            val kinds = json.optJSONArray("availableRuntimeKinds")
            if (kinds != null) {
                for (index in 0 until kinds.length()) {
                    add(enumValueOf<EngineRuntimeKind>(kinds.optString(index)))
                }
            } else {
                add(EngineRuntimeKind.BRIDGE)
            }
        },
        nativePreferred = json.optBoolean("nativePreferred", false)
    )

    private fun parseRoutePreview(json: JSONObject): RoutePreview = RoutePreview(
        routeKey = json.optString("routeKey"),
        routeToken = json.optString("routeToken").takeIf { it.isNotBlank() },
        steps = buildList {
            val steps = json.optJSONArray("steps")
            if (steps != null) {
                for (index in 0 until steps.length()) {
                    steps.optJSONObject(index)?.let { step ->
                        add(
                            RouteStep(
                                fromFormatId = step.optString("fromFormatId"),
                                toFormatId = step.optString("toFormatId"),
                                handlerName = step.optString("handlerName"),
                                performanceClass = step.optString("performanceClass"),
                                batchStrategy = step.optString("batchStrategy"),
                                note = step.optString("note"),
                                runtimeKind = enumValueOf(step.optString("runtimeKind", EngineRuntimeKind.BRIDGE.name))
                            )
                        )
                    }
                }
            }
        },
        etaLabel = json.optString("etaLabel"),
        cpuImpactLabel = json.optString("cpuImpactLabel"),
        confidenceLabel = json.optString("confidenceLabel"),
        reasons = buildList {
            val reasons = json.optJSONArray("reasons")
            if (reasons != null) {
                for (index in 0 until reasons.length()) {
                    add(reasons.optString(index))
                }
            }
        },
        previewSupported = json.optBoolean("previewSupported"),
        runtimeKind = enumValueOf(json.optString("runtimeKind", EngineRuntimeKind.BRIDGE.name))
    )

    private fun parsePreview(json: JSONObject?): ConversionPreview {
        if (json == null) {
            return ConversionPreview(
                supported = false,
                kind = PreviewKind.NONE,
                headline = "Preview unavailable",
                body = "The compatibility runtime did not return preview metadata."
            )
        }
        return ConversionPreview(
            supported = json.optBoolean("supported"),
            kind = enumValueOf(json.optString("kind", PreviewKind.NONE.name)),
            headline = json.optString("headline"),
            body = json.optString("body"),
            proxyUri = json.optString("proxyUri").takeIf { it.isNotBlank() },
            textPreview = json.optString("textPreview").takeIf { it.isNotBlank() }
        )
    }

    private fun parseDiagnostics(json: JSONObject?): EngineDiagnostics {
        return EngineDiagnostics(
            runtimeKind = EngineRuntimeKind.BRIDGE,
            catalogFormatCount = json?.optInt("catalogFormatCount") ?: 0,
            inputFormatCount = json?.optInt("inputFormatCount") ?: 0,
            outputFormatCount = json?.optInt("outputFormatCount") ?: 0,
            handlerCount = json?.optInt("handlerCount") ?: 0,
            disabledHandlers = buildList {
                val array = json?.optJSONArray("disabledHandlers")
                if (array != null) {
                    for (index in 0 until array.length()) {
                        add(array.optString(index))
                    }
                }
            },
            cacheSource = json?.optString("cacheSource")
        )
    }

    private fun resolveDisplayName(uri: Uri): String {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            return uri.lastPathSegment ?: "input-file"
        }
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index != -1 && cursor.moveToFirst()) {
                return cursor.getString(index)
            }
        }
        return uri.lastPathSegment ?: "input-file"
    }

    private fun resolveFileSize(uri: Uri): Long {
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
            if (index != -1 && cursor.moveToFirst()) {
                return cursor.getLong(index)
            }
        }
        return 0L
    }

    private fun jsonLiteral(value: String): String = JSONObject.quote(value)

    private inner class RuntimeHost {
        @JavascriptInterface
        fun onRuntimeReady() {
            runtimeReady.complete(Unit)
        }

        @JavascriptInterface
        fun deliverResult(callId: String, payloadJson: String) {
            val payload = JSONObject(payloadJson)
            pendingCalls.remove(callId)?.deferred?.complete(payload)
        }

        @JavascriptInterface
        fun emitOutputChunk(
            callId: String,
            fileIndex: Int,
            fileName: String,
            mimeType: String,
            chunkIndex: Int,
            isLastChunk: Boolean,
            base64Chunk: String
        ) {
            val pending = pendingCalls[callId] ?: return
            val buffer = pending.outputs.getOrPut(fileIndex) {
                OutputBuffer(fileName = fileName, mimeType = mimeType)
            }
            val bytes = Base64.decode(base64Chunk, Base64.DEFAULT)
            buffer.bytes.write(bytes)
        }
    }

    private inner class BridgeInputPathHandler : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse? {
            val sanitized = path.removePrefix("/")
            val token = sanitized.substringBefore('/')
            val registered = registeredInputs[token] ?: return null
            return try {
                val inputStream = context.contentResolver.openInputStream(registered.uri.toUri())
                    ?: throw FileNotFoundException(registered.uri)
                WebResourceResponse(
                    registered.mimeType ?: "application/octet-stream",
                    null,
                    inputStream
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
