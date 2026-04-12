plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm("desktop")

    sourceSets {
        val commonMain by getting {
            dependencies {
                // Compose Multiplatform
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(compose.animation)
                implementation(compose.components.resources)

                // Coroutines
                implementation(libs.coroutines.core)

                // Ktor Client (shared networking)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.json)
                implementation(libs.ktor.client.websockets)

                // Koin (multiplatform DI)
                implementation(libs.koin.core)
                implementation(libs.koin.compose)
                implementation(libs.koin.compose.viewmodel)

                // kotlinx.serialization
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
            }
        }

        val desktopMain by getting {
            dependencies {
                // Desktop Compose
                implementation(compose.desktop.currentOs)

                // Coroutines Swing dispatcher (required for Desktop Compose)
                implementation(libs.coroutines.swing)

                // vlcj for desktop audio playback
                implementation(libs.vlcj)

                // Ktor CIO engine for desktop
                implementation(libs.ktor.client.cio)

                // Ktor Server (for desktop OAuth login flow)
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.netty)
            }
        }
    }
}
