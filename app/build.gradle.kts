import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

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
        versionName = "1.19.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            // Read from keystore.properties file
            val keystorePropertiesFile = rootProject.file("keystore.properties")
            if (keystorePropertiesFile.exists()) {
                val keystoreProperties = Properties()
                keystoreProperties.load(FileInputStream(keystorePropertiesFile))
                
                storeFile = file(keystoreProperties["storeFile"].toString())
                storePassword = keystoreProperties["storePassword"].toString()
                keyAlias = keystoreProperties["keyAlias"].toString()
                keyPassword = keystoreProperties["keyPassword"].toString()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    
    applicationVariants.all {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "LifeStreamer-v${versionName}-${name}.apk"
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

    implementation("io.github.thibaultbee.streampack:streampack-core:3.0.1-SNAPSHOT")
    implementation("io.github.thibaultbee.streampack:streampack-ui:3.0.1-SNAPSHOT")
    implementation("io.github.thibaultbee.streampack:streampack-services:3.0.1-SNAPSHOT")
    implementation("io.github.thibaultbee.streampack:streampack-rtmp:3.0.1-SNAPSHOT")
    implementation("io.github.thibaultbee.streampack:streampack-srt:3.0.1-SNAPSHOT")

    implementation("com.herohan:UVCAndroid:1.0.11")
    implementation("com.google.code.gson:gson:2.11.0")

    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
    implementation("androidx.media3:media3-datasource-rtmp:1.8.0")

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

// Custom AAB output names - create copy so Android Studio can still locate original
afterEvaluate {
    tasks.named("bundleRelease").configure {
        doLast {
            val originalBundle = file("${projectDir}/release/app-release.aab")
            if (originalBundle.exists()) {
                val newName = "LifeStreamer-v${android.defaultConfig.versionName}-release.aab"
                val newFile = File(originalBundle.parent, newName)
                Files.copy(originalBundle.toPath(), newFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}
