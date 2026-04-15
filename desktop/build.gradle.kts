import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
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
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.metrolist.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Exe, TargetFormat.Dmg)
            packageName = "Metrolist"
            packageVersion = "1.0.0"
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
