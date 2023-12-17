plugins {
    val kotlinVersion = "1.8.10"
    kotlin("jvm") version kotlinVersion
    kotlin("plugin.serialization") version kotlinVersion

    id("net.mamoe.mirai-console") version "2.16.0"
}

group = "top.jie65535.mirai"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://maven.aliyun.com/repository/public")
}

val openaiClientVersion = "3.6.2"
val ktorVersion = "2.3.7"

dependencies {
    implementation("com.aallam.openai:openai-client:$openaiClientVersion")
    implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
}