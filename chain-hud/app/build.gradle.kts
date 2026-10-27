plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.damageinc.chainhud"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.damageinc.chainhud"
        minSdk = 31
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("com.rokid.cxr:client-l:1.0.4")
    testImplementation("junit:junit:4.13.2")
}
