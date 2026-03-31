// src/main/kotlin/avishgreen/amvera/checkDeployFlow/services/FlowExecutorService.kt

package avishgreen.amvera.checkDeployFlow.services

import avishgreen.amvera.checkDeployFlow.enums.ProjectStatus
import avishgreen.amvera.checkDeployFlow.flows.IFlow
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.Tracing
import com.microsoft.playwright.options.AriaRole
import com.microsoft.playwright.options.WaitForSelectorState
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import kotlinx.coroutines.slf4j.MDCContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationContext
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.UUID
import java.util.concurrent.Executors


//private val log = KotlinLogging.logger {}
private val log = KotlinLogging.logger("FLOW_FILE_LOGGER")

// Константы для таймаута
internal  const val MAX_WAIT_TIME_MS = 300_000.0 // 5 минут - задержка прежде чем ожидание выдаст ошибку
internal  const val POLL_INTERVAL_MS = 10_000L // 10 секунд - пауза между обновлениями в интерфейсе

// Список промежуточных статусов (для логирования, но не для выхода по ошибке)
internal  val INTERMEDIATE_STATUSES = setOf(ProjectStatus.BUILDING, ProjectStatus.RUNNING)

@Service
class FlowExecutorService(
    private val context: ApplicationContext,
    @param:Value("\${playwright.headed}") private val headed: Boolean,
    @param:Value("\${amvera.test-project-name}") internal val amveraTestProjectName: String,
    @param:Value("\${amvera.username}") internal val amveraUsername: String,
    @param:Value("\${amvera.password}") internal val amveraPassword: String,
    @param:Value("\${amvera.host}") internal val amveraHost: String,
    @param:Value("\${amvera.isAmvera}") private val isAmvera: Boolean
){

    // Создаем область видимости для фоновых задач
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Инициализируем один раз при старте сервиса. by lazy создаст объект при первом вызове.
    private val playwright by lazy { Playwright.create() }

    // Один экземпляр браузера для всего приложения.
    // Это экономит 100-200 МБ оперативной памяти на каждом запуске флоу.
    private val browser by lazy {
        playwright.chromium().launch(
            com.microsoft.playwright.BrowserType.LaunchOptions()
                .setHeadless(!headed)
                .setArgs(listOf("--start-maximized"))
        )
    }

    val activeTasks = ConcurrentHashMap<String, String>()

    @PreDestroy
    fun shutdownService() {
        log.info("Завершение работы сервиса: остановка всех запущенных корутин...")
        browser.close()
        playwright.close()
        serviceScope.cancel()
    }

    /**
     * Запускаем основные флоу тестирования
     * */
    fun executeFlow(flow: IFlow): Job {
        // Создаем диспетчер, который привязан к одному конкретному потоку для этого флоу

        return serviceScope.launch {
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM-dd-HH-mm-ss"))
            val fileName = "${timestamp} ${flow.name}.log"
            activeTasks[fileName] = "Init"

            val contextOptions = Browser.NewContextOptions().apply {
                if (headed) {
                    // В режиме с окном — разрешаем браузеру самому решать размер (подхватит maximized)
                    setViewportSize(null)
                } else {
                    // В фоновом режиме — жестко задаем Full HD, чтобы верстка не поехала
                    setViewportSize(1920, 1080)
                }
            }

            val context = browser.newContext(contextOptions)

            // Начинаем запись трейса
            context.tracing().start(Tracing.StartOptions()
                .setScreenshots(true)
                .setSnapshots(true)
                .setSources(true))
            val page: Page = context.newPage()

            // Устанавливаем таймаут для всех действий (click, fill, и т.д.)
            page.setDefaultTimeout(MAX_WAIT_TIME_MS)

            // Устанавливаем таймаут для всех ожиданий (waitForSelector и т.д.)
            page.setDefaultNavigationTimeout(MAX_WAIT_TIME_MS)

            try {
                withContext(MDCContext(mapOf("logFileName" to fileName.removeSuffix(".log")))) {
                    log.info("--- СТАРТ ФЛОУ в фоне: ${flow.name} ---")

                    activeTasks[fileName] = "Running"
                    flow.execute(this@FlowExecutorService, page)
                    activeTasks[fileName] = "Success"

                    log.info("--- ФЛОУ ЗАВЕРШЕН УСПЕШНО ---")
                }
            } catch (e: Throwable) {
                withContext(MDCContext(mapOf("logFileName" to fileName.removeSuffix(".log")))) {                    activeTasks[fileName] = "Error"

                    log.error("Критическая ошибка ${e.message}")
                    log.error("Делаю скрин fatalerror.jpg")
                    takeScreenshot(page,"fatalerror-${flow.name}.jpg")
                }
            } finally {
                page.close()
                context.close()
            }
        }
    }


    /**
     * Получает текущий текст статуса из элемента на странице.
     */
    internal fun getCurrentStatus(page: Page): ProjectStatus? {
        // Используем тот же локатор, который успешно нашли ранее
        val selector = "p.text-body3.line-clamp-1"
        val statusLocator = page.locator(selector).first()

        // Ждем элемент 5 секунд, чтобы убедиться, что он прогрузился после клика
        // Если его нет, возвращаем null
        return try {
            // Ждем только ПРИСУТСТВИЯ (attached), а не видимости,
            // так как тултипы могут быть капризными
            statusLocator.waitFor(Locator.WaitForOptions()
                .setState(WaitForSelectorState.ATTACHED)
                .setTimeout(2000.0))

            val text = statusLocator.textContent()?.trim()
//            log.info("DEBUG: Текст из селектора: '$text'")

            if (text.isNullOrEmpty()) {
                log.warn("DEBUG: Текст пустой или null")
                return null
            }

            // Парсим текст в Enum
            ProjectStatus.fromText(text)

        } catch (e: Exception) {
            log.error("DEBUG: Ошибка при поиске статуса по селектору '$selector': ${e.message}")
            // Если элемент не найден за 5 секунд, вероятно, статус еще не появился
            null
        }
    }


    /**
     * В цикле ждем нужный статус и уведомляем об изменениях
     * */
    internal suspend fun waitForStatus(needStatus: ProjectStatus, page: Page) {
        log.info("Ожидаем статус ${needStatus.text}")
        val startTime = System.currentTimeMillis()
        val timeoutLimit = MAX_WAIT_TIME_MS*10

        page.waitForTimeout(5000.0) // Даем 5 секунд на начало процесса и смену статуса


        while (System.currentTimeMillis() - startTime < timeoutLimit) {
            // Считываем текущий статус
            val currentStatus = getCurrentStatus(page)

            if (currentStatus == needStatus) {
                log.info("✅ Успех: Достигнут статус '${needStatus.text}'")
                return
            }

            if (currentStatus == null) {
                log.error("Не удалось считать статус")
                delay(1000)
//                throw RuntimeException("Статус не читается")
            }

            log.info("Текущий статус: ${currentStatus?.text}. Ждем...")

            // освобождаем поток, пока спим
            delay(POLL_INTERVAL_MS)
        }

        // Ошибка кидается ТОЛЬКО если вышли из цикла по времени
        throw RuntimeException("Таймаут: Статус '${needStatus.text}' не достигнут за ${timeoutLimit/1000} сек.")
    }

    internal suspend fun pushStopButton(page: Page) {
        // Находим саму SVG-иконку по ее классу 'lucide-pause'
        val pauseIconSelector = page.locator(".lucide.lucide-pause")
        pauseIconSelector.waitFor()
        pauseIconSelector.click()

        waitForStatus(ProjectStatus.STOPPED, page)
    }

    internal suspend fun pushBuildButton(page: Page) {
        log.info("Находим ссылку по тексту \"Конфигурация\" и кликаем")
        page.getByRole(AriaRole.LINK, Page.GetByRoleOptions().setName("Конфигурация")).click()

        page.locator("button:has-text('Собрать')").waitFor()
        page.waitForTimeout(10000.0)

        // Нажимаем кнопку "Собрать"
        page.getByText("Собрать").click()

        waitForStatus(ProjectStatus.SUCCESS, page)

    }

    internal suspend fun pushLogin(page: Page) {
        // Начинаем навигацию с https://cloud.amvera.ru
        // Playwright автоматически перейдет на https://id.amvera.ru/
        log.warn("ТЕСТИРУЮ $amveraHost")
        page.navigate(amveraHost)

        //log.info("Текущая страница логина: ${page.url()}")

        // Вводим логин и пароль
        log.info("Используем логин: $amveraUsername")
        page.fill("#username", amveraUsername)
        page.fill("#password", amveraPassword)

        // Нажимаем кнопку "Войти"
        page.click("#kc-login")

        // Ждем переадресации на дашборд
        // Ждем, пока URL перестанет быть id.amvera.ru и вернется на cloud.amvera.ru с /dashboard
        page.waitForURL("${amveraHost}/projects")

        log.info("Успешно залогинились. Текущая страница: ${page.title()}")
        takeScreenshot(page, "login_success.png")
        log.info("Скриншот логина сохранен как login.png")
    }

    fun getProjectLocator(projectName:String, page: Page):Locator {
        // URL для страницы проектов
        val projectsUrl = "${amveraHost}/projects/applications"

        // Навигация на страницу проектов
        page.navigate(projectsUrl)

        // Ждем, пока на странице появится хотя бы один элемент,
        // который является карточкой проекта (тег <a> с href="/projects/applications/").

        // Селектор для ожидания загрузки контейнера:
        val projectListContainerSelector = "a[href*='/projects/applications/']"

        // Playwright будет ждать до 30 секунд (по умолчанию), пока этот элемент
        // не появится в DOM и не станет видимым.
        page.waitForSelector(projectListContainerSelector)

        //Ищем элемент <a>,
        // у которого атрибут href содержит /projects/applications/{имя_проекта}
        // Мы преобразуем имя проекта, убирая пробелы и переводя в нижний регистр,
        // чтобы соответствовать формату в href.
        val projectUrlPart = "/projects/applications/${projectName.replace(" ", "-").lowercase()}"

        // Селектор CSS: a[href*='...'] - ищем тег <a>, у которого атрибут href
        // содержит (оператор *) нашу строку projectUrlPart.
        val projectLocator: Locator = page.locator("a[href='$projectUrlPart']")

        // Поскольку элемент должен быть один, берем первый.
        return projectLocator.first()
    }

    internal fun checkIsProjectExists(projectName:String, projectLocator:Locator) : Boolean {
        // Проверяем количество найденных элементов
        val isFound = projectLocator.count() > 0

        if (isFound) {
            log.info("✅ Проект '$projectName' найден.")
        } else {
            log.warn("ℹ️ Проект '$projectName' не найден.")
        }

        return isFound
    }

    internal fun removeProject(projectName: String, projectLocator:Locator, page: Page) {
        require(projectLocator.count() > 0) {
            "ℹ️ Проект '$projectName' не найден для удаления."
        }

        //кликаем по карточке проекта
        projectLocator.click()
        //кликаем по ссылке Управление
        page.getByText("Управление").click()
        //кликаем по вкладке Удаление
        page.getByRole(AriaRole.TAB, Page.GetByRoleOptions().setName("Удаление")).click()
        //жмем кнопку Удалить проект
        page.getByText("Удалить проект").click()
        // Ввод подтверждающей строки
        val confirmationString = "удалить навсегда $projectName"
        // Селектор для поля ввода: используем placeholder или data-sentry-element="Input"
        val inputSelector = "input[placeholder='Указать строку для удаления']"
        page.fill(inputSelector, confirmationString)
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Удалить")).click()
        log.info("Подтверждено окончательное удаление.")

        // Ожидание возвращения на страницу списка проектов
        page.waitForURL("${amveraHost}/projects")

        log.info("✅ Проект '$projectName' успешно удален.")
    }

    internal fun takeScreenshot(page: Page, filename: String) {
        val screenshotPath = Paths.get(getScreenshotsDir(), filename)
        // Создаем директорию 'screenshots', если она не существует
        Files.createDirectories(screenshotPath.parent)
        // Делаем скриншот, используя опцию setFullPage
        val fullPage = false
        page.screenshot(
            Page.ScreenshotOptions()
                .setPath(screenshotPath)
                .setFullPage(fullPage) // Скриншот всей длинной страницы
        )
        log.info("Скриншот сохранен (FullPage: $fullPage): ${screenshotPath.toAbsolutePath()}")
    }

    //Делаем скриншот определенного развернутого локатора
    internal fun takeScreenshot(locator: Locator, filename: String) {
        val screenshotPath = Paths.get(getScreenshotsDir(), filename)
        // Создаем директорию 'screenshots', если она не существует
        Files.createDirectories(screenshotPath.parent)

        val page = locator.page() // Получаем страницу
        val elementHandle = locator.elementHandle() // Получаем ElementHandle для JS

        if (elementHandle != null) {
            // Сохраняем оригинальные стили, которые мы будем менять
            @Suppress("UNCHECKED_CAST")
            val originalStyles = page.evaluate(
                """
                (element) => {
                    const originalHeight = element.style.height;
                    const originalOverflow = element.style.overflow;
                    // Временно растягиваем элемент
                    element.style.height = 'auto';
                    element.style.overflow = 'visible';
                    return { originalHeight, originalOverflow };
                }
                """,
                elementHandle
            ) as Map<String, String> // Kotlin/Java концепция: page.evaluate() возвращает Object, который при маппинге из JS-объекта становится Map<String, Any?>

            // Даем браузеру время на перерисовку
            page.waitForTimeout(100.0)

            val fullPage = true
            page.screenshot(
                Page.ScreenshotOptions()
                    .setPath(screenshotPath)
                    .setFullPage(fullPage) // Скриншот всей длинной страницы
            )

            // Делаем снимок растянутого элемента
//            locator.screenshot(
//                com.microsoft.playwright.Locator.ScreenshotOptions()
//                    .setPath(screenshotPath)
//            )

            log.info("Скриншот элемента сохранен: ${screenshotPath.toAbsolutePath()}")
        }
    }

    /**
     * Определяет папку для сохранения в зависимости от того,
     * запущены мы локально или в облаке.
     */
    private fun getScreenshotsDir(): String {
//        log.info("getScreenshotDirs isAmvera==${isAmvera}")
        return if (isAmvera) "/data/screenshots" else "screenshots"
    }

    suspend fun pushRerunBtton(page: Page) {
        log.info("--> Попытка перезапустить проект")

        // Используем уникальный атрибут компонента для поиска кнопки
        val rerunButton = page.locator("button[data-sentry-component='RerunProject']")

        // Проверяем видимость кнопки перед нажатием
        // В Kotlin Playwright мы можем использовать методы ожидания напрямую из локатора
        rerunButton.waitFor(Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE))

        log.info("Нажимаем кнопку перезапуска")
        // Используем обычный клик, но если кнопка капризная, можно добавить setForce(true)
        rerunButton.click()

        waitForStatus(ProjectStatus.SUCCESS, page)
    }
    internal suspend fun closeAllNotifications(page: Page) {
        // Ищем именно кнопку, которая содержит SVG с классом lucide-x
        // Используем селектор, который точно найдет кликабельный элемент
        val closeButtonSelector = ".notistack-Snackbar button:has(svg.lucide-x)"

        var closedCount = 0

        // Используем count() для проверки наличия, так как isVisible может капризничать при анимации
        while (page.locator(closeButtonSelector).count() > 0 && closedCount < 10) {
            try {
                val btn = page.locator(closeButtonSelector).first()

                // Если кнопка не видна (например, улетает), прерываемся
                if (!btn.isVisible) break

                log.info("Закрываю уведомление #${++closedCount}...")

                // force = true прожмет кнопку, даже если она перекрыта другим уведомлением
                btn.click(Locator.ClickOptions().setForce(true).setTimeout(1000.0))

                // Даем время на срабатывание скрипта на странице
                delay(300)
            } catch (e: Exception) {
                log.warn("Не удалось закрыть уведомление: ${e.message}")
                break
            }
        }
    }
}
