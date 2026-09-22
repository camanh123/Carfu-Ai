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

fun requirePinnedFile(file: File, sha: String, bytes: Long, label: String) {
    require(file.isFile) { "Missing $label: $file" }
    require(file.length() == bytes) { "$label size ${file.length()} != pinned $bytes" }
    val actual = sha256Of(file)
    require(actual == sha) { "$label SHA256 $actual != pinned $sha" }
}

val generatedAssets = layout.buildDirectory.dir("generated-sherpa-assets")
val modelName = pin("MODEL_NAME")
val modelDir = File(rootDir, "models/$modelName")

val prepareDeps by tasks.registering(Exec::class) {
    workingDir = rootDir
    commandLine("bash", "scripts/prepare-deps.sh")
    inputs.file(File(rootDir, "deps.lock"))
    outputs.file(File(rootDir, "models/$modelName/${pin("MODEL_ENCODER")}"))
    outputs.file(File(rootDir, "models/$modelName/${pin("MODEL_DECODER")}"))
    outputs.file(File(rootDir, "models/$modelName/${pin("MODEL_JOINER")}"))
    outputs.file(File(rootDir, "models/$modelName/${pin("MODEL_TOKENS")}"))
    outputs.file(File(rootDir, "models/$modelName/${pin("MODEL_BPE")}"))
    outputs.file(File(rootDir, "third_party/sherpa-onnx-android/jniLibs/arm64-v8a/${pin("LIB_JNI_NAME")}"))
    outputs.file(File(rootDir, "third_party/sherpa-onnx-android/jniLibs/arm64-v8a/${pin("LIB_ORT_NAME")}"))
    outputs.file(File(rootDir, "third_party/sherpa-onnx-kotlin-api/OfflineRecognizer.kt"))
}

val stageModelAssets by tasks.registering(Copy::class) {
    dependsOn(prepareDeps)
    from(modelDir) {
        include(pin("MODEL_ENCODER"))
        include(pin("MODEL_DECODER"))
        include(pin("MODEL_JOINER"))
        include(pin("MODEL_TOKENS"))
        include(pin("MODEL_BPE"))
    }
    into(generatedAssets.map { it.dir("models/$modelName") })
    doFirst {
        requirePinnedFile(
            File(modelDir, pin("MODEL_ENCODER")),
            pin("MODEL_ENCODER_SHA256"),
            pin("MODEL_ENCODER_BYTES").toLong(),
            pin("MODEL_ENCODER"),
        )
        requirePinnedFile(
            File(modelDir, pin("MODEL_DECODER")),
            pin("MODEL_DECODER_SHA256"),
            pin("MODEL_DECODER_BYTES").toLong(),
            pin("MODEL_DECODER"),
        )
        requirePinnedFile(
            File(modelDir, pin("MODEL_JOINER")),
            pin("MODEL_JOINER_SHA256"),
            pin("MODEL_JOINER_BYTES").toLong(),
            pin("MODEL_JOINER"),
        )
        requirePinnedFile(
            File(modelDir, pin("MODEL_TOKENS")),
            pin("MODEL_TOKENS_SHA256"),
            pin("MODEL_TOKENS_BYTES").toLong(),
            pin("MODEL_TOKENS"),
        )
        requirePinnedFile(
            File(modelDir, pin("MODEL_BPE")),
            pin("MODEL_BPE_SHA256"),
            pin("MODEL_BPE_BYTES").toLong(),
            pin("MODEL_BPE"),
        )
        requirePinnedFile(
            File(rootDir, "third_party/sherpa-onnx-android/jniLibs/arm64-v8a/${pin("LIB_JNI_NAME")}"),
            pin("LIB_JNI_SHA256"),
            pin("LIB_JNI_BYTES").toLong(),
            pin("LIB_JNI_NAME"),
        )
        requirePinnedFile(
            File(rootDir, "third_party/sherpa-onnx-android/jniLibs/arm64-v8a/${pin("LIB_ORT_NAME")}"),
            pin("LIB_ORT_SHA256"),
            pin("LIB_ORT_BYTES").toLong(),
            pin("LIB_ORT_NAME"),
        )
    }
}

tasks.named("preBuild").configure { dependsOn(stageModelAssets) }
tasks.configureEach {
    if (name.startsWith("compile") || name.startsWith("package")) {
        dependsOn(prepareDeps)
    }
}

android {
    namespace = "org.stypox.dicio.sherpabenchmark"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.stypox.dicio.sherpabenchmark"
        minSdk = 29
        targetSdk = 29
        versionCode = 4
        versionName = "0.3b1.3-interactive-optimize"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.clear()
            abiFilters += "arm64-v8a"
        }

        buildConfigField("String", "SHERPA_ONNX_SOURCE", "\"${pin("SHERPA_ONNX_SOURCE")}\"")
        buildConfigField("String", "SHERPA_ONNX_TAG", "\"${pin("SHERPA_ONNX_TAG")}\"")
        buildConfigField("String", "SHERPA_ONNX_COMMIT", "\"${pin("SHERPA_ONNX_COMMIT")}\"")
        buildConfigField("String", "SHERPA_ONNX_LICENSE", "\"${pin("SHERPA_ONNX_LICENSE")}\"")
        buildConfigField("String", "MODEL_NAME", "\"$modelName\"")
        buildConfigField("String", "MODEL_ARCHITECTURE", "\"${pin("MODEL_ARCHITECTURE")}\"")
        buildConfigField("String", "MODEL_LANGUAGE", "\"${pin("MODEL_LANGUAGE")}\"")
        buildConfigField("String", "MODEL_QUANTIZATION", "\"${pin("MODEL_QUANTIZATION")}\"")
        buildConfigField("String", "MODEL_ARCHIVE_URL", "\"${pin("MODEL_ARCHIVE_URL")}\"")
        buildConfigField("String", "MODEL_ARCHIVE_SHA256", "\"${pin("MODEL_ARCHIVE_SHA256")}\"")
        buildConfigField("long", "MODEL_ARCHIVE_BYTES", "${pin("MODEL_ARCHIVE_BYTES")}L")
        buildConfigField("String", "MODEL_ENCODER", "\"${pin("MODEL_ENCODER")}\"")
        buildConfigField("String", "MODEL_ENCODER_SHA256", "\"${pin("MODEL_ENCODER_SHA256")}\"")
        buildConfigField("long", "MODEL_ENCODER_BYTES", "${pin("MODEL_ENCODER_BYTES")}L")
        buildConfigField("String", "MODEL_DECODER", "\"${pin("MODEL_DECODER")}\"")
        buildConfigField("String", "MODEL_DECODER_SHA256", "\"${pin("MODEL_DECODER_SHA256")}\"")
        buildConfigField("long", "MODEL_DECODER_BYTES", "${pin("MODEL_DECODER_BYTES")}L")
        buildConfigField("String", "MODEL_JOINER", "\"${pin("MODEL_JOINER")}\"")
        buildConfigField("String", "MODEL_JOINER_SHA256", "\"${pin("MODEL_JOINER_SHA256")}\"")
        buildConfigField("long", "MODEL_JOINER_BYTES", "${pin("MODEL_JOINER_BYTES")}L")
        buildConfigField("String", "MODEL_TOKENS", "\"${pin("MODEL_TOKENS")}\"")
        buildConfigField("String", "MODEL_TOKENS_SHA256", "\"${pin("MODEL_TOKENS_SHA256")}\"")
        buildConfigField("long", "MODEL_TOKENS_BYTES", "${pin("MODEL_TOKENS_BYTES")}L")
        buildConfigField("String", "MODEL_BPE", "\"${pin("MODEL_BPE")}\"")
        buildConfigField("String", "MODEL_BPE_SHA256", "\"${pin("MODEL_BPE_SHA256")}\"")
        buildConfigField("long", "MODEL_BPE_BYTES", "${pin("MODEL_BPE_BYTES")}L")
        buildConfigField("String", "PHASE_NAME", "\"3B.1.3\"")
        buildConfigField("String", "ENGINE_NAME", "\"sherpa-onnx\"")
        buildConfigField("String", "EXECUTION_PROVIDER", "\"cpu\"")
        buildConfigField("String", "DECODING_METHOD", "\"greedy_search\"")
        buildConfigField("boolean", "NATIVE_STREAMING_MODEL", "false")
        buildConfigField("boolean", "SIMULATED_STREAMING", "true")
    }

    signingConfigs {
        getByName("debug") {
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

    sourceSets {
        getByName("main") {
            assets.srcDir(generatedAssets)
            jniLibs.srcDir(File(rootDir, "third_party/sherpa-onnx-android/jniLibs"))
            java.srcDir(File(rootDir, "third_party/sherpa-onnx-kotlin-api"))
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            excludes += setOf(
                "**/armeabi-v7a/**",
                "**/x86/**",
                "**/x86_64/**",
                "**/armeabi/**",
            )
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    androidResources {
        noCompress += listOf("onnx", "int8.onnx")
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
            "carfu-sherpa-zipformer-vi-benchmark-p3b1-3.apk"
        } else {
            "carfu-sherpa-zipformer-vi-benchmark-p3b1-3-${buildType.name}.apk"
        }
        (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName = fileName
    }
}

tasks.register<Copy>("syncDiagnosticApk") {
    dependsOn("packageDebug")
    from(layout.buildDirectory.dir("intermediates/apk/debug"))
    include("*.apk")
    into(layout.buildDirectory.dir("outputs/apk/debug"))
    rename { "carfu-sherpa-zipformer-vi-benchmark-p3b1-3.apk" }
}

afterEvaluate {
    tasks.named("assembleDebug").configure { finalizedBy("syncDiagnosticApk") }
}
