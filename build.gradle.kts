import org.gradle.kotlin.dsl.invoke
import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode

plugins {
    kotlin("jvm") version "2.0.21"
    `java-library`
    `maven-publish`
}

val ktorVersion = "2.3.10"
val klerkVersion = "1.0.0-beta.3"
val gsonVersion = "2.9.0"
val kotlinLoggingVersion = "2.1.21"
val sqliteJdbcVersion = "3.44.1.0"
val slf4jVersion = "2.0.3"

group = "dev.klerkframework"
version = "1.0.0-alpha.1"

dependencies {
    implementation("com.github.klerk-framework:klerk:$klerkVersion")
    implementation("io.ktor:ktor-server-html-builder:$ktorVersion")
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-auth:$ktorVersion")
    implementation("io.ktor:ktor-server-sessions-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("com.google.code.gson:gson:$gsonVersion")
    implementation("io.github.microutils:kotlin-logging-jvm:$kotlinLoggingVersion")
    testImplementation(kotlin("test"))
    testImplementation("org.xerial:sqlite-jdbc:$sqliteJdbcVersion")
    testImplementation("org.slf4j:slf4j-simple:$slf4jVersion")
}

publishing {
    publications {
        create<MavenPublication>("Maven") {
            artifactId = "klerk-web"
            from(components["java"])
        }
    }
}

java {
    withSourcesJar()
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
    explicitApi = ExplicitApiMode.Strict
}
