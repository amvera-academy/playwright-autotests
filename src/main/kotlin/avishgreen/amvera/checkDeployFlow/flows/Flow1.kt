//src/main/kotlin/avishgreen/amvera/checkDeployFlow/services/CheckFlow.kt
package avishgreen.amvera.checkDeployFlow.flows

import avishgreen.amvera.checkDeployFlow.enums.ProjectStatus
import avishgreen.amvera.checkDeployFlow.services.FlowExecutorService
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.LocatorAssertions
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import com.microsoft.playwright.options.WaitForSelectorState
import kotlinx.coroutines.delay
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.time.measureTime

private val log = KotlinLogging.logger("FLOW_FILE_LOGGER")

@Component
class Flow1: IFlow {
    override val name = "Flow1"
    val projectName = "Flow1" //не использовать нижнее подчеркивание, будет стопиться на этапе ввода названия

    override suspend fun execute(runner: FlowExecutorService, page: Page) {
        log.info("Вводим креды")
        runner.pushLogin(page)

        log.info("Закроем все уведомления если есть")
        delay(5000)
        runner.closeAllNotifications(page)


        log.info("Ждем перехода на страницу приложений")

        // Ждем перехода на страницу проекта/сборки
        page.waitForURL("${runner.amveraHost}/projects/applications") // Ждем URL

        log.info("-->Перешли в список проектов")


        log.info("Проверим, существует ли проект в списке, если да - перейдем в него")
        val projectLocator2 = runner.getProjectLocator(projectName, page)
        require(projectLocator2.count() > 0) {
            "ℹ️ Проект '$projectName' не создался."
        }

        projectLocator2.click()
        log.info("Проект успешно найден и открыт")

        //обработаем редкий статус Приложение не развертывалось сразу после создания
        val status = runner.getCurrentStatus(page)
        if(ProjectStatus.NOTDEPLOYED==status) {
            delay(1000)
            val status2 = runner.getCurrentStatus(page)
            if(ProjectStatus.NOTDEPLOYED==status) {
                log.warn("Статус Приложение не развертывалось после создания проекта!")
                log.warn("Принудительно запустим пересборку!")
                runner.pushBuildButton(page)
            }
        }

        //подождем пока проект соберется и запустится, замерим время
        waitSuccessWithMeasureTime(page, runner)

        //загрузим дополнительный файл в репозиторий
        uploadAdditionalFileToRepository(page)

        //загрузим файл в постоянное хранилище
        uploadAdditionalFileToWarehouse(page, runner)

        //проверим что загруженные файлы отображаются в репозитории и пост. хранилище
        checkFilesArePresent(page)

        //загрузим ошибочный файл конфигурации, проверим сообщения об ошибках валидации
        // и ошибку при сборке при лишних полях (в т.ч. уведомление в логе)
        uploadWrongConfigToRepositoryAndTestAlerts(page,runner)

        //теперь загрузим верную конфигурацию и пересоберем проект, убедимся что запустился
        uploadGoodConfigToRepositoryAndRebuild(page,runner)

        // Перезапустим проект
        runner.pushRerunBtton(page)

        //Проверим работу статическоого анализатора
        checkStaticAnalizator(page)

        //Остановим проект
        runner.pushStopButton(page)
    }

    suspend fun checkStaticAnalizator(page: Page){
        log.info("--> Проверим работу стат анализатора")

        log.info("Нужный файл мы уже загрузили в uploadGoodConfigToRepositoryAndRebuild вместе с верным amvera.yml")

        log.info("Переходим в раздел Репозиторий по ссылке")
        page.click("a[href$='/repo']") // Ищем ссылку, заканчивающуюся на /repo
        page.waitForLoadState()

        //Открываем репозиторий Code
        page.getByRole(AriaRole.TAB, Page.GetByRoleOptions().setName("Code")).click()

        log.info("Ищем уведомления стат анализатора для stat_analizator.py")

        // Находим общий блок алерта
        val alert = page.getByRole(AriaRole.ALERT).filter(
            Locator.FilterOptions().setHasText("Файлы требуют внимания")
        )

        // Находим блок конкретно для нашего файла внутри этого алерта
        // В HTML это div, содержащий h3 с именем файла
        val fileBlock = alert.locator("div").filter(
            Locator.FilterOptions().setHas(page.locator("h3",
            Page.LocatorOptions().setHasText("stat-analizator.py")))
        )

        // Текст ошибки, который мы ищем
        val expectedWarningPersistentMount = "Вы пишете файлы вне persistenceMount"
        val expectedWarningSecretKeys = "Обнаружены секретные ключи в файле"

        try {
            // Ждем, пока внутри блока файла появится нужный текст в списке (li)
            val warningLocator = fileBlock.locator("li").filter(
                Locator.FilterOptions().setHasText(expectedWarningPersistentMount)
            )

            warningLocator.waitFor(Locator.WaitForOptions().setTimeout(20000.0))

            log.info("Успех: Найдено предупреждение о persistenceMount для stat_analizator.py")

            // Если нужно вывести полный текст ошибки (включая таймкод в скобках)
            log.info("✅ Полный текст варнинга: ${warningLocator.textContent()}")

        } catch (e: Exception) {
            log.error("ОШИБКА: Не найдено предупреждение '$expectedWarningPersistentMount' для файла stat_analizator.py")
            // Делаем скриншот для отладки, если это предусмотрено вашим фреймворком
            page.screenshot(Page.ScreenshotOptions().setPath(Paths.get("error_alert.png")))
            throw e
        }



        try {
            // Ждем, пока внутри блока файла появится нужный текст в списке (li)
            val warningLocator = fileBlock.locator("li").filter(
                Locator.FilterOptions().setHasText(expectedWarningSecretKeys)
            )

            warningLocator.waitFor(Locator.WaitForOptions().setTimeout(20000.0))

            log.info("✅ Успех: Найдено предупреждение о Secret Keys для stat_analizator.py")

            // Если нужно вывести полный текст ошибки (включая таймкод в скобках)
            log.info("Полный текст варнинга: ${warningLocator.textContent()}")

        } catch (e: Exception) {
            log.error("ОШИБКА: Не найдено предупреждение '$expectedWarningPersistentMount' для файла stat_analizator.py")
            // Делаем скриншот для отладки, если это предусмотрено вашим фреймворком
            page.screenshot(Page.ScreenshotOptions().setPath(Paths.get("error_alert.png")))
            throw e
        }

    }

    private suspend fun uploadGoodConfigToRepositoryAndRebuild(page: Page, runner: FlowExecutorService) {
        log.info("--> Загрузим правильный файл amvera.yml в репозиторий")

//        log.info("Переходим в раздел Репозиторий по ссылке")
        page.click("a[href$='/repo']") // Ищем ссылку, заканчивающуюся на /repo
        page.waitForLoadState()


        //Открываем репозиторий Code
        page.getByRole(AriaRole.TAB, Page.GetByRoleOptions().setName("Code")).click()

        log.info("Нажимаем кнопку 'Загрузить данные'")
        val uploadButton = page.getByRole(
            AriaRole.BUTTON,
            Page.GetByRoleOptions().setName("Загрузить данные")
        )
        uploadButton.click()

        log.info("Загружаем правильный файл amvera.yml и stat-analizator.py")

        // Даем время анимации диалога
        page.waitForSelector("div[role='dialog']")

        //проверим наличие файла
        val filePathAmvera = Paths.get("src/main/resources/load_data2/amvera.yml").toAbsolutePath()
        val filePathStatAnalizator = Paths.get("src/main/resources/load_data2/stat-analizator.py").toAbsolutePath()

        require(Files.exists(filePathAmvera)) {"ОШИБКА: Файл не найден $filePathAmvera" }
        require(Files.exists(filePathStatAnalizator)) {"ОШИБКА: Файл не найден $filePathStatAnalizator" }

//        log.info("загружаем в селектор")
        // Способ А: Берем инпут именно внутри диалога (самый надежный)
        val fileInput = page.locator("div[role='dialog'] input[type='file']")

        // Если их там несколько, берем первый доступный в диалоге
        if (fileInput.count() > 0) {
//            log.info("Найдено инпутов в диалоге: ${fileInput.count()}. Используем первый.")
            fileInput.first().setInputFiles(arrayOf(filePathAmvera,filePathStatAnalizator))
        } else {
            // Резервный способ Б: берем последний инпут на всей странице
            log.warn("В диалоге не найден инпут, пробуем последний инпут на странице")
            page.locator("input[type='file']").last().setInputFiles(arrayOf(filePathAmvera,filePathStatAnalizator))
        }

//        log.info("проверяем галочку")
        //  проверяем появление зеленой галочки
        val successIconSelector = "svg.lucide-file-check.text-status-success"
        page.waitForSelector(
            successIconSelector,
            Page.WaitForSelectorOptions().setState(WaitForSelectorState.VISIBLE)
        )

//        log.info("жмем закрыть")
        // Нажимаем кнопку "Закрыть" в диалоге
        val closeButton = page.getByRole(
            AriaRole.BUTTON,
            Page.GetByRoleOptions().setName("Закрыть")
        )
        closeButton.click()

        log.info("Нажимаем кнопку \"Собрать\"")
        runner.pushBuildButton(page)

        log.info("убедимся что соберется и запустится без ошибок")
        runner.waitForStatus(ProjectStatus.SUCCESS, page)
    }

    private suspend fun uploadWrongConfigToRepositoryAndTestAlerts(page: Page, runner: FlowExecutorService) {
        log.info("--> Загрузим ошибочный файл amvera.yml в репозиторий")

//        log.info("Переходим в раздел Репозиторий по ссылке")
        page.click("a[href$='/repo']") // Ищем ссылку, заканчивающуюся на /repo
        page.waitForLoadState()


        //Открываем репозиторий Code
        page.getByRole(AriaRole.TAB, Page.GetByRoleOptions().setName("Code")).click()

        log.info("Нажимаем кнопку 'Загрузить данные'")
        val uploadButton = page.getByRole(
            AriaRole.BUTTON,
            Page.GetByRoleOptions().setName("Загрузить данные")
        )
        uploadButton.click()

        log.info("Загружаем ошибочный файл amvera.yml")

        // Даем время анимации диалога
        page.waitForSelector("div[role='dialog']")

        //проверим наличие файла
        val filePath = Paths.get("src/main/resources/badConfigExample/amvera.yml").toAbsolutePath()

        require(Files.exists(filePath)) {
            "ОШИБКА: Файл не найден $filePath"
        }

//        log.info("загружаем в селектор")
        // Способ А: Берем инпут именно внутри диалога (самый надежный)
        val fileInput = page.locator("div[role='dialog'] input[type='file']")

        // Если их там несколько, берем первый доступный в диалоге
        if (fileInput.count() > 0) {
//            log.info("Найдено инпутов в диалоге: ${fileInput.count()}. Используем первый.")
            fileInput.first().setInputFiles(filePath)
        } else {
            // Резервный способ Б: берем последний инпут на всей странице
            log.warn("В диалоге не найден инпут, пробуем последний инпут на странице")
            page.locator("input[type='file']").last().setInputFiles(filePath)
        }

//        log.info("проверяем галочку")
        //  проверяем появление зеленой галочки
        val successIconSelector = "svg.lucide-file-check.text-status-success"
        page.waitForSelector(
            successIconSelector,
            Page.WaitForSelectorOptions().setState(WaitForSelectorState.VISIBLE)
        )

//        log.info("жмем закрыть")
        // Нажимаем кнопку "Закрыть" в диалоге
        val closeButton = page.getByRole(
            AriaRole.BUTTON,
            Page.GetByRoleOptions().setName("Закрыть")
        )
        closeButton.click()

        log.info("✅ Загрузка в репозиторий успешно завершена")

        // Находим ссылку по тексту "Конфигурация" и кликаем
        page.getByRole(AriaRole.LINK, Page.GetByRoleOptions().setName("Конфигурация")).click()

        // Путь к зависимостям
        val requirementsLocator = page.locator("input[name='build.requirementsPath']")
        val scriptNameLocator = page.locator("input[name='run.scriptName']")
        //кликаем по неверно заполненным полям чтобы появились сообщения об ошибках
        scriptNameLocator.click()
        requirementsLocator.click()
        // Кликаем по полю порта, чтобы окончательно сбросить фокус с верхних элементов
        val portInput = page.locator("input[name='run.containerPort']")
        log.info("Кликаем по полю порта для сброса фокуса")
        portInput.click()
        page.waitForTimeout(1000.0)


        //requirements.txt
        // Ищем сообщение об ошибке. В HTML оно имеет id, заканчивающийся на -form-item-message
        // Мы можем найти его по тексту, так как он уникален для этого блока
        val requirementsError = page.locator("p:has-text('В коде не найден указанный файл, измените название или проверьте путь. Инструкции по созданию requirements.txt')")
        // Ждем появления ошибки (на случай задержки валидации)
        requirementsError.waitFor(Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE))
        require(requirementsError.isVisible) {
            "ОШИБКА: Сообщение о неверном requirements.txt не появилось"
        }
        log.info("✅ Проверка валидации для requirementsPath пройдена")


        // Имя скрипта
        val scriptError = page.locator("p:has-text('В коде не найден указанный файл, измените название или проверьте путь')")
            .filter(Locator.FilterOptions().setHasNotText("Инструкции по созданию")) // Исключаем первое сообщение, если оно вдруг совпадет по тексту
        scriptError.waitFor(Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE))
        require(scriptError.isVisible) {
            "ОШИБКА: Сообщение о неверном имени скрипта не появилось"
        }
        log.info("✅ Проверка валидации для имени скрипта пройдена!")

//        //заполним поля верными значениями
//        val rightRequirements = "requirements.txt"
//        val rightScriptName = "bot.py"
//        scriptNameLocator.fill(rightScriptName)
//        requirementsLocator.fill(rightRequirements)
//
//        log.info("Нажимаем кнопку \"Применить\"")
//        page.getByText("Применить").click()
//
//        //ждем секунду
//        page.waitForTimeout(1000.0)
//
//        log.info("Повторно нажимаем кнопку \"Применить\"")
//        page.getByText("Применить").click()
//
//        // Ожидаем появления плашки с уведомлением
//        val successToast = page.locator("li[role='status']").filter(
//            Locator.FilterOptions().setHasText("Конфигурация успешно изменена")
//        )// assertThat будет ждать появления элемента (по умолчанию до 5 секунд)
//        assertThat(successToast).isVisible(
//            LocatorAssertions.IsVisibleOptions().setTimeout(15000.0)
//        )
//
//        //ждем секунду
//        page.waitForTimeout(5000.0)

        log.info("Нажимаем кнопку \"Собрать\"")
        page.getByText("Собрать").click()

        log.info("убедимся что статус выпадает в нужную ошибку")
        runner.waitForStatus(ProjectStatus.ERRORINCONFIG, page)

        //убедимся, что в логе сборки появилась ошибка в верхней строчке
        lookForErrorMessageInBuildingLog(page)

    }

    private fun lookForErrorMessageInBuildingLog(page: Page) {
        log.info("--> Найдем сообщение об ошибке конфигурации в логе сборки")

        log.info("Переходим в раздел Логи по ссылке")
        page.click("a[href$='/logs']") // Ищем ссылку, заканчивающуюся на /logs
        page.waitForLoadState()

        //Открываем вкладку Лог сборки
        page.getByRole(AriaRole.LINK, Page.GetByRoleOptions().setName("Лог сборки")).click()

        log.info("Нажимаем кнопку 'Выполнить запрос'")
        val queryButton = page.locator("button[type='submit']:has-text('Выполнить запрос')")
        queryButton.click()

        // Локатор для надписи о загрузке
        val loadingIndicator = page.getByText("Идет загрузка истории...")

        if (loadingIndicator.isVisible()) {
            log.info("Ждем, пока надпись о загрузке исчезнет")
            try {
                loadingIndicator.waitFor(Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN).setTimeout(5000.0))
            } catch (e: Exception) {
                log.warn("Индикатор так и не исчез за 5 секунд, попробуем проверить лог так.")
            }
        }

        // Формируем локатор для первой строки лога (id="log-0")
        // Мы ищем параграф <p> внутри этого блока, так как именно в нем лежит текст сообщения
        val firstLogEntry = page.locator("#log-0 p")

        // Текст, который мы ожидаем увидеть
        val expectedMessage = "Configuration error. File amvera.yaml contains unknown fields: Invalid variable found: var in build section. To create a configuration file, use the builder in the \"Configuration\" section of your project.\n"

        // Используем проверку на точное соответствие текста
        assertThat(firstLogEntry).hasText(
            expectedMessage,
            com.microsoft.playwright.assertions.LocatorAssertions.HasTextOptions().setTimeout(30000.0)
        )
        log.info("✅ В логе сборки найдено сообщение об ошибках в конфигурации!")

    }

    private fun uploadAdditionalFileToRepository(page: Page) {
        log.info("--> Загрузим дополнительный файл bot2.py в репозиторий Code")

//        log.info("Переходим в раздел Репозиторий по ссылке")
        page.click("a[href$='/repo']") // Ищем ссылку, заканчивающуюся на /repo
        page.waitForLoadState()

        log.info("Нажимаем кнопку 'Загрузить данные'")
        val uploadButton = page.getByRole(
            AriaRole.BUTTON,
            Page.GetByRoleOptions().setName("Загрузить данные")
        )
        uploadButton.click()

        log.info("Загружаем файл: bot2.py")

        // Даем время анимации диалога
        page.waitForSelector("div[role='dialog']")

        //проверим наличие файла
        val filePath = Paths.get("src/main/resources/load_data2/bot2.py").toAbsolutePath()

        require(Files.exists(filePath)) {
            "ОШИБКА: Файл не найден $filePath"
        }

        log.info("загружаем в селектор")
        // Способ А: Берем инпут именно внутри диалога (самый надежный)
        val fileInput = page.locator("div[role='dialog'] input[type='file']")

        // Если их там несколько, берем первый доступный в диалоге
        if (fileInput.count() > 0) {
            log.info("Найдено инпутов в диалоге: ${fileInput.count()}. Используем первый.")
            fileInput.first().setInputFiles(filePath)
        } else {
            // Резервный способ Б: берем последний инпут на всей странице
            log.warn("В диалоге не найден инпут, пробуем последний инпут на странице")
            page.locator("input[type='file']").last().setInputFiles(filePath)
        }

        log.info("проверяем галочку")
        //  проверяем появление зеленой галочки
        val successIconSelector = "svg.lucide-file-check.text-status-success"
        page.waitForSelector(
            successIconSelector,
            Page.WaitForSelectorOptions().setState(WaitForSelectorState.VISIBLE)
        )

        log.info("ищем имя в списке")
        // Проверяем наличие текста с именем файла в списке
        val fileEntry = page.locator("span:has-text('./bot2.py')")
        require(fileEntry.isVisible) {
            "ОШИБКА: Файл ./bot2.py не отображается в списке загруженных"
        }

        log.info("жмем закрыть")
        // Нажимаем кнопку "Закрыть" в диалоге
        val closeButton = page.getByRole(
            AriaRole.BUTTON,
            Page.GetByRoleOptions().setName("Закрыть")
        )
        closeButton.click()

        log.info("✅ Загрузка в репозиторий успешно завершена")

    }

    private suspend fun uploadAdditionalFileToWarehouse(page: Page, runner: FlowExecutorService) {
        log.info("--> Загрузим дополнительный файл bot2.py в Постоянное хранилище Data")

//        log.info("Переходим в раздел Репозиторий по ссылке")
        page.click("a[href$='/repo']") // Ищем ссылку, заканчивающуюся на /repo
        page.waitForLoadState()

        //Открываем постоянное хранилище Data
        page.getByRole(AriaRole.TAB, Page.GetByRoleOptions().setName("Data")).click()

        //подождем пока статус проекта прогрузится
        runner.waitForStatus(ProjectStatus.SUCCESS, page)

        log.info("Нажимаем кнопку 'Загрузить данные'")
        val uploadButton = page.getByRole(
            AriaRole.BUTTON,
            Page.GetByRoleOptions().setName("Загрузить данные")
        )
        uploadButton.click()

        log.info("Загружаем файл: bot2.py")

        // Даем время анимации диалога
        page.waitForSelector("div[role='dialog']")

        //проверим наличие файла
        val filePath = Paths.get("src/main/resources/load_data2/bot2.py").toAbsolutePath()

        require(Files.exists(filePath)) {
            "ОШИБКА: Файл не найден $filePath"
        }

        log.info("загружаем в селектор")
        // Способ А: Берем инпут именно внутри диалога (самый надежный)
        val fileInput = page.locator("div[role='dialog'] input[type='file']")

        // Если их там несколько, берем первый доступный в диалоге
        if (fileInput.count() > 0) {
            log.info("Найдено инпутов в диалоге: ${fileInput.count()}. Используем первый.")
            fileInput.first().setInputFiles(filePath)
        } else {
            // Резервный способ Б: берем последний инпут на всей странице
            log.warn("В диалоге не найден инпут, пробуем последний инпут на странице")
            page.locator("input[type='file']").last().setInputFiles(filePath)
        }

        log.info("проверяем галочку")
        //  проверяем появление зеленой галочки
        val successIconSelector = "svg.lucide-file-check.text-status-success"
        page.waitForSelector(
            successIconSelector,
            Page.WaitForSelectorOptions().setState(WaitForSelectorState.VISIBLE)
        )

        log.info("ищем имя в списке")
        // Проверяем наличие текста с именем файла в списке
        val fileEntry = page.locator("span:has-text('./bot2.py')")
        require(fileEntry.isVisible) {
            "ОШИБКА: Файл ./bot2.py не отображается в списке загруженных"
        }

        log.info("жмем закрыть")
        // Нажимаем кнопку "Закрыть" в диалоге
        val closeButton = page.getByRole(
            AriaRole.BUTTON,
            Page.GetByRoleOptions().setName("Закрыть")
        )
        closeButton.click()

        log.info("✅ Загрузка в Data успешно завершена")

    }

    private fun checkFilesArePresent(page: Page) {
        log.info("--> Проверим что файл bot2.py виден в репозитории и пост. хранилище")

//        log.info("Переходим в раздел Репозиторий по ссылке")
        page.click("a[href$='/repo']") // Ищем ссылку, заканчивающуюся на /repo
        page.waitForLoadState()

        //Открываем постоянное хранилище Code
        page.getByRole(AriaRole.TAB, Page.GetByRoleOptions().setName("Code")).click()
        // Ищем span с текстом bot2.py внутри таблицы (tbody)
        val fileLocator1 = page.locator("tbody span:has-text('bot2.py')")

        assertThat(fileLocator1).isVisible(
            LocatorAssertions.IsVisibleOptions().setTimeout(15000.0)
        )
        log.info("✅ Файл успешно найден в списке файлов Репозитория")

        //Открываем постоянное хранилище Data
        page.waitForTimeout(10000.0)
        page.getByRole(AriaRole.TAB, Page.GetByRoleOptions().setName("Data")).click()
        // Ищем span с текстом bot2.py внутри таблицы (tbody)
        val fileLocator2 = page.locator("tbody span:has-text('bot2.py')")

        //Дожидаемся, пока надпись "Пустая папка" исчезнет (станет скрытой)
        assertThat(page.locator("span:has-text('Пустая папка')")).isHidden(
            LocatorAssertions.IsHiddenOptions().setTimeout(30000.0)
        )

        assertThat(fileLocator2).isVisible(
            LocatorAssertions.IsVisibleOptions().setTimeout(15000.0)
        )
        log.info("✅ Файл успешно найден в списке файлов Постоянного хранилища")

    }

    private suspend fun waitSuccessWithMeasureTime(page: Page, runner: FlowExecutorService) {


        var buildDuration = kotlin.time.Duration.ZERO
        var deployDuration = kotlin.time.Duration.ZERO

        // Замеряем сборку
        log.info("--> Наблюдаем за сборкой")
        buildDuration = measureTime {
            log.info("Ожидаем статус RUNNING (Сборка завершена)...")
            runner.waitForStatus(ProjectStatus.RUNNING, page)
        }


        // Замеряем запуск
        log.info("--> Наблюдаем за запуском")
        deployDuration = measureTime {
            log.info("Ожидаем статус SUCCESS (Приложение запущено)...")
            runner.waitForStatus(ProjectStatus.SUCCESS, page)
        }

        log.info("✅ Сборка завершена за $buildDuration. Статус: RUNNING")
        log.info("✅ Запуск завершен за $deployDuration. Статус: SUCCESS")
        log.info("🚀 Общее время: ${buildDuration + deployDuration}")
    }

    private fun fillProjectNameAndTariffInMaster(page: Page) {
        log.info("+++ 1 ШАГ мастера")
        log.info("Заполняем название")
        page.waitForSelector("input[placeholder='Введите название проекта']")
        page.getByPlaceholder("Введите название проекта").fill(projectName)
        log.info("Открываем выпадающий список тарифов и выбираем второй элемент")
        page.getByRole(AriaRole.COMBOBOX).click()
        val tariffOptionLocator = page.getByRole(AriaRole.OPTION).nth(1)//нумерация с 0
        val selectedTariffName = tariffOptionLocator.innerText()
        tariffOptionLocator.click()
        log.info("Выбран тариф: $selectedTariffName")
    }

    private suspend fun loadFilesInMaster(page: Page) {
        log.info("+++ 2 ШАГ мастера")

        log.info("Активируем вкладку Загрузить через интерфейс")
        val interfaceTab = page.getByRole(
            AriaRole.TAB,
            Page.GetByRoleOptions().setName("Через интерфейс")
        )

        // Проверяем, что вкладка видна перед кликом
        interfaceTab.waitFor(Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE))

        // Кликаем по вкладке и ждем, пока вкладка станет активной.
        interfaceTab.click()
        // Ждем, пока атрибут data-state станет "active" (это надежнее, чем waitForLoadState)
        assertThat(interfaceTab).hasAttribute("data-state", "active",
            LocatorAssertions.HasAttributeOptions().setTimeout(5000.0))

        //Загружаем файлы
        // 💡 ВАЖНО: PlayWright нужен АБСОЛЮТНЫЙ путь к файлу.
        val folderToUpload = Paths.get("src/main/resources/load_data")
        val absolutePath = folderToUpload.toAbsolutePath().toString()

        val filePaths = java.nio.file.Files.walk(folderToUpload, 1) // 1 - глубина (только файлы в корне)
            .filter { java.nio.file.Files.isRegularFile(it) } // Исключаем папки
            .map { it.toAbsolutePath() } // Преобразуем Path в абсолютный путь
            .toList()
            .toTypedArray()

        if (filePaths.isEmpty()) {
            log.warn("В папке 'load_data' не найдено файлов для загрузки.")
            return // Прекращаем выполнение, если файлов нет
        }

        log.info("Загружаем файлы из $absolutePath")
        page.setInputFiles("input[type=\"file\"]", filePaths)
        //Создаем селектор для зеленой иконки успеха (SVG с нужными классами)
        val successIconSelector = "svg.lucide-file-check.text-status-success"
        log.info("Ожидаем появления иконки успешной загрузки.")
        page.waitForSelector(
            successIconSelector,
            Page.WaitForSelectorOptions().setState(WaitForSelectorState.VISIBLE)
        )
        log.info("✅ Загрузка файлов завершена. Иконка успеха появилась.")
    }

    fun fillParamsBlockInMaster(page: Page) {
        log.info("+++ 3 ШАГ мастера - заполним переменные")

        val varName1 = "secret1"
        val varValue1 = "SecretValue1"
        val varName2 = "param1"
        val varValue2 = "Value1"

        // Нажимаем кнопку "Добавить переменные и секреты"
        val addVariablesButton = page.getByRole(
            AriaRole.BUTTON,
            Page.GetByRoleOptions().setName("Добавить переменные и секреты")
        )
        log.info("Нажимаем кнопку 'Добавить переменные и секреты'.")
        addVariablesButton.click()

        // Вводим Название и Значение в появившемся окне

        // Используем плейсхолдеры для поиска полей
        val nameInput = page.locator("input[name='envvars.0.name']")
        val valueInput = page.locator("input[name='envvars.0.value']")

        log.info("Вводим переменную '$varName1' со значением '$varValue1'.")
        nameInput.fill(varName1)
        valueInput.fill(varValue1)

        // Ставим флажок "Это секрет"
        // Флажок имеет роль checkbox. Мы можем искать его по доступному имени ("Это секрет").
        val secretCheckbox = page.getByRole(
            AriaRole.CHECKBOX,
            Page.GetByRoleOptions().setName("Это секрет")
        )

        // Проверяем, что флажок не установлен, и кликаем.
        // Если он уже установлен (что маловероятно), .setChecked(true) гарантирует, что он будет установлен.
        if (secretCheckbox.isChecked.not()) { // 💡 Kotlin: .not() эквивалентно !
            log.info("Устанавливаем флажок 'Это секрет'.")
            secretCheckbox.setChecked(true)
        }

        // Добавляем в список переменных дополнительное поле
        //  Нажимаем кнопку "Добавить" в списке уже добавленных переменных
        val addButton = page.getByRole(
            AriaRole.BUTTON,
            Page.GetByRoleOptions().setName("Добавить")
        )
        log.info("Нажимаем кнопку 'Добавить' для создания второй переменной.")
        addButton.click()

        val nameInput2 = page.locator("input[name='envvars.1.name']")

        // Поле Значения: name="envvars.1.value" (второй элемент массива)
        val valueInput2 = page.locator("input[name='envvars.1.value']")

        log.info("Вводим вторую переменную '$varName2' со значением '$varValue2'.")
        nameInput2.fill(varName2)
        valueInput2.fill(varValue2)

        // Нажимаем кнопку "Применить"
        // Кнопка имеет роль BUTTON, текст "Применить", и у нее есть <span>
        val applyButton = page.getByRole(
            AriaRole.BUTTON,
            Page.GetByRoleOptions().setName("Применить")
        )
        log.info("Нажимаем кнопку 'Применить'.")
        applyButton.click()
    }

    fun fillAndCheckConfigStepInMaster(page: Page) {
        log.info("+++ 4 ШАГ мастера - конфигурация")

        log.info("Выбираем Runtime: Python")

        // кликаем по Combobox для открытия списка
        // Находим Python в появившемся списке
        val runtimeCombobox = page.getByText("Выберите окружение").locator("~ button[role=combobox]")
        runtimeCombobox.click()
        val pythonOptionLocator = page.getByRole(AriaRole.OPTION, Page.GetByRoleOptions().setName("Python"))
        pythonOptionLocator.click()

        //проверим заполнение дефолтных полей конфига
        checkDefaultConfigFieldsFillingInMaster(page)

        //проверим валидацию полей конфига на неверные значения
        checkValidationConfigInMaster(page)

        //пропишем нужные поля в конфиге чтобы все собралось
        fillConfigInMaster(page)

    }

    fun checkDefaultConfigFieldsFillingInMaster(page: Page){
        log.info("-->Проверяем заполнение полей по умолчанию в мастере нового проекта на этапе заполнения конфигурации")

        // Проверяем и кликаем второй Combobox (Build Tool) чтобы там стояло pip
        val buildToolCombobox = page.getByText("Выберите инструмент").locator("~ button[role=combobox]")
        // Текст 'pip' находится внутри <span>, который является дочерним элементом <button>


        val defaultBuildTool = buildToolCombobox.innerText().trim()
        // Проверка, что по умолчанию выбран "pip"
        require(defaultBuildTool == "pip") {
            "ОШИБКА: Ожидался Build Tool 'pip', но найден '$defaultBuildTool'"
        }
        log.info("✅ Build Tool по умолчанию: '$defaultBuildTool'. ")

        // Версия Python (name="meta.toolchain.version", ожидаем "3.11")
        val pythonVersionLocator = page.locator("input[name='meta.toolchain.version']")
        val actualVersion = pythonVersionLocator.inputValue()
        require(actualVersion == "3.11") {
            "ОШИБКА: Ожидалась версия Python '3.11', но найдено '$actualVersion'."
        }
        log.info("✅ Версия Python: $actualVersion")


        // Путь к зависимостям (name="build.requirementsPath", ожидаем "requirements.txt")
        val requirementsLocator = page.locator("input[name='build.requirementsPath']")
        val actualRequirements = requirementsLocator.inputValue()
        require(actualRequirements == "requirements.txt") {
            "ОШИБКА: Ожидался путь к зависимостям 'requirements.txt', но найдено '$actualRequirements'."
        }
        log.info("✅ Путь к требованиям: $actualRequirements")


        // Имя скрипта (name="run.scriptName", ожидаем "app.py")
        val scriptNameLocator = page.locator("input[name='run.scriptName']")
        val actualScriptName = scriptNameLocator.inputValue()
//        require(actualScriptName == "app.py") {
//            "ОШИБКА: Ожидалось имя скрипта 'app.py', но найдено '$actualScriptName'."
//        }
        log.info("✅ Имя скрипта: $actualScriptName")

        // Команда запуска (name="run.command", ожидаем пустое поле "")
        // Поле disabled="", но мы все равно можем проверить его значение.
        val commandLocator = page.locator("input[name='run.command']")
        val actualCommand = commandLocator.inputValue()
        require(actualCommand.isBlank()) { // Проверяем, что значение пустое (null, "", или состоит из пробелов)
            // isBlank() проверяет, что строка пуста ИЛИ содержит только пробельные символы.
            // isBlank() == true, если строка "", " ", "\t", "\n" и т.д.
            "ОШИБКА: Ожидалась пустая команда запуска, но найдено '$actualCommand'."
        }
        log.info("✅ Команда запуска: поле пустое")


        // Путь к постоянному хранилищу (name="run.persistenceMount", ожидаем "/data")
        val mountLocator = page.locator("input[name='run.persistenceMount']")
        val actualMount = mountLocator.inputValue()
        require(actualMount == "/data") {
            "ОШИБКА: Ожидался путь монтирования '/data', но найдено '$actualMount'."
        }
        log.info("✅ Путь к постоянному хранилищу: $actualMount")


        // Порт контейнера (name="run.containerPort", ожидаем "80")
        val portLocator = page.locator("input[name='run.containerPort']")
        val actualPort = portLocator.inputValue()
        require(actualPort == "80") {
            "ОШИБКА: Ожидался порт '80', но найдено '$actualPort'."
        }
        log.info("✅ Порт контейнера: $actualPort")
    }

    fun fillConfigInMaster(page: Page){
        // Установим используемое в коде имя скрипта app.py
        val scriptNameLocator = page.locator("input[name='run.scriptName']")
        val actualScriptName = scriptNameLocator.inputValue()
        val rightScriptName = "bot.py"

        scriptNameLocator.fill(rightScriptName)
        log.info("➡️ Имя скрипта изменено с '$actualScriptName' на '$rightScriptName'.")

    }

    fun checkValidationConfigInMaster(page: Page){
        log.info("-->Проверяем валидацию полей в мастере нового проекта на этапе заполнения конфигурации")

        // Путь к зависимостям
        val requirementsLocator = page.locator("input[name='build.requirementsPath']")
        val scriptNameLocator = page.locator("input[name='run.scriptName']")
        val actualRequirements = requirementsLocator.inputValue()
        val wrongRequirements = "missing-requirements.txt"
        val actualScriptName = scriptNameLocator.inputValue()
        val wrongScriptName = "missing_script.py"

        log.info("Вводим неверное имя requirements.txt: $wrongRequirements")
        requirementsLocator.fill(wrongRequirements)
        // Ищем сообщение об ошибке. В HTML оно имеет id, заканчивающийся на -form-item-message
        // Мы можем найти его по тексту, так как он уникален для этого блока
        val requirementsError = page.locator("p:has-text('В коде не найден указанный файл, измените название или проверьте путь. Создать файл requirements.txt можно на этапе загрузки файлов Upload files или по Инструкции по созданию requirements.txt')")
        scriptNameLocator.click()
        // Ждем появления ошибки (на случай задержки валидации)
        requirementsError.waitFor(Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE))

        require(requirementsError.isVisible) {
            "ОШИБКА: Сообщение о неверном requirements.txt не появилось"
        }
        log.info("✅ Проверка валидации для requirementsPath пройдена")
        requirementsLocator.fill(actualRequirements)


        // Имя скрипта

        scriptNameLocator.fill(wrongScriptName)
        log.info("Вводим неверное имя скрипта: $wrongScriptName")
        val scriptError = page.locator("p:has-text('В коде не найден указанный файл, измените название или проверьте путь')")
            .filter(Locator.FilterOptions().setHasNotText("Инструкция по созданию")) // Исключаем первое сообщение, если оно вдруг совпадет по тексту
        requirementsLocator.click()
        scriptError.waitFor(Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE))
        require(scriptError.isVisible) {
            "ОШИБКА: Сообщение о неверном имени скрипта не появилось"
        }
        log.info("✅ Проверка валидации для имени скрипта пройдена!")
        scriptNameLocator.fill(actualScriptName)

    }
}
