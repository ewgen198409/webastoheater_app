plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.webasto.heater"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.webasto.heater"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "2.5.0"         // Версия приложения
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

signingConfigs {
    create("release") {
        storeFile = file("$rootDir/app/keystore/debug.keystore")
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
    }
}

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            isShrinkResources = false
            applicationVariants.all {
                outputs.all {
                    val apkName = "WebastoHeater-${versionName}.apk"
                    (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName = apkName
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.9.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.cardview:cardview:1.0.0")

    // Dependencies for update checker
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
