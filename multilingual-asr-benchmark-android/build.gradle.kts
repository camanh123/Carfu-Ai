import java.io.File
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application") version "8.13.0"
    id("org.jetbrains.kotlin.android") version "1.9.24"
}

val pins = Properties().apply {
    File(rootDir, "deps.lock").bufferedReader().use { load(it) }
}

fun pin(key: String): String = pins.getProperty(key)
    ?: error("Missing $key in deps.lock")

val modelName = pin("MODEL_NAME")
val modelSha256 = pin("MODEL_SHA256")
val modelBytes = pin("MODEL_BYTES").toLong()

fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
        }
    }
    return digest.digest().joinToString("") { b -> "%02x".format(b) }
}

val generatedAssets = layout.buildDirectory.dir("generated-whisper-assets")

val prepareDeps by tasks.registering(Exec::class) {
    workingDir = rootDir
    commandLine("bash", "scripts/prepare-deps.sh")
    inputs.file(File(rootDir, "deps.lock"))
    outputs.file(File(rootDir, "third_party/whisper.cpp/CMakeLists.txt"))
    outputs.file(File(rootDir, "models/$modelName"))
}

val stageModelAsset by tasks.registering(Copy::class) {
    dependsOn(prepareDeps)
    from(File(rootDir, "models/$modelName"))
    into(generatedAssets.map { it.dir("models") })
    doFirst {
        val model = File(rootDir, "models/$modelName")
        require(model.isFile) { "Model missing: $model" }
        require(model.length() == modelBytes) {
            "Model size ${model.length()} != pinned $modelBytes"
        }
        val actual = sha256Of(model)
        require(actual == modelSha256) {
            "Model SHA256 $actual != pinned $modelSha256"
        }
    }
}

tasks.named("preBuild").configure { dependsOn(stageModelAsset) }
tasks.configureEach {
    if (name.startsWith("configureCMake") ||
        name.startsWith("buildCMake") ||
        name.startsWith("externalNativeBuild")
    ) {
        dependsOn(prepareDeps)
    }
}

android {
    namespace = "org.stypox.dicio.asrbenchmark"
    compileSdk = 34
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "org.stypox.dicio.asrbenchmark"
        minSdk = 29
        targetSdk = 29
        versionCode = 2
        versionName = "0.3a1-whisper-tiny-installable"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.clear()
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-O3", "-DNDEBUG")
                cFlags += listOf("-O3", "-DNDEBUG")
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_ARM_NEON=TRUE",
                    // Force native Release even when the APK variant is debug-signed.
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DGGML_NATIVE=OFF",
                    "-DGGML_OPENMP=OFF",
                    "-DGGML_BLAS=OFF",
                    "-DBUILD_SHARED_LIBS=OFF",
                )
            }
        }

        buildConfigField("String", "WHISPER_CPP_REPO", "\"${pin("WHISPER_CPP_REPO")}\"")
        buildConfigField("String", "WHISPER_CPP_TAG", "\"${pin("WHISPER_CPP_TAG")}\"")
        buildConfigField("String", "WHISPER_CPP_COMMIT", "\"${pin("WHISPER_CPP_COMMIT")}\"")
        buildConfigField("String", "WHISPER_CPP_LICENSE", "\"${pin("WHISPER_CPP_LICENSE")}\"")
        buildConfigField("String", "MODEL_NAME", "\"$modelName\"")
        buildConfigField("String", "MODEL_SOURCE_URL", "\"${pin("MODEL_SOURCE_URL")}\"")
        buildConfigField("String", "MODEL_SHA256", "\"$modelSha256\"")
        buildConfigField("String", "MODEL_QUANTIZATION", "\"${pin("MODEL_QUANTIZATION")}\"")
        buildConfigField("long", "MODEL_BYTES", "${modelBytes}L")
        buildConfigField("String", "PHASE_NAME", "\"3A\"")
        buildConfigField("String", "ENGINE_NAME", "\"whisper.cpp\"")
        buildConfigField("boolean", "MODEL_MULTILINGUAL", "true")
    }

    signingConfigs {
        getByName("debug") {
            // Diagnostic identity (Android Debug). Enable v1+v2 for API 29 / OEM installers.
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = false
            enableV4Signing = false
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            ndk {
                debugSymbolLevel = "NONE"
            }
        }
        release {
            isDebuggable = false
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets {
        getByName("main") {
            assets.srcDir(generatedAssets)
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            excludes += setOf("**/libwhisper_v8fp16_va.so", "**/libwhisper_vfpv4.so")
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    androidResources {
        noCompress += listOf("bin")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    testImplementation("junit:junit:4.13.2")
}

android.applicationVariants.configureEach {
    outputs.configureEach {
        val fileName = if (buildType.name == "debug") {
            "carfu-whisper-asr-benchmark-installable.apk"
        } else {
            "carfu-whisper-asr-benchmark-${buildType.name}.apk"
        }
        (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName = fileName
    }
}

tasks.register<Copy>("syncDiagnosticApk") {
    dependsOn("packageDebug")
    from(layout.buildDirectory.dir("intermediates/apk/debug"))
    include("*.apk")
    into(layout.buildDirectory.dir("outputs/apk/debug"))
    rename { "carfu-whisper-asr-benchmark-installable.apk" }
}

afterEvaluate {
    tasks.named("assembleDebug").configure { finalizedBy("syncDiagnosticApk") }
}
