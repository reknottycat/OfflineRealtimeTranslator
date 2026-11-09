plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.bohanli.ruzhtranslator"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "com.bohanli.ruzhtranslator"
        minSdk = 29
        targetSdk = 36
        versionCode = 39
        versionName = "3.9-alpha-vosk-fix"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake { arguments += listOf("-DANDROID_STL=c++_static", "-DCMAKE_BUILD_TYPE=Release") }
        }
    }
    signingConfigs { getByName("debug") }
    buildTypes {
        // CI generates a fresh Android debug key each run. Old v3.7/v3.8 packages
        // therefore cannot safely be upgraded in place. Use a distinct preview
        // application ID so the user can preserve the original app and its history.
        debug {
            applicationIdSuffix = ".preview"
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/lmt/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    buildFeatures { viewBinding = true }
    androidResources { noCompress += listOf("bin", "onnx", "model", "bpe", "litertlm", "gguf") }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.vosk.android)
    implementation(libs.onnxruntime.android)
    // Gemma remains LiteRT-LM; the GGUF bridge owns distinct LMT/HY-MT2 profiles.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.16.1")
    val roomVersion = "2.7.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
