plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "it.supermens.local"
    compileSdk = 36
    defaultConfig {
        applicationId = "it.supermens.offline"
        minSdk = 35
        targetSdk = 36
        versionCode = 3
        versionName = "0.2.1-alpha"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    signingConfigs {
        create("publicRelease") {
            storeFile = providers.environmentVariable("SUPERMENS_KEYSTORE_PATH").orNull?.let { file(it) }
            storePassword = providers.environmentVariable("SUPERMENS_KEYSTORE_PASSWORD").orNull
            keyAlias = providers.environmentVariable("SUPERMENS_KEY_ALIAS").orNull
            keyPassword = providers.environmentVariable("SUPERMENS_KEY_PASSWORD").orNull
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("publicRelease")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

// AGP can otherwise silently produce an unsigned release when credentials are absent.
gradle.taskGraph.whenReady {
    val releaseTasks = setOf("assembleRelease", "packageRelease", "bundleRelease", "installRelease")
    if (allTasks.any { it.project.path == project.path && it.name in releaseTasks }) {
        val missing = listOf(
            "SUPERMENS_KEYSTORE_PATH", "SUPERMENS_KEYSTORE_PASSWORD",
            "SUPERMENS_KEY_ALIAS", "SUPERMENS_KEY_PASSWORD"
        ).filter { providers.environmentVariable(it).orNull.isNullOrBlank() }
        if (missing.isNotEmpty()) {
            throw GradleException("Release signing requires private environment variables: ${missing.joinToString()}")
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.9.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    implementation("org.jsoup:jsoup:1.22.2")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.12.0")
    implementation("androidx.compose.ui:ui:1.9.5")
    implementation("androidx.compose.foundation:foundation:1.9.5")
    implementation("androidx.compose.material:material:1.9.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:genai-speech-recognition:1.0.0-alpha1")
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.11.0")
}
