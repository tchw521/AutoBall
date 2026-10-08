import java.util.Properties

val useRhino: Boolean = (project.findProperty("useRhino") as String?)?.toBoolean() ?: false
val keystorePropsFile = rootProject.file("signing.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.autoball"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.autoball"
        minSdk = 24
        targetSdk = 34
        versionCode = 27
        versionName = "1.12.0"

        buildConfigField("boolean", "USE_RHINO", useRhino.toString())
        buildConfigField("String", "BUILD_ENGINE", "\"${if (useRhino) "rhino" else "quickjs"}\"")

        // 单 ABI 是包体最大的可控项：QuickJS so 约 350KB，只打 arm64 + 通用兜底
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        resourceConfigurations += listOf("zh", "en")
    }

    signingConfigs {
        // 统一签名：debug 与 release 共用同一 keystore。
        // 否则 debug 用本机默认 debug key、release 用另一把（或干脆不签名），
        // 两个包签名不一致 → 装不上 / 无法覆盖升级。
        // keystore 随仓库提供；signing.properties 只是可选覆盖手段。
        val sf = keystoreProps.getProperty("storeFile") ?: "app/autoball.jks"
        val sp = keystoreProps.getProperty("storePassword") ?: "autoball2026"
        val ka = keystoreProps.getProperty("keyAlias") ?: "autoball"
        val kp = keystoreProps.getProperty("keyPassword") ?: "autoball2026"
        if (rootProject.file(sf).exists()) {
            create("unified") {
                storeFile = rootProject.file(sf)
                storePassword = sp
                keyAlias = ka
                keyPassword = kp
            }
        }
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
            isShrinkResources = false
            // 与 release 同签，可直接互相覆盖安装
            if (signingConfigs.findByName("unified") != null) {
                signingConfig = signingConfigs.getByName("unified")
            }
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingConfigs.findByName("unified") != null) {
                signingConfig = signingConfigs.getByName("unified")
            }
        }
    }

    if (!useRhino) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = listOf("-Xno-param-assertions", "-Xno-receiver-assertions")
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/*.kotlin_module",
            "META-INF/*.version",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*"
        )
    }

    lint {
        // 无障碍/悬浮窗类 App 必然触发 lint 告警，不因告警中断 CI
        abortOnError = false
        checkReleaseBuilds = false
        disable += setOf("UnusedResources", "MissingTranslation", "Instantiatable")
    }

    buildFeatures {
        buildConfig = true
        viewBinding = false
    }
}

dependencies {
    // 零第三方运行时依赖：不使用 androidx / 协程 / 序列化库
    // JSON 使用平台自带 org.json
    if (useRhino) {
        implementation("org.mozilla:rhino:1.7.15")
    }
}

// QuickJS 源码体积大且不属于本仓库产出，构建前按需拉取；拉取失败不阻断编译（走 stub 引擎）
tasks.register<Exec>("fetchQuickJs") {
    workingDir = rootProject.projectDir
    commandLine("bash", "scripts/fetch_quickjs.sh")
    isIgnoreExitValue = true
    onlyIf { !useRhino }
}

tasks.register<Exec>("fetchShizukuAidl") {
    workingDir = rootProject.projectDir
    commandLine("bash", "scripts/fetch_shizuku_aidl.sh")
    isIgnoreExitValue = true
}

afterEvaluate {
    val cmakeTasks = tasks.matching { it.name.startsWith("externalNativeBuild") || it.name.contains("CMake") }
    cmakeTasks.configureEach {
        if (!useRhino) dependsOn("fetchQuickJs")
    }
    tasks.matching { it.name.startsWith("compile") && it.name.contains("DebugAidl") || it.name.startsWith("compile") && it.name.contains("ReleaseAidl") }
        .configureEach { dependsOn("fetchShizukuAidl") }
    tasks.matching { it.name.startsWith("preBuild") }.configureEach {
        dependsOn("fetchShizukuAidl")
        if (!useRhino) dependsOn("fetchQuickJs")
    }
}
