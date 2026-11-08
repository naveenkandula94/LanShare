package com.example.lanshare

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class LanSignalServer(
    private val rtc: RtcPeer,
    private val onStatus: (String) -> Unit,
    private val sessionPassword: String
) {

    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var activeSocket: Socket? = null

    fun start(
        port: Int = 47831,
        onReady: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        if (!running.compareAndSet(false, true)) {
            onStatus("Signaling server is already running.")
            return
        }

        executor.execute {
            var socketServer: ServerSocket? = null

            try {
                socketServer = ServerSocket()
                socketServer.reuseAddress = true
                socketServer.bind(InetSocketAddress(port))

                server = socketServer

                // The port is now actually listening.
                onStatus("Waiting for receiver on TCP port $port...")
                onReady()

                val socket = socketServer.accept()
                activeSocket = socket

                socket.use {
                    it.soTimeout = 60000
                    it.tcpNoDelay = true
                    handleReceiver(it)
                }

            } catch (e: Exception) {
                if (running.get()) {
                    val message =
                        "${e.javaClass.simpleName}: ${e.message}"

                    onStatus("Signaling error: $message")
                    onError(message)
                }

            } finally {
                running.set(false)

                try {
                    activeSocket?.close()
                } catch (_: Exception) {
                }
                activeSocket = null

                try {
                    socketServer?.close()
                } catch (_: Exception) {
                }
                server = null
            }
        }
    }

    private fun handleReceiver(socket: Socket) {
        val input = BufferedReader(
            InputStreamReader(
                socket.getInputStream(),
                Charsets.UTF_8
            )
        )

        val output = PrintWriter(
            socket.getOutputStream(),
            true
        )

        onStatus("Receiver connected. Checking password...")

        val line = input.readLine()
            ?: error("Receiver disconnected before sending offer.")

        require(line.length <= MAX_MESSAGE_LENGTH) {
            "Signaling message is too large."
        }

        val request = JSONObject(line)

        val suppliedPassword =
            request.optString("password", "")

        if (
            sessionPassword.isNotEmpty() &&
            suppliedPassword != sessionPassword
        ) {
            output.println(
                JSONObject()
                    .put("error", "Incorrect connection password")
                    .toString()
            )

            error("Incorrect connection password.")
        }

        val offer = request.optString("sdp", "")

        require(offer.isNotBlank()) {
            "Receiver did not provide a valid SDP offer."
        }

        require(offer.length <= MAX_MESSAGE_LENGTH) {
            "SDP offer is too large."
        }

        onStatus("Receiver authenticated. Negotiating WebRTC...")

        val answer = rtc.acceptOfferAndCreateAnswer(offer)

        check(!answer.startsWith("ERROR:")) {
            answer
        }

        val response = JSONObject()
            .put("sdp", answer)

        output.println(response.toString())

        check(!output.checkError()) {
            "Could not send SDP answer to receiver."
        }

        onStatus("Answer sent. Waiting for WebRTC connection...")
    }

    fun stop() {
        running.set(false)

        try {
            activeSocket?.close()
        } catch (_: Exception) {
        }

        try {
            server?.close()
        } catch (_: Exception) {
        }

        activeSocket = null
        server = null

        executor.shutdownNow()
    }

    companion object {
        private const val MAX_MESSAGE_LENGTH = 2 * 1024 * 1024
    }
}
