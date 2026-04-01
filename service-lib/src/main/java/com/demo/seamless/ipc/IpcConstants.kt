package com.demo.seamless.ipc

object IpcConstants {
    const val HOST_PACKAGE = "com.demo.a3ddemo"
    const val ACTION_BIND_SERVICE = "com.demo.seamless.service.BIND"

    const val CLIENT_A_PACKAGE = "com.demo.a3ddemo.clienta"
    const val CLIENT_A_ACTIVITY = "com.demo.a3ddemo.clienta.ClientAActivity"

    const val CLIENT_B_PACKAGE = "com.demo.a3ddemo.clientb"
    const val CLIENT_B_ACTIVITY = "com.demo.a3ddemo.clientb.ClientBActivity"

    val CLIENT_CONFIGS = mapOf(
        "client-a" to ClientConfig(
            homeRotX = -25f,
            homeRotY = 45f,
            homeDist = 3.5f,
            targetClientId = "client-b",
        ),
        "client-b" to ClientConfig(
            homeRotX = -15f,
            homeRotY = 135f,
            homeDist = 3.5f,
            targetClientId = "client-a",
        ),
    )
}

data class ClientConfig(
    val homeRotX: Float,
    val homeRotY: Float,
    val homeDist: Float,
    val targetClientId: String,
)
