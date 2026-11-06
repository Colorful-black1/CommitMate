import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // 以最低支持版本（2024.1 / build 241）编译，避免误用高版本 API；
        // 2025.3+ 的兼容性由 verifyPlugin 校验。
        intellijIdeaCommunity(providers.gradleProperty("platformVersion").get())
    }

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    // IntelliJ IDEA 2024.1 运行在 Java 17 上，字节码目标必须是 17，
    // 否则 2024.1 会因 UnsupportedClassVersionError 无法加载插件。
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}

intellijPlatform {
    pluginConfiguration {
        name = "Commit Mate"
        version = providers.gradleProperty("pluginVersion")

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
        }
    }

    // 打包时不启动无头 IDE 生成 searchable options，也不做字节码插桩，保持构建轻量可复现。
    buildSearchableOptions = false
    instrumentCode = false

    // 使用 Plugin Verifier 校验目标 IDE 的二进制兼容性
    pluginVerification {
        ides {
            // 最低支持版本
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2024.1")
            // 规格要求的目标版本（2025.3 起 Community 与 Ultimate 合并发布，使用 IU 发行版）
            create(IntelliJPlatformType.IntellijIdea, "2025.3")
            // 再验证一个非 Java 类产品，证明插件不依赖任何语言模块
            create(IntelliJPlatformType.WebStorm, "2025.3")
        }
    }
}
