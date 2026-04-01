package com.demo.a3ddemo.clienta

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import com.demo.seamless.client.GLClientScreen
import com.demo.seamless.ipc.IpcConstants

class ClientAActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MaterialTheme {
                GLClientScreen(
                    clientId = "client-a",
                    title = "Client-A",
                    targetPkg = IpcConstants.CLIENT_B_PACKAGE,
                    targetAct = IpcConstants.CLIENT_B_ACTIVITY,
                    targetLabel = "一镜到底 → Client-B",
                )
            }
        }
    }
}
