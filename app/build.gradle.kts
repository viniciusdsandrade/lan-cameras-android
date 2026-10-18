import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Assinatura do release com um keystore LOCAL, fora do repositório (~/.config/lan-cameras/android.jks),
// cujas senhas chegam por variáveis de ambiente (scripts/instalar_android.sh lê ~/.config/lan-cameras/android.env).
val keystoreLocal: String? = System.getenv("CAMERAS_KEYSTORE")

android {
    namespace = "com.lancameras.android"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.lancameras.android"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreLocal != null) {
            create("local") {
                storeFile = file(keystoreLocal)
                storePassword = System.getenv("CAMERAS_KEYSTORE_SENHA")
                keyAlias = System.getenv("CAMERAS_KEYSTORE_ALIAS") ?: "cameras"
                keyPassword = System.getenv("CAMERAS_KEYSTORE_SENHA")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("local") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.add("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")
        }
    }

    buildFeatures {
        compose = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
        lintConfig = file("lint.xml")
    }

    testOptions {
        // os testes de unidade rodam na JVM; o pouco de Android que encontrarem devolve valores padrão
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":nucleo"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
