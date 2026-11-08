package com.example.lanshare

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

class LanSignalServer(
    private val rtc: RtcPeer,
    private val onStatus: (String) -> Unit,
    private val sessionPassword: String
) {
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var server: ServerSocket? = null

    fun start(port: Int = 47831) {
        executor.execute {
            try {
                server = ServerSocket(port)
                onStatus("Waiting for the receiver on TCP port $port.")
                server!!.accept().use { socket ->
                    socket.soTimeout = 60000
                    val input = BufferedReader(InputStreamReader(socket.getInputStream()))
                    val output = PrintWriter(socket.getOutputStream(), true)
                    val line = input.readLine() ?: error("Receiver disconnected before offer.")
                    val request = JSONObject(line)
                    val suppliedPassword = request.optString("password")
                    if (sessionPassword.isNotEmpty() && suppliedPassword != sessionPassword) {
                        output.println(JSONObject().put("error", "Incorrect connection password").toString())
                        error("Receiver supplied an incorrect password.")
                    }
                    val offer = request.getString("sdp")
                    onStatus("Receiver authenticated. Negotiating WebRTC…")
                    val answer = rtc.acceptOfferAndCreateAnswer(offer)
                    check(!answer.startsWith("ERROR:")) { answer }
                    output.println(JSONObject().put("sdp", answer).toString())
                    onStatus("Answer sent. Connecting screen and voice…")
                }
            } catch (e: Exception) {
                onStatus("Signaling error: ${e.message}")
            }
        }
    }

    fun stop() {
        try { server?.close() } catch (_: Exception) {}
        executor.shutdownNow()
    }
}
