import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 高德 Key 不入库：优先级 环境变量 AMAP_KEY（CI Secret）> local.properties 的 amapKey（本地）> 占位符
val amapKey: String
    get() {
        val fromEnv = System.getenv("AMAP_KEY")
        if (!fromEnv.isNullOrBlank()) return fromEnv.trim()
        val props = Properties()
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { props.load(it) }
        return props.getProperty("amapKey", "").trim().ifBlank { "REPLACE_WITH_YOUR_AMAP_KEY" }
    }

android {
    namespace = "com.guardian.link"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.guardian.link"
        minSdk = 27
        targetSdk = 35
        versionCode = 2
        versionName = "2.0.0"
        manifestPlaceholders["AMAP_KEY"] = amapKey
    }

    signingConfigs {
        // 固定调试证书入库：保证 SHA1 稳定，便于在高德开放平台绑定包名+SHA1
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "guardianlink2026"
            keyAlias = "guardianlink"
            keyPassword = "guardianlink2026"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
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
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // 高德 3D 地图 SDK（Maven Central，已核实版本存在）
    // 注意：3dmap 已内置 com.amap.api.location.* 定位类，勿再单独引入 com.amap.api:location，否则重复类冲突
    implementation("com.amap.api:3dmap:9.8.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // 本地单测中 android.jar 的 org.json 是桩类，引入真实实现
    testImplementation("org.json:json:20231013")
}
