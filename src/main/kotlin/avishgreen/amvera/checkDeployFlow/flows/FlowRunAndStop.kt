//src/main/kotlin/avishgreen/amvera/checkDeployFlow/services/CheckFlow.kt
package avishgreen.amvera.checkDeployFlow.flows

import avishgreen.amvera.checkDeployFlow.enums.ProjectStatus
import avishgreen.amvera.checkDeployFlow.services.FlowExecutorService
import com.microsoft.playwright.Page
import mu.KotlinLogging
import org.springframework.stereotype.Component

private val log = KotlinLogging.logger("FLOW_FILE_LOGGER")

@Component
class FlowRunAndStop: IFlow {
    override val name = "FlowRunAndStop"

    override suspend fun execute(runner: FlowExecutorService, page: Page) {
        log.info("Вводим креды")
        runner.pushLogin(page)

        //Открываем конфигурацию тестового проекта
        log.info("Открываем проект ${runner.amveraTestProjectName}")
        page.navigate("${runner.amveraHost}/projects/applications/${runner.amveraTestProjectName}/configuration")
        page.locator("button:has-text('Собрать')").waitFor()
        runner.takeScreenshot(page,"opened project.jpeg")

        log.info("Жмем кнопку собрать")
        runner.pushBuildButton(page)

        runner.takeScreenshot(page,"assemble.jpeg")

        log.info("Жмем кнопку Остановить")
        runner.pushStopButton(page)
    }

}