import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    id("app.cash.sqldelight") version "2.1.0"
}

sqldelight {
    databases {
        create("WatsonDatabase") { packageName.set("ai.fatai.database") }
    }
    linkSqlite = true
}

kotlin {
    androidTarget { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    iosArm64()
    iosSimulatorArm64()
    jvm()
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(libs.runtime)
        }
        androidMain.dependencies { implementation(libs.android.driver) }
        jvmMain.dependencies { implementation(libs.sqlite.driver) }
        nativeMain.dependencies { implementation(libs.native.driver) }
    }
}

android {
    namespace = "ai.fatai.database"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.android.minSdk.get().toInt() }
}
