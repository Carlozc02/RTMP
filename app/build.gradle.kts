plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.rtmpstreamer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.rtmpstreamer"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/INDEX.LIST"
            )
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
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    // Librería open source (Apache 2.0) para RTMP/RTSP/SRT desde cámara
    implementation("com.github.pedroSG94.RootEncoder:library:2.5.5")
}
