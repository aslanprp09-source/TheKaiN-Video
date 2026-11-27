import groovy.json.JsonOutput
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
    id("org.jetbrains.kotlin.plugin.compose")
}
val internalRuntime = providers.gradleProperty("isolatedRuntimeDir").isPresent
val internalSource = providers.exec { commandLine("git", "rev-parse", "HEAD") }.standardOutput.asText.map { it.trim() }
android {
    namespace = "com.thekain.video"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.thekain.video.rebuild"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.1.0-m6"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        if (internalRuntime) {
            ndk.abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
            val source = internalSource.get()
            require(source.matches(Regex("[0-9a-f]{40}")))
            buildConfigField("String", "INTERNAL_RUNTIME_SOURCE", "\"$source\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = internalRuntime }
    sourceSets.getByName("main").java.srcDir(if (internalRuntime) "src/isolated/java" else "src/standard/java")
    if (internalRuntime) {
        sourceSets.getByName("androidTest").java.srcDir("src/isolatedAndroidTest/java")
        packaging.jniLibs.useLegacyPackaging = true
        packaging.jniLibs.keepDebugSymbols.add("**/*.so")
        androidResources.ignoreAssetsPattern = "thekain-internal-dont-ignore-anything"
    } else {
        sourceSets.getByName("androidTest").java.srcDir("src/standardAndroidTest/java")
    }
    bundle { language { enableSplit = false } }
    val signingValues = listOf("THEKAIN_KEYSTORE", "THEKAIN_STORE_PASSWORD", "THEKAIN_KEY_ALIAS", "THEKAIN_KEY_PASSWORD")
        .map { System.getenv(it)?.takeIf(String::isNotBlank) }
    require(signingValues.all { it == null } || signingValues.all { it != null }) {
        "Release signing requires all four THEKAIN signing environment variables; no debug-key fallback"
    }
    val controlledSigning = if (signingValues.all { it != null }) signingConfigs.create("controlledRelease") {
        storeFile = file(signingValues[0]!!)
        storePassword = signingValues[1]
        keyAlias = signingValues[2]
        keyPassword = signingValues[3]
    } else null
    buildTypes.getByName("release") {
        isDebuggable = false
        isMinifyEnabled = false
        signingConfig = controlledSigning // Absence deliberately produces unsigned output.
    }
}
gradle.taskGraph.whenReady {
    require(!internalRuntime || allTasks.none { it.path.contains("release", ignoreCase = true) }) {
        "Isolated runtime integration is internal debug only; release packaging remains on the existing default path"
    }
}

dependencyLocking {
    lockAllConfigurations()
    if (internalRuntime) lockFile.set(file("gradle-isolated.lockfile"))
}

abstract class ReleaseNoticesTask : DefaultTask() {
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
}
val releaseNotices = tasks.register<ReleaseNoticesTask>("releaseNotices") {
    val runtime = provider { configurations.getByName("releaseRuntimeClasspath") }
    inputs.files(runtime)
    inputs.dir("../../release/legal")
    outputDirectory.set(layout.buildDirectory.dir("generated/releaseLegal"))
    doLast {
        val root = outputDirectory.get().asFile.resolve("third_party").apply { deleteRecursively(); mkdirs() }
        file("../../release/legal").copyRecursively(root, overwrite = true)
        val artifacts = runtime.get().resolvedConfiguration.resolvedArtifacts.sortedBy { it.moduleVersion.id.toString() }
        val components = artifacts.map { artifact ->
            val id = artifact.moduleVersion.id
            val license = when {
                id.group.startsWith("androidx.") || id.group.startsWith("org.jetbrains") || id.group in setOf("com.google.guava", "org.jspecify") -> "Apache-2.0"
                id.group == "org.jsoup" -> "MIT"
                else -> error("Review the release license/source policy for ${id.group}:${id.name}:${id.version}")
            }
            val notices = mutableListOf<String>()
            ZipFile(artifact.file).use { archive ->
                archive.entries().asSequence().filter { !it.isDirectory &&
                    Regex("(^|/)(LICENSE[^/]*|NOTICE[^/]*|COPYING[^/]*)", RegexOption.IGNORE_CASE).containsMatchIn(it.name)
                }.forEach { entry ->
                    val name = "${id.group}.${id.name}-${id.version}/" + entry.name.replace('/', '_').replace('\\', '_')
                    root.resolve(name).apply { parentFile.mkdirs(); writeBytes(archive.getInputStream(entry).use { it.readBytes() }) }
                    notices += name
                }
            }
            mapOf("component" to id.toString(), "artifact" to artifact.file.name,
                "sha256" to MessageDigest.getInstance("SHA-256").digest(artifact.file.readBytes()).joinToString("") { "%02x".format(it) },
                "license" to license, "license_evidence" to "M6 reviewed upstream family policy and packaged notices",
                "source" to when {
                    id.group.startsWith("androidx.") -> "https://android.googlesource.com/platform/frameworks/support/"
                    id.group.startsWith("org.jetbrains.kotlinx") -> "https://github.com/Kotlin/kotlinx.coroutines"
                    id.group.startsWith("org.jetbrains.kotlin") -> "https://github.com/JetBrains/kotlin"
                    id.group.startsWith("org.jetbrains") -> "https://github.com/JetBrains/java-annotations"
                    id.group == "com.google.guava" -> "https://github.com/google/guava"
                    id.group == "org.jspecify" -> "https://github.com/jspecify/jspecify/tree/${id.version}"
                    else -> "https://github.com/jhy/jsoup/tree/jsoup-${id.version}"
                }, "embedded_notices" to notices)
        }
        root.resolve("inventory.json").writeText(JsonOutput.prettyPrint(JsonOutput.toJson(mapOf(
            "schema" to 1, "configuration" to "releaseRuntimeClasspath", "components" to components,
            "native_yt_dlp_python_js_ffmpeg" to false, "production_legal_signoff" to "BLOCKED: final distribution review required"
        ))))
        println("Release inventory: ${components.size} artifacts; no unknown license families")
    }
}
androidComponents.onVariants(androidComponents.selector().withBuildType("release")) { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(releaseNotices, ReleaseNoticesTask::outputDirectory)
}
if (internalRuntime) {
    val internalNotices = tasks.register<ReleaseNoticesTask>("internalAnalysisNotices") {
        val runtime = provider { configurations.getByName("debugRuntimeClasspath") }
        val external = provider { runtime.get().incoming.artifactView {
            componentFilter { it !is org.gradle.api.artifacts.component.ProjectComponentIdentifier }
        }.artifacts }
        inputs.files(external.map { it.artifactFiles })
        inputs.property("repositoryCommit", internalSource)
        inputs.dir("../../release/legal")
        outputDirectory.set(layout.buildDirectory.dir("generated/internalLegal"))
        doLast {
            val root = outputDirectory.get().asFile.resolve("third_party_internal").apply { deleteRecursively(); mkdirs() }
            file("../../release/legal").copyRecursively(root, overwrite = true)
            val nativeBindings = mutableMapOf<String, String>()
            val components = external.get().artifacts.sortedBy { it.id.componentIdentifier.toString() }.map { artifact ->
                val id = artifact.id.componentIdentifier as org.gradle.api.artifacts.component.ModuleComponentIdentifier
                ZipFile(artifact.file).use { archive ->
                    archive.entries().asSequence().filter { !it.isDirectory && it.name.startsWith("jni/") && it.name.endsWith(".so") }.forEach { entry ->
                        val path = "lib/" + entry.name.removePrefix("jni/")
                        if (path.split('/')[1] in setOf("arm64-v8a", "x86_64")) {
                            require("${id.group}:${id.module}:${id.version}" == "androidx.graphics:graphics-path:1.0.1" && path.endsWith("/libandroidx.graphics.path.so"))
                            nativeBindings[path] = MessageDigest.getInstance("SHA-256").digest(archive.getInputStream(entry).use { it.readBytes() }).joinToString("") { "%02x".format(it.toInt() and 255) }
                        }
                    }
                }
                val license = when {
                    id.group in setOf("com.squareup.okhttp3", "com.squareup.okio") -> {
                        require("${id.group}:${id.module}:${id.version}" in setOf("com.squareup.okhttp3:okhttp:4.12.0", "com.squareup.okio:okio:3.6.0", "com.squareup.okio:okio-jvm:3.6.0"))
                        "Apache-2.0"
                    }
                    id.group.startsWith("androidx.") || id.group.startsWith("org.jetbrains") || id.group in setOf("com.google.guava", "org.jspecify") -> "Apache-2.0"
                    id.group == "org.jsoup" -> "MIT"
                    else -> error("Unreviewed internal dependency: $id")
                }
                mapOf("component" to "${id.group}:${id.module}:${id.version}", "artifact" to artifact.file.name, "license" to license,
                    "sha256" to MessageDigest.getInstance("SHA-256").digest(artifact.file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) },
                    "source" to when {
                        id.group == "com.squareup.okhttp3" -> "https://github.com/square/okhttp/tree/parent-4.12.0"
                        id.group == "com.squareup.okio" -> "https://github.com/square/okio/tree/3.6.0"
                        id.group.startsWith("androidx.") -> "https://android.googlesource.com/platform/frameworks/support/"
                        id.group.startsWith("org.jetbrains.kotlinx") -> "https://github.com/Kotlin/kotlinx.coroutines"
                        id.group.startsWith("org.jetbrains.kotlin") -> "https://github.com/JetBrains/kotlin/tree/v${id.version}"
                        id.group.startsWith("org.jetbrains") -> "https://github.com/JetBrains/java-annotations"
                        id.group == "com.google.guava" -> "https://github.com/google/guava"
                        id.group == "org.jspecify" -> "https://github.com/jspecify/jspecify/tree/${id.version}"
                        else -> "https://github.com/jhy/jsoup/tree/jsoup-${id.version}"
                    })
            }
            root.resolve("inventory.json").writeText(JsonOutput.prettyPrint(JsonOutput.toJson(mapOf(
                "schema" to 1, "configuration" to "debugRuntimeClasspath", "scope" to "explicit internal analysis build; no release promotion",
                "components" to components, "project_code" to "runtime-core: owner-authorized source, bound by APK/source receipt; distribution review pending",
                "native_bindings" to nativeBindings, "repository_commit" to internalSource.get(),
                "native_python_licenses" to "assets/legal; verified source-build inventory",
                "production_ready" to false, "automatic_publication" to false
            ))) + "\n")
        }
    }
    androidComponents.onVariants(androidComponents.selector().withBuildType("debug")) { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(internalNotices, ReleaseNoticesTask::outputDirectory)
    }
}

kapt { arguments { arg("room.schemaLocation", "$projectDir/schemas") } }

dependencies {
    if (internalRuntime) implementation(project(":runtime-core"))
    val composeBom = platform("androidx.compose:compose-bom:2025.02.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("org.jsoup:jsoup:1.18.3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    kapt("androidx.room:room-compiler:2.7.2")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.core:core-ktx:1.15.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
}
