import java.io.File

plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.spring") version "2.2.20" // Добавляем плагин Kotlin для Spring
    id("org.springframework.boot") version "3.3.1" // Плагин Spring Boot
    id("io.spring.dependency-management") version "1.1.6" // Управление зависимостями Spring Boot
    id("io.qameta.allure") version "3.0.1"
    application
}

group = "avishgreen.games.wordgame"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.microsoft.playwright:playwright:1.56.0")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")

    implementation("io.github.microutils:kotlin-logging:3.0.5")
    // Базовая библиотека корутин
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    //поддержка правильной работы MDC с корутинами
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-slf4j:1.9.0")

    // Поддержка реактивности и моста со Spring (полезно для будущего)
//    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.9.0")
    // Поддержка JDK 8+ (для работы с CompletableFuture и прочим)
//    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.9.0")

    // Библиотека для вычисления выражений в logback.xml
//    implementation("org.codehaus.janino:janino:3.1.12")

    testImplementation(kotlin("test"))
}

allure {
    version.set("2.27.0") // Версия самого Allure
    adapter {
        aspectjVersion.set("1.9.20") // Необходим для перехвата шагов (AspectJ)
        frameworks {
            junit5 {
                adapterVersion.set("2.27.0")
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("avishgreen.amvera.checkDeployFlow.CheckDeployFlowApplicationKt")
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8")
}

// Создаем задачу для установки браузеров
tasks.register("installPlaywright") {
    group = "verification"
    description = "Downloads Playwright browsers into the project directory"

    doLast {
        project.javaexec {
// Используем classpath проекта, где уже есть playwright.jar
            classpath = sourceSets["main"].runtimeClasspath
            mainClass.set("com.microsoft.playwright.CLI")
            args = listOf("install")

// ⭐️ Указываем путь внутри build/libs
            // Мы используем layout.buildDirectory, чтобы путь был корректным на любой ОС
            val browsersPath = File(project.layout.buildDirectory.asFile.get(), "libs/pw-browsers")
            environment("PLAYWRIGHT_BROWSERS_PATH", browsersPath.absolutePath)        }
    }

}