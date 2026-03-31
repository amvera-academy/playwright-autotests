// src/main/kotlin/avishgreen/amvera/checkDeployFlow/flows/TestFlow.kt
package avishgreen.amvera.checkDeployFlow.flows

import avishgreen.amvera.checkDeployFlow.services.FlowExecutorService
import com.microsoft.playwright.Page

interface IFlow {
    val name: String
    suspend fun execute(runner: FlowExecutorService, page: Page)
}