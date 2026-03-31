//src/main/kotlin/avishgreen/amvera/checkDeployFlow/controllers/AdminController.kt
package avishgreen.amvera.checkDeployFlow.controllers

import avishgreen.amvera.checkDeployFlow.flows.IFlow
import avishgreen.amvera.checkDeployFlow.services.FlowExecutorService
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import java.io.File

@Controller
class AdminController(
    private val flowExecutorService: FlowExecutorService,
    private val flows: List<IFlow>,
    @Value("\${LOG_PATH:test_results}")
    private val logsDir: String
) {

    data class LogRow(
        val fileName: String,
        val status: String?
    )

    @GetMapping("/task-status")
    fun getTasksStatus(model: Model): String {
        val folder = File(logsDir)

        // Список файлов на диске
        val fileNames = folder.listFiles { file ->
            file.isFile && file.extension == "log" && file.name != "application.log"
        }?.map { it.name } ?: emptyList()

        // '+' объединяет коллекции, .distinct() убирает дубли
        val allNames = (fileNames + flowExecutorService.activeTasks.keys)
            .distinct()
            .sortedByDescending { it }

        // Преобразуем имена в объекты для шаблона
        val rows = allNames.map { name ->
            LogRow(
                fileName = name,
                status = flowExecutorService.activeTasks[name]
            )
        }

        model.addAttribute("logRows", rows)
        return "fragments/task-list :: task-list"
    }

    @GetMapping("/")
    fun index(model: Model): String {
        // На главной нам теперь нужны только доступные флоу.
        // Список логов подгрузится через HTMX автоматически.
        model.addAttribute("availableFlows", flows)

        return "index"
    }

    @PostMapping("/run-flow")
    fun runFlow(@RequestParam flowName: String, // Получаем имя выбранного флоу
        redirectAttributes: RedirectAttributes
    ): String {
        try {
            // Ищем флоу по имени
            val flow = flows.find { it.name == flowName }
                ?: throw IllegalArgumentException("Флоу с именем $flowName не найден")

            // Запускаем выполнение асинхронно через корутину
            flowExecutorService.executeFlow(flow)

            redirectAttributes.addFlashAttribute("message", "Флоу '${flow.name}' запущен в фоновом режиме! Следите за логом")
        } catch (e: Exception) {
            redirectAttributes.addFlashAttribute("error", "Ошибка при запуске: ${e.message}")
        }

        return "redirect:/"
    }

    @GetMapping("/view-log")
    fun viewLog(@RequestParam name: String, model: Model): String {
        val content = readLogSafely(name)
        if (content != null) {
            // Переворачиваем список строк: последние будут первыми
            // Фильтруем пустые строки, чтобы они не создавали "прыгающие" блоки в начале
            val filteredContent = content.filter { it.isNotBlank() }.reversed()
            model.addAttribute("content", filteredContent)
            model.addAttribute("fileName", name)
        } else {
            model.addAttribute("error", "Файл не найден или доступ запрещен")
            model.addAttribute("fileName", name)
        }
        return "log-view"
    }

    @GetMapping("/view-log-content")
    fun viewLogContent(@RequestParam name: String, model: Model): String {
        val content = readLogSafely(name)
        // Переворачиваем и здесь для единообразия при обновлении
        // Делаем ту же фильтрацию здесь
        val filteredContent = content?.filter { it.isNotBlank() }?.reversed() ?: emptyList<String>()
        model.addAttribute("content", filteredContent)
        return "log-view :: log-body"
    }

    // Приватная функция, чтобы не дублировать проверки безопасности
    private fun readLogSafely(name: String): List<String>? {
        val file = File(logsDir, name)
        val base = File(logsDir).canonicalPath + File.separator

        return if (file.exists() && file.canonicalPath.startsWith(base)) {
            file.readLines() // Это встроенная функция расширения Kotlin, которая заменяет Files.readAllLines
        } else {
            null
        }    }
}