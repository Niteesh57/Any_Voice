plugins { id("com.android.application") }
android {
    namespace = "dev.hiprobe"
    compileSdk = 36
    buildFeatures { buildConfig = true }
    defaultConfig {
        applicationId = "dev.hiprobe"
        testInstrumentationRunner = "dev.hiprobe.DeviceSmokeInstrumentation"
        minSdk = 29
        targetSdk = 35
        versionCode = 17
        versionName = "0.17"
        val backendUrl = providers.gradleProperty("anyVoiceBackendUrl").orElse("").get()
        require(backendUrl.matches(Regex("[a-zA-Z0-9:/._-]*"))) { "Invalid backend URL" }
        buildConfigField("String", "DEFAULT_BACKEND_URL", "\"$backendUrl\"")
        ndk {
            abiFilters += providers.gradleProperty("targetAbi").orElse("arm64-v8a,x86_64").get().split(",")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation("io.agora.rtc:full-rtc-basic:4.6.3")
}
