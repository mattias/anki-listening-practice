import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "dev.lovkvist.listening"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.lovkvist.listening"
        minSdk = 26
        targetSdk = 35
        versionCode = 16
        versionName = "0.7.5"
        // The bundled Python runtime is large per ABI; virtually all current phones are arm64.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // Release signing reads keystore.properties (git-ignored) from the project root:
    // storeFile, storePassword, keyAlias, keyPassword. Without it, release builds are unsigned.
    val keystoreProps = rootProject.file("keystore.properties").takeIf { it.isFile }?.let { file ->
        Properties().apply { file.inputStream().use(::load) }
    }
    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        compose = true
        // VERSION_NAME, compared with the latest GitHub release.
        buildConfig = true
    }
    packaging {
        resources.excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/*.md")
        // youtubedl-android runs Python and QuickJS from extracted native libraries.
        jniLibs.useLegacyPackaging = true
    }
    androidResources {
        // Lists the translated UI languages for Android 13+'s per-app language setting.
        generateLocaleConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    // Per-app UI language (AppCompatDelegate.setApplicationLocales), also before Android 13
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.android)

    // Japanese morphological analyzer (IPADIC dictionary bundled in the jar)
    implementation(libs.kuromoji.ipadic)

    // Modern .apkg/.colpkg exports compress the collection and media with zstd.
    // 1.5.7-3+ is the first build Android 16 KB page devices accept (older ones align to 64 KB);
    // 1.5.7-13+ would require compileSdk 37.
    implementation(libs.zstd.jni) { artifact { type = "aar" } }

    // yt-dlp with its own Python runtime and QuickJS, for YouTube audio and subtitles
    implementation(libs.youtubedl.android)

    // Yomitan dictionaries: streaming JSON (term banks reach tens of MB) and scheduled update checks
    implementation(libs.gson)
    implementation(libs.work.runtime)

    testImplementation(libs.junit)
    // Desktop build of zstd-jni and a real org.json, since android.jar only has stubs on the JVM
    testImplementation(libs.zstd.jni)
    testImplementation(libs.json)
}
