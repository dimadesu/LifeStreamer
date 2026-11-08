// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    dependencies {
        // Align AGP 9 built-in Kotlin version with StreamPack (Kotlin 2.4.10)
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.legacy.kapt) apply false
}