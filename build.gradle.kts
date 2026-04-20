import org.gradle.kotlin.dsl.invoke
import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode

plugins {
    kotlin("jvm") version "2.3.10"
    `java-library`
    `maven-publish`
}

//val klerkVersion = "1.0.0-beta.6"
val klerkVersion = "1.0.0-beta.7-SNAPSHOT"
val ktorVersion = "3.2.3"
val gsonVersion = "2.9.0"
val kotlinLoggingVersion = "2.1.21"
val sqliteJdbcVersion = "3.44.1.0"
val slf4jVersion = "2.0.3"
val datetimeVersion = "0.7.1"

group = "dev.klerkframework"
version = "1.0.0-alpha.2-SNAPSHOT"

dependencies {
    implementation("dev.klerkframework:klerk:$klerkVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:${datetimeVersion}")
    implementation("io.ktor:ktor-server-html-builder:$ktorVersion")
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-auth:$ktorVersion")
    implementation("io.ktor:ktor-server-sessions-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("com.google.code.gson:gson:$gsonVersion")
    implementation("io.micrometer:micrometer-core:1.12.3") // TODO this should not be necessary? It seems to be required when copying a config (e.g. in a plugin). Do we really want all klerk-based application to require this?
    implementation("io.ktor:ktor-server-compression:${ktorVersion}") // TODO: remove?
    implementation("io.github.microutils:kotlin-logging-jvm:$kotlinLoggingVersion")
    testImplementation(kotlin("test"))
    testImplementation("io.micrometer:micrometer-core:1.12.3")
    testImplementation("org.xerial:sqlite-jdbc:$sqliteJdbcVersion")
    testImplementation("org.slf4j:slf4j-simple:$slf4jVersion")
    testImplementation("dev.klerkframework:klerk:${klerkVersion}")
    testImplementation("io.ktor:ktor-server-html-builder:${ktorVersion}")
    testImplementation("io.ktor:ktor-server-core-jvm:${ktorVersion}")
    testImplementation("io.ktor:ktor-server-netty-jvm:${ktorVersion}")
    testImplementation("io.ktor:ktor-server-auth:${ktorVersion}")
    testImplementation("io.ktor:ktor-server-sessions-jvm:${ktorVersion}")
    testImplementation("io.ktor:ktor-client-core:${ktorVersion}")
    testImplementation("io.ktor:ktor-client-cio:${ktorVersion}")
    testImplementation("io.ktor:ktor-server-test-host:${ktorVersion}")
    //testImplementation("org.jetbrains.kotlin:kotlin-test")
    //testImplementation("org.jetbrains.kotlin:kotlin-test-junit")

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
