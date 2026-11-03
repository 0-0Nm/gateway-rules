import groovy.json.JsonSlurper
import groovy.json.JsonOutput

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Four display fields are project-owned; Android code uses an independent monotonic CI sequence.
val versionPolicy = JsonSlurper().parse(rootProject.file("version-policy.json")) as Map<*, *>
val identityPath = providers.gradleProperty("gatewayBuildIdentity").orNull
require(System.getenv("GITHUB_ACTIONS") != "true" || identityPath != null) {
    "CI must allocate a project build identity before Gradle; no hardcoded version fallback"
}
val identity: Map<*, *> = if (identityPath != null) {
    val input = rootProject.file(identityPath)
    require(input.isFile) { "Allocated build identity missing" }
    JsonSlurper().parse(input) as Map<*, *>
} else mapOf(
    "schema" to 1, "versionName" to "${versionPolicy["major"]}.${versionPolicy["minor"]}.${versionPolicy["validationRound"]}.0",
    "versionCode" to 1, "major" to versionPolicy["major"], "minor" to versionPolicy["minor"],
    "validationRound" to versionPolicy["validationRound"], "buildSequence" to 0, "ciSequence" to 0, "sourceCommit" to "local-unallocated",
    "workflowRunId" to "", "workflowRunAttempt" to 0, "buildKind" to "local-unallocated"
)
val identityAsset = JsonOutput.toJson(identity)
val generatedIdentityAssets = layout.buildDirectory.dir("generated/buildIdentityAssets")
val generateBuildIdentityAssets by tasks.registering {
    inputs.property("identityJson", identityAsset)
    outputs.file(generatedIdentityAssets.map { it.file("build-identity.json") })
    doLast {
        val output = generatedIdentityAssets.get().file("build-identity.json").asFile
        output.parentFile.mkdirs()
        output.writeText(identityAsset, Charsets.UTF_8)
    }
}

android {
    namespace = "com.aqi.ccb"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.aqi.ccb"
        minSdk = 28
        targetSdk = 34
        versionCode = (identity["versionCode"] as Number).toInt()
        versionName = identity["versionName"] as String
        buildConfigField("String", "BUILD_SOURCE_COMMIT", JsonOutput.toJson(identity["sourceCommit"]))
        buildConfigField("String", "BUILD_RUN_ID", JsonOutput.toJson(identity["workflowRunId"]))
        buildConfigField("String", "BUILD_KIND", JsonOutput.toJson(identity["buildKind"]))
        buildConfigField("int", "BUILD_SEQUENCE", identity["buildSequence"].toString())
        buildConfigField("int", "BUILD_CI_SEQUENCE", identity["ciSequence"].toString())
        buildConfigField("int", "BUILD_RUN_ATTEMPT", identity["workflowRunAttempt"].toString())
        buildConfigField("int", "VERSION_MAJOR", identity["major"].toString())
        buildConfigField("int", "VERSION_MINOR", identity["minor"].toString())
        buildConfigField("int", "VERSION_VALIDATION", identity["validationRound"].toString())
    }

    sourceSets.getByName("main").assets.srcDir(generatedIdentityAssets)

    buildFeatures {
        buildConfig = true
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    // CI passes an explicit private debug-store location; never rely on runner HOME defaults.
    signingConfigs {
        getByName("debug") {
            project.providers.gradleProperty("gatewayDebugKeystore").orNull?.let { path ->
                val fixedStore = project.file(path)
                require(fixedStore.isFile) { "Configured debug keystore is missing" }
                storeFile = fixedStore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug { isMinifyEnabled = false }
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")

    implementation("androidx.compose.ui:ui")
    // LazyColumn / clickable / verticalScroll 都在这里。
    // 虽然 material3 会传递引入，但显式声明可避免上游改传递关系后编译突然失败。
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}

tasks.named("preBuild").configure { dependsOn(generateBuildIdentityAssets) }
