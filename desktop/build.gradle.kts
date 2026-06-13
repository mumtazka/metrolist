import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.Locale

// JavaFX 21.0.2 ships WebKit 615+ which Google sign-in still accepts.
// 17.x used WebKit 613 which is now blocked.
private val javaFxVersion = "21.0.2"

private fun currentJavaFxClassifier(): String {
    val osName = System.getProperty("os.name").lowercase(Locale.US)
    val arch = System.getProperty("os.arch").lowercase(Locale.US)
    val isArm64 = arch == "aarch64" || arch == "arm64"

    return when {
        osName.contains("win") -> "win"
        osName.contains("mac") && isArm64 -> "mac-aarch64"
        osName.contains("mac") -> "mac"
        osName.contains("linux") && isArm64 -> "linux-aarch64"
        osName.contains("linux") -> "linux"
        else -> error("Unsupported JavaFX platform: $osName / $arch")
    }
}

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm()

    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(project(":shared"))
                implementation(project(":innertube"))
                implementation(compose.desktop.currentOs)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(libs.coroutines.swing)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.okhttp)
                implementation(libs.ktor.client.websockets)
                implementation(libs.kotlinx.serialization.json)
                implementation("com.materialkolor:material-kolor:2.0.0")
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.json)
                implementation("com.github.hypfvieh:dbus-java-core:5.2.0")
                implementation("com.github.hypfvieh:dbus-java-transport-native-unixsocket:5.2.0")
                // JNA for native platform integration (Windows SMTC, macOS NowPlaying)
                implementation("net.java.dev.jna:jna:5.17.0")
                implementation("net.java.dev.jna:jna-platform:5.17.0")
                // JavaFX WebView for Google sign-in dialog
                implementation("org.openjfx:javafx-base:$javaFxVersion:${currentJavaFxClassifier()}")
                implementation("org.openjfx:javafx-controls:$javaFxVersion:${currentJavaFxClassifier()}")
                implementation("org.openjfx:javafx-graphics:$javaFxVersion:${currentJavaFxClassifier()}")
                implementation("org.openjfx:javafx-swing:$javaFxVersion:${currentJavaFxClassifier()}")
                implementation("org.openjfx:javafx-web:$javaFxVersion:${currentJavaFxClassifier()}")
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "TestYtKt"

        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Exe, TargetFormat.Dmg)
            packageName = "Metrolist"
            packageVersion = "13.3.0"
            description = "Metrolist Desktop - YouTube Music Client"
            vendor = "MetrolistGroup"

            linux {
                iconFile.set(project.file("src/jvmMain/resources/icon.png"))
            }
            windows {
                iconFile.set(project.file("src/jvmMain/resources/icon.ico"))
                menuGroup = "Metrolist"
            }
        }
    }
}
