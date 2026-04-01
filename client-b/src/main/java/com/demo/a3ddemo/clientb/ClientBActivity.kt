package com.demo.a3ddemo.clientb

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import com.demo.seamless.client.GLClientScreen
import com.demo.seamless.ipc.IpcConstants

class ClientBActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MaterialTheme {
                GLClientScreen(
                    clientId = "client-b",
                    title = "Client-B",
                    targetPkg = IpcConstants.CLIENT_A_PACKAGE,
                    targetAct = IpcConstants.CLIENT_A_ACTIVITY,
                    targetLabel = "一镜到底 → Client-A",
                )
            }
        }
    }
}
