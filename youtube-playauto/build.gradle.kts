import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.com.android.library)
    alias(libs.plugins.org.jetbrains.kotlin.android)
}

android {
    namespace = "org.stypox.dicio.youtubeplayauto.lib"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.java.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.java.get())
    }

    kotlin {
        compilerOptions {
            jvmTarget = JvmTarget.fromTarget(libs.versions.java.get())
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

android.sourceSets.getByName("main").java.srcDir(
    file("${rootDir}/youtube-playauto-android/src/main/kotlin"),
)

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    exclude("**/YouTubePlayAutoHarnessActivity.kt")
    exclude("**/YouTubePlayAutoAccessibilityService.kt")
    exclude("**/AndroidYouTubeInAppSelector.kt")
}

dependencies {
    api(project(":playauto"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}
