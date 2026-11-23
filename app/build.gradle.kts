import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.liu.gallery"
    compileSdk = 36      // backdrop(液态玻璃) 要求 36；targetSdk 仍为 35

    defaultConfig {
        applicationId = "com.liu.gallery"
        minSdk = 26          // 覆盖 Android 8.0 及以上
        targetSdk = 35
        versionCode = 3
        versionName = "1.0.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // 使用 debug 签名，方便与 debug 版本覆盖安装（仅用于真机体验 Release 性能）
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Kotlin 编译目标（KGP 2.3 起 kotlinOptions 已移除，改用 compilerOptions）
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Compose BOM 统一管理 compose 版本
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // 本地图片加载（无网络模块，纯离线）
    implementation("io.coil-kt.coil3:coil-compose:3.0.4")

    // 视频播放
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    // 右边缘快速滚动条（Compose 原生，LazyVerticalGrid 支持，MIT；compileSdk 34 兼容版）
    implementation("com.github.nanihadesuka:LazyColumnScrollbar:2.2.0")

    // EXIF 属性读取
    implementation("androidx.exifinterface:exifinterface:1.4.2")

    // 液态玻璃：Android Liquid Glass（Backdrop，纯本地渲染，无网络）
    implementation("io.github.kyant0:backdrop:1.0.6")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // 捏合手势真机注入测试（UiAutomation 多指）
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
