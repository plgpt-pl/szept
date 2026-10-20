import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) load(FileInputStream(f))
}

android {
    namespace = "pl.fourdt.szept"
    compileSdk = 35
    ndkVersion = "27.2.12479018"   // potrzebny tylko do odchudzenia (strip) bibliotek natywnych libsignal

    defaultConfig {
        applicationId = "pl.plgpt.szept"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }   // realne telefony + emulatory; bez 32-bit
    }
    buildFeatures { compose = true; buildConfig = true }
    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystoreProps.getProperty("storeFile") != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    packaging {
        resources.excludes += "META-INF/*.md"
        // libsignal-client.jar niesie biblioteki JNI dla desktopów (dylib/dll/so) – na Androidzie zbędne (~270 MB)
        resources.excludes += listOf("**/*.dylib", "**/*.dll", "**/libsignal_jni*.so", "**/signal_jni*.dll")
        jniLibs.excludes += "**/libsignal_jni_testing.so"   // biblioteka testowa Signala, zbędna w apce
    }
    splits { abi { isEnable = false } }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.02.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.signal:libsignal-android:0.105.1")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")   // QR bez usług Google
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
