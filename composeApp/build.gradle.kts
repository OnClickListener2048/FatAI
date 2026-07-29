@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
    alias(libs.plugins.kover)
    id("app.cash.sqldelight") version "2.1.0" apply false // 确保版本号统一且 apply false
}



kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "FatAI"
            isStatic = false
            export("io.insert.koin:koin-core")
        }
    }
    
    jvm()
    /*
    js {
        browser()
        binaries.executable()
    }
    
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }
    */
    sourceSets {
        androidMain.dependencies {
            implementation(compose.preview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.ktor.client.android)
            implementation(libs.ktor.client.okhttp)
            // Koin Android specific helpers (e.g., for androidContext())
            implementation(libs.koin.android)
            implementation(libs.landscapist.coil3)


        }
        appleMain.dependencies {
            implementation(libs.ktor.client.darwin)

        }
        commonMain.dependencies {
            // Koin 核心库
            implementation(libs.koin.core)
            // Koin for Compose Multiplatform (提供 koinInject() 等)
            implementation(libs.koin.compose)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor3)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.coroutines.core)
            implementation(projects.shared)
            implementation(project(":core"))
            implementation(project(":database"))
            implementation(project(":feature-model"))
            implementation(project(":feature-workspace"))
            implementation(project(":feature-memory"))
            implementation(project(":feature-files"))
            implementation(project(":feature-prompt"))
            implementation(project(":feature-chat"))
            implementation(project(":feature-tools"))
            implementation(project(":feature-settings"))
            implementation(project(":feature-user"))
            implementation(libs.landscapist.placeholder)
            implementation(libs.composeIcons.feather)
            // Multiplatform

            implementation(libs.decompose)
            implementation(libs.decompose.compose)

            // Koin integration

            // Android

            // Hilt integration


            // Desktop + Android

            // Kodein integration

            // RxJava integration



            // Ktor 核心客户端
            implementation(libs.ktor.client.core)

            // 1. Ktor 引擎 (Engine) - 这是 Ktor 的核心概念！
            // Ktor 需要为每个平台指定一个具体的 HTTP 请求实现。
            // 你需要为你支持的每个平台都添加一个引擎。
            // Ktor 会在编译时自动选择正确的引擎。

            // 为 Android 添加 OkHttp 引擎

            // 为 JVM/Desktop 添加 CIO 引擎 (也可以用 OkHttp)
            implementation(libs.ktor.client.cio)

            // 2. Ktor 功能插件 (Plugins) - 按需添加
            // 内容协商插件，用于自动处理 JSON
            implementation(libs.ktor.client.content.negotiation)
            // 使用 kotlinx.serialization 进行 JSON 解析
            implementation(libs.ktor.serialization.kotlinx.json)

            // 日志插件，方便调试
            implementation(libs.ktor.client.logging)

            
            implementation(libs.filekit.core)
            implementation(libs.filekit.dialogs)
            implementation(libs.filekit.dialogs.compose)
            implementation(libs.filekit.coil)
            implementation(libs.landscapist.coil3)
            implementation(libs.zoomable)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmMain.dependencies {
            // JVM/Desktop 平台的数据库驱动
            implementation(libs.sqlite.driver)
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutinesSwing)
            implementation(libs.ktor.client.java)
        }
    }
}

android {
    namespace = "ai.fatai"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "ai.fatai.app"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    debugImplementation(compose.uiTooling)
}

compose.desktop {
    application {
        mainClass = "ai.fatai.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "FatAI"
            packageVersion = "1.0.0"
        }
    }
}
