plugins { id("com.android.application") }
android {
    namespace = "com.thekain.runtime.probe"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.thekain.runtime.probe"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-boundary-probe"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
