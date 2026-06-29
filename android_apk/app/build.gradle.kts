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
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            isMinifyEnabled = false
        }
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
    implementation("com.yandex.android:maps.mobile:4.5.1-full")
}
