plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

group = "com.fa"
version = "1.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.2.4")
        instrumentationTools()
        pluginVerifier()
    }
    // gson 由 IntelliJ 平台自带，不重复打包；Java-WebSocket 为第三方库需打包
    implementation("org.java-websocket:Java-WebSocket:1.5.7")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

intellijPlatform {
    pluginVerification {
        ides { recommended() }
    }
    publishing {
        // 上传时提供：JETBRAINS_TOKEN=xxx ./gradlew publishPlugin
        token = providers.environmentVariable("JETBRAINS_TOKEN").getOrElse("")
    }
}

tasks {
    buildSearchableOptions {
        enabled = false
    }
    patchPluginXml {
        sinceBuild = "242"
        untilBuild = provider { null }
    }
}
