import groovy.json.JsonOutput
import java.security.MessageDigest

plugins { id("com.android.application") }
val runtimeDir = providers.gradleProperty("labRuntimeDir")
val qaNotices = layout.buildDirectory.dir("generated/qa-notices")
android {
    buildFeatures { aidl = true; buildConfig = true }
    namespace = "com.thekain.runtime.lab"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.thekain.runtime.lab"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3-isolated-uid-lab"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        val commit = providers.exec { commandLine("git", "rev-parse", "HEAD") }.standardOutput.asText.get().trim()
        require(commit.matches(Regex("[0-9a-f]{40}")))
        buildConfigField("String", "QA_SOURCE_COMMIT", "\"$commit\"")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.getByName("main") {
        assets.srcDir(runtimeDir.map { "$it/assets" })
        jniLibs.srcDir(runtimeDir.map { "$it/jniLibs" })
    }
    sourceSets.getByName("debug").assets.srcDir(qaNotices)
    packaging {
        jniLibs.useLegacyPackaging = true
        jniLibs.keepDebugSymbols.add("**/*.so")
    }
    androidResources {
        // AAPT's default <dir>_* exclusion drops Python packages such as __phello__.
        ignoreAssetsPattern = "thekain-lab-dont-ignore-anything"
    }
}
dependencyLocking { lockAllConfigurations() }
dependencies {
    // Already pinned/used by the existing TLS tests; debug QA certificate fixture only.
    debugImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    debugImplementation(platform("org.jetbrains.kotlin:kotlin-bom:1.9.10"))
    debugImplementation("org.jetbrains:annotations:23.0.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
}
val recordQaNotices = tasks.register("recordQaNotices") {
    inputs.files(configurations.named("debugRuntimeClasspath"))
    inputs.file(file("../../../../release/legal/Apache-2.0.txt"))
    outputs.dir(qaNotices)
    doLast {
        val allowed = setOf("com.squareup.okhttp3:okhttp-tls:4.12.0", "com.squareup.okhttp3:okhttp:4.12.0",
            "com.squareup.okio:okio:3.6.0", "com.squareup.okio:okio-jvm:3.6.0",
            "org.jetbrains.kotlin:kotlin-stdlib:1.9.10", "org.jetbrains.kotlin:kotlin-stdlib-common:1.9.10",
            "org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.9.10", "org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.10",
            "org.jetbrains:annotations:23.0.0")
        val entries = configurations.getByName("debugRuntimeClasspath").resolvedConfiguration.resolvedArtifacts
            .sortedBy { it.moduleVersion.id.toString() }.map { artifact ->
                val id=artifact.moduleVersion.id.toString();require(id in allowed) { "Unreviewed QA runtime dependency: $id" }
                val sha=MessageDigest.getInstance("SHA-256").digest(artifact.file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
                mapOf("id" to id,"sha256" to sha,"license" to "Apache-2.0",
                    "source" to when {
                        id.startsWith("com.squareup.okhttp3:") -> "https://github.com/square/okhttp/tree/parent-4.12.0"
                        id.startsWith("com.squareup.okio:") -> "https://github.com/square/okio/tree/3.6.0"
                        id.startsWith("org.jetbrains.kotlin:") -> "https://github.com/JetBrains/kotlin/tree/v1.9.10"
                        else -> "https://github.com/JetBrains/java-annotations/tree/23.0.0"
                    })
            }
        val folder=qaNotices.get().asFile.resolve("legal").apply { mkdirs() }
        folder.resolve("qa-debug-dependencies.json").writeText(JsonOutput.prettyPrint(JsonOutput.toJson(mapOf(
            "scope" to "debug-only owned TLS certificate fixture; no product runtime dependency",
            "components" to entries)))+"\n")
        file("../../../../release/legal/Apache-2.0.txt").copyTo(folder.resolve("qa-debug-Apache-2.0.txt"),overwrite=true)
    }
}
tasks.matching { it.name == "mergeDebugAssets" || (it.name.contains("Debug") && it.name.contains("lint",ignoreCase=true)) }
    .configureEach { dependsOn(recordQaNotices) }
