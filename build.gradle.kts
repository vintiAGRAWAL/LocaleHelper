plugins {
    kotlin("jvm") version "1.9.23"
    id("org.jetbrains.intellij") version "1.17.3"
}

group = "com.vinti"
version = "1.0.1"

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    testImplementation(kotlin("test"))
    implementation("org.yaml:snakeyaml:2.0")
    implementation("org.apache.opennlp:opennlp-tools:2.2.0")
}

intellij {
    version.set("2024.1")
    type.set("IU")
    plugins.set(listOf("java"))
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        jvmTarget = "17"
    }
}

tasks.named<org.jetbrains.intellij.tasks.PublishPluginTask>("publishPlugin") {
    token.set(System.getenv("JB_MARKETPLACE_TOKEN"))
    channels.set(listOf("beta", "stable"))
}

tasks.test {
    useJUnitPlatform()
}