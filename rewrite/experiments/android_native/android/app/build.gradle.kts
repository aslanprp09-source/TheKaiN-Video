plugins { id("com.android.application") }
val runtimeDir = providers.gradleProperty("labRuntimeDir")
android {
    buildFeatures { aidl = true }
    namespace = "com.thekain.runtime.lab"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.thekain.runtime.lab"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-source-lab"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.getByName("main") {
        assets.srcDir(runtimeDir.map { "$it/assets" })
        jniLibs.srcDir(runtimeDir.map { "$it/jniLibs" })
    }
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
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
}
