plugins { id("com.android.application") }
android {
    namespace = "dev.hiprobe"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.hiprobe"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "0.2"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
