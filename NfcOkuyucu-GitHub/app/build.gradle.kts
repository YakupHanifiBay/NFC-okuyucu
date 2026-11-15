plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.nfcreader"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.nfcreader"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.jmrtd:jmrtd:0.7.42")
    implementation("net.sf.scuba:scuba-sc-android:0.0.26")
    implementation("io.github.michaldvorak-gemalto:jp2-android:1.0.5")
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
