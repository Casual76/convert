package com.p2r3.convert.data.jobs

import com.p2r3.convert.model.ConversionRequest
import com.p2r3.convert.model.OutputNamingPolicy
import com.p2r3.convert.model.PerformancePreset
import org.json.JSONArray
import org.json.JSONObject

fun serializeConversionRequest(request: ConversionRequest): String {
    val json = JSONObject()
        .put("sourceFormatId", request.sourceFormatId)
        .put("targetFormatId", request.targetFormatId)
        .put("presetId", request.presetId)
        .put("outputDirectoryUri", request.outputDirectoryUri)
        .put("outputNamingPolicy", request.outputNamingPolicy.name)
        .put("performancePreset", request.performancePreset.name)
        .put("previewMode", request.previewMode)
        .put("routeToken", request.routeToken)
        .put("allowBridgeFallback", request.allowBridgeFallback)
        .put("maxParallelJobs", request.maxParallelJobs)
        .put("batteryFriendlyMode", request.batteryFriendlyMode)
        .put("autoOpenResult", request.autoOpenResult)
        .put("previewLimitBytes", request.previewLimitBytes)

    val inputUris = JSONArray()
    request.inputUris.forEach(inputUris::put)
    json.put("inputUris", inputUris)
    return json.toString()
}

fun deserializeConversionRequest(raw: String): ConversionRequest {
    val json = JSONObject(raw)
    val inputUris = buildList {
        val array = json.optJSONArray("inputUris")
        if (array != null) {
            for (index in 0 until array.length()) {
                add(array.optString(index))
            }
        }
    }
    return ConversionRequest(
        inputUris = inputUris,
        sourceFormatId = json.optString("sourceFormatId").takeIf { it.isNotBlank() },
        targetFormatId = json.optString("targetFormatId"),
        presetId = json.optString("presetId").takeIf { it.isNotBlank() },
        outputDirectoryUri = json.optString("outputDirectoryUri").takeIf { it.isNotBlank() },
        outputNamingPolicy = enumValueOf(json.optString("outputNamingPolicy", OutputNamingPolicy.APPEND_TARGET.name)),
        performancePreset = enumValueOf(json.optString("performancePreset", PerformancePreset.BALANCED.name)),
        previewMode = json.optBoolean("previewMode"),
        routeToken = json.optString("routeToken").takeIf { it.isNotBlank() },
        allowBridgeFallback = json.optBoolean("allowBridgeFallback", true),
        maxParallelJobs = json.optInt("maxParallelJobs", 2),
        batteryFriendlyMode = json.optBoolean("batteryFriendlyMode", true),
        autoOpenResult = json.optBoolean("autoOpenResult", false),
        previewLimitBytes = json.optLong("previewLimitBytes", 10L * 1024L * 1024L)
    )
}
