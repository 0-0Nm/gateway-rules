package com.aqi.ccb.data

import com.aqi.ccb.BuildConfig
import org.json.JSONObject

/** Immutable application build provenance; independent of MCP protocol and config revisions. */
object BuildInfo {
    val versionName: String get() = BuildConfig.VERSION_NAME
    val versionCode: Int get() = BuildConfig.VERSION_CODE
    val sourceCommit: String get() = BuildConfig.BUILD_SOURCE_COMMIT
    val sequence: Int get() = BuildConfig.BUILD_SEQUENCE
    val runId: String get() = BuildConfig.BUILD_RUN_ID
    val kind: String get() = BuildConfig.BUILD_KIND
    fun json(): JSONObject = JSONObject().put("versionName",versionName).put("versionCode",versionCode)
        .put("sourceCommit",sourceCommit).put("buildSequence",sequence).put("workflowRunId",runId)
        .put("ciSequence",BuildConfig.BUILD_CI_SEQUENCE).put("workflowRunAttempt",BuildConfig.BUILD_RUN_ATTEMPT).put("buildKind",kind)
        .put("versionRule","主.次.本.末").put("major",BuildConfig.VERSION_MAJOR)
        .put("minor",BuildConfig.VERSION_MINOR).put("validationRound",BuildConfig.VERSION_VALIDATION)
}
