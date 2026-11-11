plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

val enableQnn = providers.gradleProperty("enableQnn").map(String::toBooleanStrict).orElse(false).get()

android {
    namespace = "com.bohanli.ruzhtranslator"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "com.bohanli.ruzhtranslator"
        minSdk = 29
        targetSdk = 36
        versionCode = 43
        versionName = "4.3-alpha-multilingual-asr"
        buildConfigField("boolean", "QNN_ENABLED", enableQnn.toString())
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake { arguments += listOf("-DANDROID_STL=c++_static", "-DCMAKE_BUILD_TYPE=Release") }
        }
    }
    signingConfigs { getByName("debug") }
    buildTypes {
        // These debug previews can be installed beside v4.2 and each other.
        debug {
            applicationIdSuffix = if (enableQnn) ".preview.multilingual.qnn" else ".preview.multilingual"
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
    packaging {
        jniLibs {
            pickFirsts += "**/libc++_shared.so"
            if (enableQnn) {
                // FastRPC locates the DSP skeleton via a real nativeLibraryDir path.
                useLegacyPackaging = true
                keepDebugSymbols += "**/libQnnHtpV*Skel.so"
            }
        }
    }
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
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
    implementation("org.apache.commons:commons-compress:1.28.0")
    // Download and verify the selected native dependencies with scripts/setup_sherpa_aar.py.
    if (enableQnn) {
        implementation(files("libs/sherpa-onnx-2.0.1-1-qnn.aar"))
        // The QNN Sherpa binary requires this newer ORT ABI. Its Java API also serves recasepunc.
        implementation(files("libs/onnxruntime-1.28.1-xdcobra.aar"))
        implementation(files("libs/qnn-runtime-2.40.0.aar"))
    } else {
        // Sherpa carries its own statically linked ORT, so recasepunc keeps its matching Java/ELF pair.
        implementation(files("libs/sherpa-onnx-static-link-onnxruntime-2.0.1.aar"))
        implementation(libs.onnxruntime.android)
    }
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
