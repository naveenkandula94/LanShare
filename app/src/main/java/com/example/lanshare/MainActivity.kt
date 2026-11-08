package com.example.lanshare

import android.Manifest
import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.ImageView
import android.widget.Spinner
import android.widget.ArrayAdapter
import org.json.JSONObject
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import android.graphics.Bitmap
import com.google.zxing.integration.android.IntentIntegrator
import org.webrtc.SurfaceViewRenderer
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var renderer: SurfaceViewRenderer
    private lateinit var ipInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var ipText: TextView
    private lateinit var qrImage: ImageView
    private lateinit var qualitySpinner: Spinner
    private var microphoneEnabled = true
    private var lastHost = ""
    private var lastPassword = ""
    private var rtc: RtcPeer? = null
    private var signalServer: LanSignalServer? = null
    private val io = Executors.newSingleThreadExecutor()
    private val projectionRequest = 7001
    private val audioRequest = 7002

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.statusText)
        renderer = findViewById(R.id.remoteRenderer)
        ipInput = findViewById(R.id.ipInput)
        passwordInput = findViewById(R.id.passwordInput)
        ipText = findViewById(R.id.ipText)
        qrImage = findViewById(R.id.qrImage)
        qualitySpinner = findViewById(R.id.qualitySpinner)
        qualitySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Low · 640×360 · 15 fps", "Standard · 1280×720 · 15 fps", "High · 1920×1080 · 30 fps"))
        findViewById<Button>(R.id.muteButton).setOnClickListener {
            microphoneEnabled = !microphoneEnabled
            rtc?.setMicrophoneEnabled(microphoneEnabled)
            (it as Button).text = if (microphoneEnabled) "Mute Microphone" else "Unmute Microphone"
            status.text = if (microphoneEnabled) "Microphone enabled." else "Microphone muted."
        }
        qualitySpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val dimensions = when (position) {
                    0 -> intArrayOf(640, 360, 15)
                    2 -> intArrayOf(1920, 1080, 30)
                    else -> intArrayOf(1280, 720, 15)
                }
                rtc?.setVideoQuality(dimensions[0], dimensions[1], dimensions[2])
            }
        }
        findViewById<Button>(R.id.reconnectButton).setOnClickListener {
            if (lastHost.isBlank()) {
                status.text = "No previous receiver address. Enter an IP address first."
            } else {
                ipInput.setText(lastHost)
                passwordInput.setText(lastPassword)
                connectAsReceiver()
            }
        }

        findViewById<Button>(R.id.scanQrButton).setOnClickListener {
            IntentIntegrator(this).setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                .setPrompt("Scan the LAN Share code from the sender phone")
                .setBeepEnabled(false).setOrientationLocked(false).initiateScan()
        }
        findViewById<Button>(R.id.shareButton).setOnClickListener {
            if (!hasAudioPermission()) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), audioRequest)
                status.text = "Allow microphone permission, then tap Share again."
            } else {
                val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(manager.createScreenCaptureIntent(), projectionRequest)
            }
        }
        findViewById<Button>(R.id.watchButton).setOnClickListener {
            if (!hasAudioPermission()) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), audioRequest)
                status.text = "Allow microphone permission, then tap Watch again."
            } else {
                connectAsReceiver()
            }
        }
        findViewById<Button>(R.id.stopButton).setOnClickListener { stopSession() }
    }

    private fun hasAudioPermission() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @Deprecated("Use Activity Result API in production; retained for broad AIDE compatibility.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val scanResult = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (scanResult != null) {
            val raw = scanResult.contents
            if (!raw.isNullOrBlank()) {
                try {
                    val uri = android.net.Uri.parse(raw)
                    val scannedIp = uri.getQueryParameter("ip")
                    if (uri.scheme == "lanshare" && !scannedIp.isNullOrBlank()) {
                        ipInput.setText(scannedIp)
                        status.text = "QR scanned. Enter the session password if required, then tap Watch."
                    } else {
                        status.text = "This QR code is not a valid LAN Share pairing code."
                    }
                } catch (_: Exception) {
                    status.text = "Could not read the LAN Share QR code."
                }
            }
            return
        }
        if (requestCode == projectionRequest && resultCode == RESULT_OK && data != null) {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(Intent(this, ProjectionForegroundService::class.java))
            }
            try {
                rtc = RtcPeer(this, renderer) { message -> runOnUiThread { status.text = message } }
                rtc!!.startScreenCapture(data)
                check(rtc!!.createPeer()) { "Could not create WebRTC peer connection" }
                signalServer = LanSignalServer(rtc!!, { message -> runOnUiThread { status.text = message } },
                    passwordInput.text.toString())
                signalServer!!.start()
                val address = LanIp.address(this)
                ipText.text = "Sender IP: $address"
                showPairingQr(address)
                status.text = "Share is ready. Scan the QR code or enter this IP on the other phone: $address"
            } catch (e: Exception) {
                status.text = "Could not start sharing: ${e.message}"
                stopSession()
            }
        } else if (requestCode == projectionRequest) {
            status.text = "Screen sharing permission was not granted."
        }
    }

    private fun connectAsReceiver() {
        val host = ipInput.text.toString().trim()
        if (host.isBlank()) {
            status.text = "Enter the sender phone's Wi-Fi IP address."
            return
        }
        if (!host.matches(Regex("""^(?:\d{1,3}\.){3}\d{1,3}$""")) ||
            host.split(".").any { it.toIntOrNull() !in 0..255 }) {
            status.text = "Enter a valid IPv4 address, for example 192.168.1.25."
            return
        }
        lastHost = host
        lastPassword = passwordInput.text.toString()
        status.text = "Connecting to $host…"
        io.execute {
            try {
                val client = RtcPeer(this, renderer) { message -> runOnUiThread { status.text = message } }
                rtc = client
                check(client.createPeer(asReceiver = true)) { "Could not create WebRTC peer connection" }
                val offer = client.makeOffer()
                check(!offer.startsWith("ERROR:")) { offer }
                Socket(host, 47831).use { socket ->
                    socket.soTimeout = 60000
                    val output = PrintWriter(socket.getOutputStream(), true)
                    val input = BufferedReader(InputStreamReader(socket.getInputStream()))
                    output.println(JSONObject().put("sdp", offer).put("password", lastPassword).toString())
                    val answerLine = input.readLine() ?: error("Sender closed the signaling connection.")
                    val answerJson = JSONObject(answerLine)
                    if (answerJson.has("error")) error(answerJson.getString("error"))
                    client.acceptAnswer(answerJson.getString("sdp"))
                }
                runOnUiThread { status.text = "Connected. Screen and two-way voice should be active." }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Connection failed: ${e.message}" }
            }
        }
    }

    private fun showPairingQr(address: String) {
        try {
            val content = "lanshare://connect?ip=$address&port=47831"
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 420, 420)
            val bitmap = Bitmap.createBitmap(420, 420, Bitmap.Config.RGB_565)
            for (x in 0 until 420) for (y in 0 until 420) {
                bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
            qrImage.setImageBitmap(bitmap)
            qrImage.visibility = android.view.View.VISIBLE
        } catch (e: Exception) {
            status.text = "QR generation failed: ${e.message}"
        }
    }

    private fun stopSession() {
        signalServer?.stop()
        signalServer = null
        rtc?.close()
        rtc = null
        stopService(Intent(this, ProjectionForegroundService::class.java))
        status.text = "Disconnected."
    }

    override fun onDestroy() {
        stopSession()
        io.shutdownNow()
        super.onDestroy()
    }
}
