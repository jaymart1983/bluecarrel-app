import java.io.FileInputStream
import java.util.Properties

// Optional fixed signing key (see keystore.properties.example). Every build signed
// with the same key installs over the previous one; without it Android refuses the
// update ("App not installed") whenever the debug key differs, e.g. in a throwaway
// build container. With no keystore present, Gradle's normal debug signing applies.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) FileInputStream(keystorePropsFile).use { load(it) }
}
val hasKeystore = keystoreProps.getProperty("storeFile")
    ?.let { rootProject.file(it).exists() } == true

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.bluecarrel.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.bluecarrel.app"
        minSdk = 29
        targetSdk = 34
        // Bump BOTH on every published build. Android only offers an APK as an
        // update when versionCode increases, and Diagnostics shows versionName so
        // the running build can be identified from the phone.
        versionCode = 17
        versionName = "1.0.16"
    }

    signingConfigs {
        create("shared") {
            if (hasKeystore) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            if (hasKeystore) signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            if (hasKeystore) signingConfig = signingConfigs.getByName("shared")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // buildConfig: the diagnostics panel prints VERSION_NAME/VERSION_CODE, so the
    // running build can always be identified from the phone itself.
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
