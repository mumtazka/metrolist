plugins {
    alias(libs.plugins.kotlin.serialization)
    application
}

application {
    mainClass.set("com.metrolist.relay.ApplicationKt")
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.coroutines.core)
    implementation("ch.qos.logback:logback-classic:1.5.15")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.metrolist.relay.ApplicationKt"
    }
    // Create fat jar for deployment
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}
