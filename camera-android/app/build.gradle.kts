import com.google.protobuf.gradle.proto

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.protobuf")
}

android {
    namespace = "com.cineremote.camera"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cineremote.camera"
        minSdk = 30            // CONTROL_ZOOM_RATIO, MediaStore paths
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            // The remote-control schema is shared with the desktop app: cineremote/proto/*.proto
            proto { srcDir("../../cineremote/proto") }
        }
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:4.35.0" }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins { create("java") { option("lite") } }
        }
    }
}

dependencies {
    implementation("com.google.protobuf:protobuf-javalite:4.35.0")
    implementation("io.getstream:stream-webrtc-android:1.3.8")
    implementation("org.java-websocket:Java-WebSocket:1.6.0")
}
