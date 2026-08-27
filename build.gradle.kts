plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

group = "com.pi"
version = "1.4.0"

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
        // token 优先级：环境变量 > .env 文件（.env 已 gitignore）
        val envFile = rootProject.file(".env")
        token = providers.environmentVariable("JETBRAINS_TOKEN")
            .orElse(providers.gradleProperty("JETBRAINS_TOKEN"))
            .orElse(providers.provider {
                if (envFile.exists()) envFile.readLines().firstOrNull { it.startsWith("JETBRAINS_TOKEN=") }?.substringAfter("=") else null
            })
            .getOrElse("")
    }
}

tasks {
    buildSearchableOptions {
        enabled = false
    }
    // 单一源头：pi-extension/idea-selection.ts 直接打进 jar，插件启动时自动部署到 ~/.pi/agent/extensions/
    processResources {
        from("pi-extension/idea-selection.ts") {
            into("pi")
        }
    }
    patchPluginXml {
        sinceBuild = "242"
        untilBuild = provider { null }
    }
}
