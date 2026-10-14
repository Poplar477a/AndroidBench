import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release 签名：口令放在 keystore.properties（已在 .gitignore 中排除）。
// 文件不存在时 release 走未签名产物，不阻塞普通构建。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}
val hasReleaseKeystore = keystorePropsFile.exists()

android {
    namespace = "com.ufs.bench"
    compileSdk = 34
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "com.ufs.bench"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-O3", "-fexceptions", "-frtti")
                // 用静态 C++ 运行时：既避免随包携带 NDK 预编译的 libc++_shared.so
                // （该库在 NDK r25c 下仍是 4KB 对齐，会导致 Android 15+ 的
                //  "16 KB 对齐检查失败" 警告），也顺带减小 APK 体积。
                arguments += listOf("-DANDROID_STL=c++_static")
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            }
        }

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        create("release") {
            if (hasReleaseKeystore) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    flavorDimensions += "edition"

    productFlavors {

        create("standard") {

            dimension = "edition"

            applicationIdSuffix = ""

            manifestPlaceholders["appName"] = "UFS Benchmark"

            buildConfigField("Boolean", "FEATURE_ROOT_LAB", "false")

            buildConfigField("Boolean", "FEATURE_EXTERNAL_SPEC", "false")

        }

        create("lab") {

            dimension = "edition"

            applicationIdSuffix = ".lab"

            manifestPlaceholders["appName"] = "UFS Benchmark 极客实验室"

            buildConfigField("Boolean", "FEATURE_ROOT_LAB", "true")

            buildConfigField("Boolean", "FEATURE_EXTERNAL_SPEC", "true")

        }

    }


    buildTypes {
        release {
            // R8 代码压缩 + 资源压缩。JNI 按名字查找的类型已在
            // proguard-rules.pro 中显式 keep，否则会在运行时 abort。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isDebuggable = true
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
        prefab = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.1")
    implementation("androidx.activity:activity-compose:1.9.0")

    // Jetpack Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.05.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // JSON serialization
    // 已移除 org.json:json（含 Good not Evil 限制的非自由许可）；改用 Android 框架内置 org.json（API 完全兼容）

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}