plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Read configuration from gradle.properties (with safe fallbacks).
val mapsApiKey: String = (project.findProperty("MAPS_API_KEY") as String?) ?: "MISSING_MAPS_KEY"
val defaultBackendUrl: String = (project.findProperty("BACKEND_BASE_URL") as String?) ?: "https://example.com/"
val defaultApiKey: String = (project.findProperty("BACKEND_API_KEY") as String?) ?: ""

// Release signing (values come from gradle.properties; keep that file out of
// any public repo since it holds the keystore passwords).
val releaseStoreFile: String? = project.findProperty("RELEASE_STORE_FILE") as String?
val releaseStorePassword: String? = project.findProperty("RELEASE_STORE_PASSWORD") as String?
val releaseKeyAlias: String? = project.findProperty("RELEASE_KEY_ALIAS") as String?
val releaseKeyPassword: String? = project.findProperty("RELEASE_KEY_PASSWORD") as String?
// Resolved relative to the root project dir (android/), where gradle.properties lives.
val hasReleaseSigning = !releaseStoreFile.isNullOrBlank() &&
    rootProject.file(releaseStoreFile).exists() &&
    !releaseStorePassword.isNullOrBlank()

android {
    namespace = "com.noesolution.gtracker"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.noesolution.gtracker"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"

        // Injected into AndroidManifest.xml as ${MAPS_API_KEY}
        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey

        // Default values; the user can override them at runtime in Settings.
        buildConfigField("String", "DEFAULT_BACKEND_URL", "\"$defaultBackendUrl\"")
        buildConfigField("String", "DEFAULT_API_KEY", "\"$defaultApiKey\"")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        buildConfig = true
    }

    lint {
        // Skip the automatic lint-vital pass on release builds: it needs to
        // download the lint-gradle tool, which fails in this environment due
        // to a local network/SSL issue unrelated to the app's code.
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.play.services.location)
    implementation(libs.kotlinx.coroutines.play.services)

    implementation(libs.maps.compose)
    implementation(libs.maps.ktx)

    implementation(libs.retrofit)
    implementation(libs.retrofit.moshi)
    implementation(libs.okhttp.logging)
    implementation(libs.moshi.kotlin)
}
