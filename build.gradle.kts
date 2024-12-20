plugins {
    val kotlinVersion = "1.8.10"
    kotlin("jvm") version kotlinVersion
    kotlin("plugin.serialization") version kotlinVersion

    id("net.mamoe.mirai-console") version "2.16.0"
}

group = "top.jie65535.mirai"
version = "1.3.0"

repositories {
    mavenCentral()
    maven("https://maven.aliyun.com/repository/public")
}

val openaiClientVersion = "3.8.2"
val ktorVersion = "2.3.12"
val jLatexMathVersion = "1.0.7"
val commonTextVersion = "1.13.0"

dependencies {
    implementation("com.aallam.openai:openai-client:$openaiClientVersion")
    implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
    //implementation("io.ktor:ktor-client-okhttp-jvm:$ktorVersion")
    implementation("org.scilab.forge:jlatexmath:$jLatexMathVersion")
    implementation("org.apache.commons:commons-text:$commonTextVersion")
}