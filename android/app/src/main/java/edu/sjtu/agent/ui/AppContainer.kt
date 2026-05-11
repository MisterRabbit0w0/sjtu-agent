package edu.sjtu.agent.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import edu.sjtu.agent.data.DefaultCampusRepository
import edu.sjtu.agent.data.local.EncryptedCredentialStore
import edu.sjtu.agent.llm.OpenAiCompatClient
import edu.sjtu.agent.llm.ToolRouter
import edu.sjtu.agent.network.AihaokeClient
import edu.sjtu.agent.network.CampusSearchClient
import edu.sjtu.agent.network.CanvasClient
import edu.sjtu.agent.network.IcourseClient
import edu.sjtu.agent.network.JwxtClient
import edu.sjtu.agent.network.PhycaiClient
import edu.sjtu.agent.network.defaultHttpClient

class AppContainer(context: Context) {
    val store = EncryptedCredentialStore(context)
    private val http = defaultHttpClient()
    val canvas = CanvasClient(http)
    val repository = DefaultCampusRepository(
        store = store,
        canvas = canvas,
        aihaoke = AihaokeClient(http),
        icourse = IcourseClient(http),
        jwxt = JwxtClient(http),
        phycai = PhycaiClient(http),
        search = CampusSearchClient(http),
    )
    val toolRouter = ToolRouter(repository)
    val llm = OpenAiCompatClient(http, toolRouter)
}

@Composable
fun rememberAppContainer(context: Context): AppContainer =
    remember(context.applicationContext) { AppContainer(context.applicationContext) }
