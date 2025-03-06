plugins {
    val kotlinVersion = "2.0.20"
    kotlin("jvm") version kotlinVersion
    kotlin("plugin.serialization") version kotlinVersion

    id("net.mamoe.mirai-console") version "2.16.0"
}

group = "top.jie65535.mirai"
version = "1.5.0"

mirai {
    jvmTarget = JavaVersion.VERSION_11
}

repositories {
    mavenCentral()
    maven("https://maven.aliyun.com/repository/public")
}

val openaiClientVersion = "4.0.1"
val ktorVersion = "3.0.3"
val jLatexMathVersion = "1.0.7"
val commonTextVersion = "1.13.0"
val hibernateVersion = "2.9.0"

dependencies {
    implementation("com.aallam.openai:openai-client:$openaiClientVersion")
    implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
    implementation("org.scilab.forge:jlatexmath:$jLatexMathVersion")
    implementation("org.apache.commons:commons-text:$commonTextVersion")

    // 聊天记录插件
    compileOnly("xyz.cssxsh.mirai:mirai-hibernate-plugin:$hibernateVersion")
}