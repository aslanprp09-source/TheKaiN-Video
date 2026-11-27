plugins { id("com.android.library") }
val runtimeDir = providers.gradleProperty("isolatedRuntimeDir")
val lab = file("../../experiments/android_native/android/app/src")
android {
    namespace = "com.thekain.runtime.core"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    buildFeatures { aidl = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    sourceSets.getByName("main") {
        java.srcDirs(lab.resolve("main/java"), lab.resolve("debug/java"))
        // Share the reviewed core verbatim. Do not include either laboratory UI/Application or QA fixture server.
        val core = lab.resolve("main/java/com/thekain/runtime/lab").listFiles()!!.filter { it.extension == "java" }
            .map { "com/thekain/runtime/lab/" + it.name }
        java.setIncludes((core + listOf("BrokerHello", "BrokerConfig", "BrokerChunk", "BrokerProcess", "BrokerGuardFlags", "RemoteHttpsService", "RemoteHttpsTransport")
            .map { "com/thekain/runtime/lab/$it.java" } + "com/thekain/runtime/lab/network/*.java").toSet())
        aidl.srcDirs(lab.resolve("main/aidl"), lab.resolve("debug/aidl"))
        assets.srcDir(runtimeDir.map { "$it/assets" })
        jniLibs.srcDir(runtimeDir.map { "$it/jniLibs" })
    }
    androidResources { ignoreAssetsPattern = "thekain-internal-dont-ignore-anything" }
}
dependencyLocking { lockAllConfigurations() }
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:1.9.10"))
    implementation("org.jetbrains:annotations:23.0.0")
}
