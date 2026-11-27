plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.floatingbrowser"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.floatingbrowser"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.register("renameDebugApk") {
    doLast {
        val outputDir = layout.buildDirectory.dir("outputs/apk/debug").get().asFile
        val source = outputDir.resolve("app-debug.apk")
        val destination = outputDir.resolve("Floating Browser.apk")
        if (source.exists()) source.renameTo(destination)
    }
}

tasks.named("assembleDebug") {
    finalizedBy("renameDebugApk")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
