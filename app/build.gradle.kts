import java.net.URI

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
        versionCode = 13
        versionName = "0.3.0-beta3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    // Keep both languages available when users change the app language offline.
    bundle { language { enableSplit = false } }
    defaultConfig {
        val reportEndpoint = providers.environmentVariable("SUPERMENS_REPORT_ENDPOINT").orElse("").get()
        if(reportEndpoint.isNotEmpty()) {
            val uri=URI(reportEndpoint)
            require(uri.scheme=="https" && !uri.host.isNullOrBlank() && uri.rawUserInfo==null && uri.rawQuery==null && uri.rawFragment==null && reportEndpoint.none {it=='"' || it=='\\' || it.isWhitespace()})
        }
        buildConfigField("String", "REPORT_ENDPOINT", "\"$reportEndpoint\"")
        val privacyEmail=providers.environmentVariable("SUPERMENS_PRIVACY_EMAIL").orElse("").get()
        require(privacyEmail.isEmpty() || privacyEmail.matches(Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")))
        buildConfigField("String", "PRIVACY_EMAIL", "\"$privacyEmail\"")
        if(providers.environmentVariable("SUPERMENS_PUBLIC_RELEASE").orElse("false").get()=="true") {
            require(reportEndpoint.isNotEmpty() && privacyEmail.isNotEmpty()) {"Public releases require the report endpoint and a private privacy contact"}
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("beta") {
            // Exercise the same optimized JNI path as release without production keys.
            initWith(getByName("release"))
            applicationIdSuffix = ".beta"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
}

// Include the same licence and notices as the corresponding source distribution.
val prepareLicenseAssets by tasks.registering(Sync::class) {
    from(rootProject.file("LICENSE"), rootProject.file("LICENSE_EXCEPTION.md"), rootProject.file("THIRD_PARTY_NOTICES.md"))
    from(provider {
        configurations.getByName("releaseRuntimeClasspath").resolvedConfiguration.resolvedArtifacts
            .filter {it.moduleVersion.id.group=="com.google.ai.edge.litertlm"}
            .map {zipTree(it.file)}
    }) {
        include("LICENSE","THIRD_PARTY_NOTICE.txt")
        rename {name -> "LiteRT-LM-$name"}
    }
    doFirst { destinationDir.walkTopDown().filter { it.isFile }.forEach { it.setWritable(true, true) } }
    filePermissions { unix("644") }
    into(layout.buildDirectory.dir("generated/licenseAssets/licenses"))
}
android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/licenseAssets"))
tasks.named("preBuild").configure { dependsOn(prepareLicenseAssets) }

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
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("com.google.mlkit:genai-speech-recognition:1.0.0-alpha1")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.11.0")
}
