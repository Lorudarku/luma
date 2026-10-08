plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.okami.luma"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // Las pruebas en un teléfono personal deben usar otro sandbox y nunca desinstalar la app real.
        applicationId = if (providers.gradleProperty("luma.validation").orNull == "true")
            "dev.okami.luma.validation" else "dev.okami.luma"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isDebuggable = false
            optimization {
                enable = true
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    sourceSets {
        getByName("androidTest").assets.directories.add("$projectDir/schemas")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Biometric 1.1 arrastra Fragment 1.2: Activity Result requiere una versión compatible explícita.
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.room.paging)
    implementation(libs.sqlcipher)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.kotlinx.coroutines.android)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
    constraints {
        // Room Testing solicita JSON 1.8.1, pero AGP alinea core con el 1.7.3 de la app.
        // Mantener ambos serializadores en 1.7.3 solo en tests evita AbstractMethodError.
        androidTestImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3") {
            version { strictly("1.7.3") }
            because("Room MigrationTestHelper necesita JSON compatible con el core compartido por la app")
        }
    }
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
