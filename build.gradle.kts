// O módulo Android usa o Kotlin embutido do AGP 9; o núcleo é Kotlin/JVM puro, testado sem emulador.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
