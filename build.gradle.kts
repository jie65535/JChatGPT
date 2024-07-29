plugins {
    val kotlinVersion = "1.9.24"
    kotlin("jvm") version kotlinVersion
    kotlin("plugin.serialization") version kotlinVersion

    id("net.mamoe.mirai-console") version "2.16.0"
}

group = "top.jie65535.mirai"
version = "1.1.0"

repositories {
    mavenCentral()
    maven("https://maven.aliyun.com/repository/public")
}

val openaiClientVersion = "3.8.2"
val ktorVersion = "2.3.12"

dependencies {
    implementation("com.aallam.openai:openai-client:$openaiClientVersion")
    implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
}