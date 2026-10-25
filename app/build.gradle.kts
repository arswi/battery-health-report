import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 签名密钥读根目录 keystore.properties（该文件不进仓库）。开源仓库没有
// 这个文件时，release 构建产出未签名 APK，任何人都能本地复现构建；
// 作者本机有该文件，构建产物与 Release 发布一致。
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }
        ?.inputStream()?.use { load(it) }
}
val hasSigning = !keystoreProps.getProperty("storeFile").isNullOrEmpty()

android {
    namespace = "com.xiaolei.battery"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.xiaolei.battery"
        minSdk = 26
        targetSdk = 34
        versionCode = 18
        versionName = "4.6.1"
    }

    signingConfigs {
        create("release") {
            if (hasSigning) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = if (hasSigning) signingConfigs.getByName("release") else null
        }
        getByName("debug") {
            // 本机有签名配置时与 release 同签名，方便覆盖安装调试
            signingConfig = if (hasSigning) signingConfigs.getByName("release") else null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // 无 androidx、无 Java 8 脱糖依赖，全部走 framework API
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    packaging {
        resources.excludes += "/META-INF/*"
    }
}

dependencies {
    implementation(project(":core"))
}
