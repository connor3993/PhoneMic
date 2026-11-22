package com.phonemic.app.model

data class ServerDevice(
    val name: String,
    val ip: String,
    val port: Int,
    val rate: Int = 48000,
    val channels: Int = 1,
    val lastSeen: Long = System.currentTimeMillis()
)
