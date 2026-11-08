package com.example.lanshare

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import android.widget.Toast

import com.google.zxing.BarcodeFormat
import com.google.zxing.integration.android.IntentIntegrator
import com.google.zxing.qrcode.QRCodeWriter

import org.json.JSONObject
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
    private val cameraRequest = 7003

    private var startingShare = false

    companion object {
        private const val SIGNAL_PORT = 47831
        private const val TAG = "LanShare"
    }

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

        setupQualitySpinner()
        setupButtons()

        status.text =
            "Connect both phones to the same Wi-Fi network."
    }

    private fun setupQualitySpinner() {

        val options = listOf(
            "Low · 640×360 · 15 fps",
            "Standard · 1280×720 · 15 fps",
            "High · 1920×1080 · 30 fps"
        )

        qualitySpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            options
        )

        qualitySpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {

                override fun onNothingSelected(
                    parent: AdapterView<*>?
                ) {}

                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {

                    val dimensions = when (position) {
                        0 -> intArrayOf(640, 360, 15)
                        2 -> intArrayOf(1920, 1080, 30)
                        else -> intArrayOf(1280, 720, 15)
                    }

                    rtc?.setVideoQuality(
                        dimensions[0],
                        dimensions[1],
                        dimensions[2]
                    )
                }
            }
    }

    private fun setupButtons() {

        findViewById<Button>(R.id.shareButton)
            .setOnClickListener {
                requestShare()
            }

        findViewById<Button>(R.id.watchButton)
            .setOnClickListener {
                requestWatch()
            }

        findViewById<Button>(R.id.scanQrButton)
            .setOnClickListener {
                startQrScanner()
            }

        findViewById<Button>(R.id.stopButton)
            .setOnClickListener {
                stopSession()
            }

        findViewById<Button>(R.id.muteButton)
            .setOnClickListener { view ->

                microphoneEnabled = !microphoneEnabled

                rtc?.setMicrophoneEnabled(microphoneEnabled)

                (view as Button).text =
                    if (microphoneEnabled) {
                        "Mute Microphone"
                    } else {
                        "Unmute Microphone"
                    }

                status.text =
                    if (microphoneEnabled) {
                        "Microphone enabled."
                    } else {
                        "Microphone muted."
                    }
            }

        findViewById<Button>(R.id.reconnectButton)
            .setOnClickListener {

                if (lastHost.isBlank()) {
                    status.text =
                        "No previous receiver address. Enter an IP first."
                } else {
                    ipInput.setText(lastHost)
                    passwordInput.setText(lastPassword)
                    requestWatch()
                }
            }
    }

    // ---------------------------------------------------------
    // PERMISSIONS
    // ---------------------------------------------------------

    private fun hasAudioPermission(): Boolean {
        return checkSelfPermission(
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestShare() {

        if (startingShare) return

        if (!hasAudioPermission()) {

            status.text =
                "Microphone permission is required for voice sharing."

            requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO),
                audioRequest
            )

            return
        }

        launchScreenPermission()
    }

    private fun requestWatch() {

        if (!hasAudioPermission()) {

            status.text =
                "Microphone permission is required for two-way voice."

            requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO),
                audioRequest
            )

            return
        }

        connectAsReceiver()
    }

    private fun launchScreenPermission() {

        try {

            val manager = getSystemService(
                Context.MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

            status.text =
                "Waiting for screen-sharing permission..."

            startActivityForResult(
                manager.createScreenCaptureIntent(),
                projectionRequest
            )

        } catch (e: Exception) {

            Log.e(TAG, "Screen permission request failed", e)

            status.text =
                "Could not request screen permission: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {

        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == audioRequest) {

            if (
                grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {

                status.text =
                    "Microphone permission granted. Tap Share or Watch again."

                Toast.makeText(
                    this,
                    "Microphone permission granted",
                    Toast.LENGTH_SHORT
                ).show()

            } else {

                status.text =
                    "Microphone permission denied. Voice sharing cannot start."
            }

            return
        }

        if (requestCode == cameraRequest) {

            if (
                grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                startQrScanner()
            } else {
                status.text =
                    "Camera permission denied. Allow camera access to scan QR codes."
            }

            return
        }
    }

    // ---------------------------------------------------------
    // QR SCANNER
    // ---------------------------------------------------------

    private fun startQrScanner() {

        if (
            checkSelfPermission(Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {

            requestPermissions(
                arrayOf(Manifest.permission.CAMERA),
                cameraRequest
            )

            return
        }

        try {

            IntentIntegrator(this)
                .setDesiredBarcodeFormats(
                    IntentIntegrator.QR_CODE
                )
                .setPrompt(
                    "Scan the LAN Share pairing QR code"
                )
                .setBeepEnabled(false)
                .setOrientationLocked(false)
                .initiateScan()

        } catch (e: Exception) {

            Log.e(TAG, "QR scanner launch failed", e)

            status.text =
                "Scanner error: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    // ---------------------------------------------------------
    // ACTIVITY RESULT
    // ---------------------------------------------------------

    @Deprecated("Uses Activity result API for compatibility.")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {

        super.onActivityResult(
            requestCode,
            resultCode,
            data
        )

        val scanResult =
            IntentIntegrator.parseActivityResult(
                requestCode,
                resultCode,
                data
            )

        if (scanResult != null) {

            val raw = scanResult.contents

            if (!raw.isNullOrBlank()) {
                handleScannedQr(raw)
            } else {
                status.text = "QR scanning cancelled."
            }

            return
        }

        if (requestCode == projectionRequest) {

            if (resultCode == RESULT_OK && data != null) {
                startSharing(data)
            } else {
                status.text =
                    "Screen-sharing permission was not granted."
            }
        }
    }

    // ---------------------------------------------------------
    // READ PAIRING QR
    // ---------------------------------------------------------

    private fun handleScannedQr(raw: String) {

        try {

            val uri = Uri.parse(raw)

            val scannedIp =
                uri.getQueryParameter("ip")

            val port =
                uri.getQueryParameter("port")

            val validIp =
                !scannedIp.isNullOrBlank() &&
                scannedIp.matches(
                    Regex("""^(?:\d{1,3}\.){3}\d{1,3}$""")
                ) &&
                scannedIp.split(".").all {
                    (it.toIntOrNull() ?: -1) in 0..255
                }

            if (
                uri.scheme == "lanshare" &&
                uri.host == "connect" &&
                validIp &&
                port == SIGNAL_PORT.toString()
            ) {

                ipInput.setText(scannedIp)

                status.text =
                    "QR scanned successfully. Enter the session password if required, then tap Watch."

            } else {

                status.text =
                    "Invalid LAN Share QR code or unsupported port."
            }

        } catch (e: Exception) {

            Log.e(TAG, "Could not read QR code", e)

            status.text =
                "Could not read QR code: ${e.message}"
        }
    }

    // ---------------------------------------------------------
    // START SCREEN SHARING
    // ---------------------------------------------------------

    private fun startSharing(permissionData: Intent) {

        if (startingShare) return

        startingShare = true

        try {

            status.text = "Starting screen sharing..."

            val serviceIntent = Intent(
                this,
                ProjectionForegroundService::class.java
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            val newRtc = RtcPeer(
                this,
                renderer
            ) { message ->

                runOnUiThread {
                    status.text = message
                }
            }

            rtc = newRtc

            newRtc.startScreenCapture(permissionData)

            check(newRtc.createPeer()) {
                "Could not create WebRTC peer connection."
            }

            val newServer = LanSignalServer(
                newRtc,
                { message ->

                    runOnUiThread {
                        status.text = message
                    }

                },
                passwordInput.text.toString()
            )

            signalServer = newServer

            newServer.start(SIGNAL_PORT)

            val address = LanIp.address(this)

            check(
                address.matches(
                    Regex("""^(?:\d{1,3}\.){3}\d{1,3}$""")
                )
            ) {
                "Could not find your Wi-Fi IPv4 address. Connect to Wi-Fi first."
            }

            ipText.text = "Sender IP: $address"

            showPairingQr(address)

            status.text =
                "Screen sharing is ready. Scan the QR code or enter this IP on the other phone: $address"

        } catch (e: Exception) {

            Log.e(TAG, "Could not start sharing", e)

            val errorMessage =
                "Could not start sharing: ${e.javaClass.simpleName}: ${e.message}"

            stopSession(errorMessage)

        } finally {

            startingShare = false
        }
    }

    // ---------------------------------------------------------
    // RECEIVER CONNECTION
    // ---------------------------------------------------------

    private fun connectAsReceiver() {

        val host = ipInput.text.toString().trim()

        if (host.isBlank()) {

            status.text =
                "Enter the sender phone's Wi-Fi IP address."

            return
        }

        if (
            !host.matches(
                Regex("""^(?:\d{1,3}\.){3}\d{1,3}$""")
            ) ||
            host.split(".").any {
                (it.toIntOrNull() ?: -1) !in 0..255
            }
        ) {

            status.text =
                "Enter a valid IPv4 address, for example 192.168.1.25."

            return
        }

        lastHost = host
        lastPassword = passwordInput.text.toString()

        status.text = "Connecting to $host..."

        io.execute {

            var client: RtcPeer? = null

            try {

                val newClient = RtcPeer(
                    this,
                    renderer
                ) { message ->

                    runOnUiThread {
                        status.text = message
                    }
                }

                client = newClient
                rtc = newClient

                check(
                    newClient.createPeer(asReceiver = true)
                ) {
                    "Could not create WebRTC peer connection."
                }

                val offer = newClient.makeOffer()

                check(!offer.startsWith("ERROR:")) {
                    offer
                }

                Socket(host, SIGNAL_PORT).use { socket ->

                    socket.soTimeout = 60000
                    socket.tcpNoDelay = true

                    val output = PrintWriter(
                        socket.getOutputStream(),
                        true
                    )

                    val input = BufferedReader(
                        InputStreamReader(
                            socket.getInputStream()
                        )
                    )

                    val request = JSONObject()
                        .put("sdp", offer)
                        .put("password", lastPassword)

                    output.println(request.toString())

                    val answerLine =
                        input.readLine()
                            ?: error("Sender closed the signaling connection.")

                    val answerJson =
                        JSONObject(answerLine)

                    if (answerJson.has("error")) {
                        error(answerJson.getString("error"))
                    }

                    newClient.acceptAnswer(
                        answerJson.getString("sdp")
                    )
                }

                runOnUiThread {
                    status.text =
                        "Connected. Waiting for screen and voice..."
                }

            } catch (e: Exception) {

                Log.e(TAG, "Receiver connection failed", e)

                try {
                    client?.close()
                } catch (_: Exception) {
                }

                if (rtc === client) {
                    rtc = null
                }

                runOnUiThread {
                    status.text =
                        "Connection failed: ${e.javaClass.simpleName}: ${e.message}"
                }
            }
        }
    }

    // ---------------------------------------------------------
    // GENERATE SENDER QR
    // ---------------------------------------------------------

    private fun showPairingQr(address: String) {

        try {

            val content =
                "lanshare://connect?ip=$address&port=$SIGNAL_PORT"

            val matrix = QRCodeWriter().encode(
                content,
                BarcodeFormat.QR_CODE,
                420,
                420
            )

            val bitmap = Bitmap.createBitmap(
                420,
                420,
                Bitmap.Config.RGB_565
            )

            for (x in 0 until 420) {
                for (y in 0 until 420) {

                    bitmap.setPixel(
                        x,
                        y,
                        if (matrix[x, y]) {
                            Color.BLACK
                        } else {
                            Color.WHITE
                        }
                    )
                }
            }

            qrImage.setImageBitmap(bitmap)
            qrImage.visibility = View.VISIBLE

        } catch (e: Exception) {

            Log.e(TAG, "QR generation failed", e)

            status.text =
                "QR generation failed: ${e.message}"
        }
    }

    // ---------------------------------------------------------
    // STOP SESSION
    // ---------------------------------------------------------

    private fun stopSession(
        message: String = "Disconnected."
    ) {

        try {
            signalServer?.stop()
        } catch (_: Exception) {
        }

        signalServer = null

        try {
            rtc?.close()
        } catch (_: Exception) {
        }

        rtc = null

        try {
            stopService(
                Intent(
                    this,
                    ProjectionForegroundService::class.java
                )
            )
        } catch (_: Exception) {
        }

        qrImage.setImageDrawable(null)
        qrImage.visibility = View.GONE

        ipText.text = "Sender IP will appear here"

        status.text = message
    }

    override fun onDestroy() {

        stopSession()

        io.shutdownNow()

        super.onDestroy()
    }
}
