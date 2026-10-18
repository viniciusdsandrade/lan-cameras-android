// O núcleo: protocolos das câmeras (RTSP, RTP, ONVIF, DVRIP), depacotadores de vídeo, A-law,
// descoberta e armazenamento. Sem nenhuma classe do Android, para rodar e ser testado na JVM.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

// Bytecode 17 compilado com o JDK em uso (não há JDK 17 instalado para uma toolchain).
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// `./gradlew :nucleo:run --args="estado"`: a ferramenta de linha de comando (o camspike do Mac)
application {
    mainClass.set("com.lancameras.nucleo.ferramenta.CamSpikeKt")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
