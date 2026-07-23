plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.gofilm.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gofilm.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 51
        versionName = "0.18.8"
        // 域名规划见 server/nginx/alucard-domains.conf 与 README
        // 片库 API：根路径即业务（Nginx 将 / 反代到 film /api/），不再对外暴露 /api/
        buildConfigField("String", "DEFAULT_BASE_URL", "\"https://api.alucard.top/\"")
        buildConfigField("String", "API_ORIGIN", "\"https://api.alucard.top\"")
        buildConfigField("String", "HOME_URL", "\"https://home.alucard.top/\"")
        buildConfigField("String", "DOWNLOAD_URL", "\"https://down.alucard.top/\"")
        buildConfigField("String", "ADS_URL", "\"https://ads.alucard.top/\"")
        buildConfigField("String", "OTA_ORIGIN", "\"https://ota.alucard.top\"")
        buildConfigField("String", "VERSION_URL", "\"https://ota.alucard.top/v.json\"")
        // 仅 arm64，避免多余 ABI；依赖库已 16KB 对齐
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        // 禁止 debug 包：避免系统「调试应用 / 16KB」黄条
        // 日常请用 assembleRelease + installRelease，不要用 Android Studio Run(debug)
        debug {
            isDebuggable = false
            isMinifyEnabled = false
            applicationIdSuffix = ""
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            isDebuggable = false
            isJniDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
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
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // 使用未压缩 + AGP 对齐打包，利于 16KB 页设备
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.datastore:datastore-preferences:1.1.7")

    val room = "2.6.1"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("io.coil-kt:coil-compose:2.7.0")

    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.5.1")

    // 内置种子边下边播（.so 放 jniLibs/arm64-v8a；当前设备 4KB 页可运行）
    implementation("org.libtorrent4j:libtorrent4j:2.1.0-35")
}
