package edu.sjtu.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import edu.sjtu.agent.ui.AgentApp
import edu.sjtu.agent.ui.rememberAppContainer

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AgentApp(container = rememberAppContainer(applicationContext))
        }
    }
}
