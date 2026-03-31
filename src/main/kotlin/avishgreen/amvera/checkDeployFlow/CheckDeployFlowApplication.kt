// src/main/kotlin/avishgreen/amvera/checkDeployFlow/CheckDeployFlowApplication.kt

package avishgreen.amvera.checkDeployFlow

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit

@SpringBootApplication
class CheckDeployFlowApplication

private val log = LoggerFactory.getLogger("LogCleaner")

fun main(args: Array<String>) {
    //чистим логи
    cleanOldLogs()

    //запускаем Spring
    runApplication<CheckDeployFlowApplication>(*args)}

private fun cleanOldLogs() {
    // Определяем среду через переменную окружения
    val isAmvera = System.getenv("IS_AMVERA")?.toBoolean() ?: true
    val logDirPath = System.getenv("LOG_PATH")?: "test_results"

    log.warn("isAmvera $isAmvera")
    log.warn("logDirPath $logDirPath")

    val logDir = File(logDirPath)
    if (!logDir.exists()) return

    val monthAgo = Instant.now().minus(30, ChronoUnit.DAYS)
    var deletedCount = 0

    logDir.listFiles()?.forEach { file ->
        if (file.isFile && Instant.ofEpochMilli(file.lastModified()).isBefore(monthAgo)) {
            if (file.delete()) {
                log.info("Удален старый лог-файл: ${file.name}")
                deletedCount++
            }
        }
    }

    if (deletedCount > 0) {
        log.info("Очистка завершена. Удалено файлов: $deletedCount")
    } else {
        log.info("Старых логов для удаления не найдено.")
    }
}