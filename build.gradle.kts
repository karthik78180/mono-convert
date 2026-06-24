plugins {
    kotlin("jvm") version "1.9.24"
    application
}

group = "com.monoconvert"
version = "0.1.0"

repositories {
    mavenCentral()
}

java {
    toolchain {
        // Target is JDK 17; using 21 locally because JDK 17 is not installed on
        // this dev machine. Do not "fix" back to 17 without installing that JDK.
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    implementation("com.github.ajalt.clikt:clikt:4.4.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.1")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.1")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.kotest:kotest-assertions-core:5.9.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("com.monoconvert.cli.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
