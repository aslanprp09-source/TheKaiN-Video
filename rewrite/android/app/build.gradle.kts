import groovy.json.JsonOutput
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.thekain.video"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.thekain.video.rebuild"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.0-m6"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
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

dependencyLocking { lockAllConfigurations() }

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

kapt { arguments { arg("room.schemaLocation", "$projectDir/schemas") } }

dependencies {
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
