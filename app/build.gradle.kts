plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("kotlin-kapt")
}

android {
    namespace = "com.dimadesu.lifestreamer"

    defaultConfig {
        applicationId = "com.dimadesu.lifestreamer"

        minSdk = 24
        targetSdk = 35
        compileSdk = 36

        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    
    applicationVariants.all {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "LifeStreamer-${name}.apk"
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
        viewBinding = true
        dataBinding = true
    }
    packaging {
        jniLibs {
            pickFirsts += setOf("**/*.so")
        }
    }
}

dependencies {
    implementation(libs.material)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.databinding.common)
    implementation(libs.androidx.lifecycle.extensions)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.datastore)

    implementation("io.github.thibaultbee.streampack:streampack-core:3.0.0-RC3")
    implementation("io.github.thibaultbee.streampack:streampack-ui:3.0.0-RC3")
    implementation("io.github.thibaultbee.streampack:streampack-services:3.0.0-RC3")
    implementation("io.github.thibaultbee.streampack:streampack-rtmp:3.0.0-RC3")
    implementation("io.github.thibaultbee.streampack:streampack-srt:3.0.0-RC3")

    // Needed because we copied PreviewView into the app module which depends on viewfinder types
    implementation("androidx.camera.viewfinder:viewfinder-core:1.4.0-alpha13")
    implementation("androidx.camera.viewfinder:viewfinder-view:1.4.0-alpha13")

    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
    implementation("androidx.media3:media3-datasource-rtmp:1.8.0")

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
