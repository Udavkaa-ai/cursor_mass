plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ru.buswidget"
    compileSdk = 34

    defaultConfig {
        applicationId = "ru.buswidget"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        val mapkitApiKey = System.getenv("MAPKIT_API_KEY") ?: "MAPKIT_API_KEY_PLACEHOLDER"
        buildConfigField("String", "MAPKIT_API_KEY", "\"$mapkitApiKey\"")

        // Yandex JavaScript API key (used by the WebView map) — distinct from the
        // MapKit key above. Falls back to the MapKit key for local builds.
        val jsApiKey = System.getenv("JS_YANDEX_API")
            ?: System.getenv("MAPKIT_API_KEY")
            ?: "JS_YANDEX_API_PLACEHOLDER"
        buildConfigField("String", "JS_YANDEX_API", "\"$jsApiKey\"")
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.gms:play-services-location:21.1.0")
    // TODO: MapKit requires authenticated Yandex repository credentials - see git history for details
    // implementation("com.yandex.android:maps.mobile:4.11.0-full")
}
