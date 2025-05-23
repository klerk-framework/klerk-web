import org.gradle.kotlin.dsl.invoke
import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode

plugins {
    kotlin("jvm") version "2.0.21"
    `java-library`
    `maven-publish`
}

val ktorVersion = "3.1.2"
val klerkVersion = "1.0.0-beta.3"
val gsonVersion = "2.9.0"
val kotlinLoggingVersion = "2.1.21"
val sqliteJdbcVersion = "3.44.1.0"
val slf4jVersion = "2.0.3"

group = "dev.klerkframework"
version = "1.0.0-alpha.2"

dependencies {
    compileOnly("com.github.klerk-framework:klerk:$klerkVersion")
    compileOnly("io.ktor:ktor-server-html-builder:$ktorVersion")
    compileOnly("io.ktor:ktor-server-core-jvm:$ktorVersion")
    compileOnly("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    compileOnly("io.ktor:ktor-server-auth:$ktorVersion")
    compileOnly("io.ktor:ktor-server-sessions-jvm:$ktorVersion")
    compileOnly("io.ktor:ktor-client-core:$ktorVersion")
    compileOnly("io.ktor:ktor-client-cio:$ktorVersion")
    compileOnly("com.google.code.gson:gson:$gsonVersion")
    implementation("io.github.microutils:kotlin-logging-jvm:$kotlinLoggingVersion")
    testImplementation(kotlin("test"))
    testImplementation("org.xerial:sqlite-jdbc:$sqliteJdbcVersion")
    testImplementation("org.slf4j:slf4j-simple:$slf4jVersion")
    testImplementation("com.github.klerk-framework:klerk:${klerkVersion}")
    testImplementation("io.ktor:ktor-server-html-builder:${ktorVersion}")
    testImplementation("io.ktor:ktor-server-core-jvm:${ktorVersion}")
    testImplementation("io.ktor:ktor-server-netty-jvm:${ktorVersion}")
    testImplementation("io.ktor:ktor-server-auth:${ktorVersion}")
    testImplementation("io.ktor:ktor-server-sessions-jvm:${ktorVersion}")
    testImplementation("io.ktor:ktor-client-core:${ktorVersion}")
    testImplementation("io.ktor:ktor-client-cio:${ktorVersion}")
    testImplementation("com.google.code.gson:gson:${gsonVersion}")
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
