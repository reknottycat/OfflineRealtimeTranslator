plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.bohanli.ruzhtranslator"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.bohanli.ruzhtranslator"
        minSdk = 29
        targetSdk = 36
        versionCode = 33
        versionName = "3.3-alpha"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        getByName("debug") // default debug keystore
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
    }

    // Prevent Gradle from compressing model weight files.
    // Without this, multi-GB assets cause extremely slow builds and extraction.
    androidResources {
        noCompress += listOf("bin", "onnx", "model", "bpe", "litertlm")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.vosk.android)
    implementation(libs.onnxruntime.android)
    // 0.16.1 uses Kotlin metadata 2.3, compatible with AGP 9.1 built-in Kotlin 2.2.
    // Do not use 0.17+ until Kotlin compiler is migrated to 2.3+.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.16.1")

    // Room database (translation history)
    val roomVersion = "2.7.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
