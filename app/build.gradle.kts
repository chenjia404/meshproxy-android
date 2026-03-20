plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.devtools.ksp)
}

android {
    namespace = "com.github.chenjia404.meshproxy.android"
    compileSdk = 36
    ndkVersion = "27.3.13750724"

    defaultConfig {
        applicationId = "com.github.chenjia404.meshproxy.android"
        minSdk = 28
        targetSdk = 35
        versionCode = 16
        versionName = "1.1.6"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            ndkBuild {
                arguments += listOf(
                    "APP_CFLAGS+=-DPKGNAME=com/github/chenjia404/meshproxy/android",
                    "APP_CFLAGS+=-DCLSNAME=ProxyService"
                )
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/hev-socks5-tunnel/Android.mk")
        }
    }
}

// Rename assembled APK output to include versionName.
// Kotlin DSL + AGP 9 doesn't expose the same applicationVariants APIs reliably,
// so we rename the produced APK file after assemble.
afterEvaluate {
    val versionName = android.defaultConfig.versionName ?: "0"

    fun renameApk(assembleTaskName: String, buildType: String) {
        tasks.named(assembleTaskName).configure {
            doLast {
                val outDir = layout.buildDirectory.dir("outputs/apk/$buildType").get().asFile
                val apks = outDir.listFiles { f -> f.isFile && f.name.endsWith(".apk") } ?: emptyArray()
                val apk = apks.maxByOrNull { it.lastModified() } ?: return@doLast

                val newFileName = apk.name
                    .replace("app", "meshproxy-android-v${versionName}")
                    .replace(".apk", ".apk")
                val newFile = File(outDir, newFileName)
                if (newFile.exists()) newFile.delete()
                apk.renameTo(newFile)
            }
        }
    }

    renameApk("assembleDebug", "debug")
    renameApk("assembleRelease", "release")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.core)
    testImplementation(libs.androidx.junit)
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.runner)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
