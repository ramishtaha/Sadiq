plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.ramish.sadiq"
    compileSdk {
        version = release(37)
        buildToolsVersion = "37.0.0"
    }

    defaultConfig {
        applicationId = "com.ramish.sadiq"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The shared debug keystore is the one thing that must never change: installing a build signed
    // by a different cert over this package forces an UNINSTALL, which erases the sky and history.
    // The path comes from ~/.gradle/gradle.properties (never committed). If the property is absent we
    // fall back to the machine's generated debug key with a warning, so a fresh checkout still builds.
    val sadiqDebugKeystore = providers.gradleProperty("SADIQ_DEBUG_KEYSTORE").orNull?.takeIf { it.isNotBlank() }
    val sadiqDebugKeystoreFile = sadiqDebugKeystore?.let { file(it) }

    signingConfigs {
        create("sharedDebug") {
            if (sadiqDebugKeystoreFile != null) {
                storeFile = sadiqDebugKeystoreFile
                storePassword = providers.gradleProperty("SADIQ_DEBUG_STORE_PASSWORD").orNull ?: "android"
                keyAlias = "sadiq"
                keyPassword = providers.gradleProperty("SADIQ_DEBUG_KEY_PASSWORD").orNull ?: "android"
            }
        }
    }

    buildTypes {
        debug {
            if (sadiqDebugKeystoreFile?.isFile == true) {
                signingConfig = signingConfigs.getByName("sharedDebug")
            } else {
                logger.lifecycle("Sadiq: SADIQ_DEBUG_KEYSTORE not set — signing with the machine debug key")
            }
        }
        release {
            // The debug APK ships every icon vector and no shrinking (74 MB). The release build is
            // what actually gets installed: R8 strips the unused material-icons-extended classes and
            // resource shrinking drops the unused resources with them.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (sadiqDebugKeystoreFile?.isFile == true) {
                signingConfig = signingConfigs.getByName("sharedDebug")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.adhan)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
