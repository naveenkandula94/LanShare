package com.example.lanshare

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
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

    private val io = Executors.newSingleThreadExecutor()

    private var rtc: RtcPeer? = null
    private var signalServer: LanSignalServer? = null

    private var microphoneEnabled = true
    private var lastHost = ""
    private var lastPassword = ""

    private var startingShare = false
    private var pendingProjectionData: Intent? = null
    private var serviceReceiverRegistered = false
    private var receiverConnecting = false

    private val projectionRequest = 7001
    private val audioRequest = 7002
    private val cameraRequest = 7003

    companion object {
        private const val TAG = "LanShare"
        private const val SIGNAL_PORT = 47831
    }

    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ProjectionForegroundService.ACTION_READY -> {
                    status.text = "Foreground service ready. Starting screen capture..."
                    initializeSharingAfterServiceReady()
                }

                ProjectionForegroundService.ACTION_FAILED -> {
                    val error = intent.getStringExtra(
                        ProjectionForegroundService.EXTRA_ERROR
                    ) ?: "Unknown foreground service error"

                    Log.e(TAG, error)
                    stopSession("Screen-sharing service failed: $error")
                }
            }
        }
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

        status.text = "Connect both phones to the same Wi-Fi network."
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

                override fun onNothingSelected(parent: AdapterView<*>?) {}

                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    val quality = when (position) {
                        0 -> intArrayOf(640, 360, 15)
                        2 -> intArrayOf(1920, 1080, 30)
                        else -> intArrayOf(1280, 720, 15)
                    }

                    rtc?.setVideoQuality(
                        quality[0],
                        quality[1],
                        quality[2]
                    )
                }
            }
    }

    private fun setupButtons() {
        findViewById<Button>(R.id.shareButton).setOnClickListener {
            requestShare()
        }

        findViewById<Button>(R.id.watchButton).setOnClickListener {
            requestWatch()
        }

        findViewById<Button>(R.id.scanQrButton).setOnClickListener {
            startQrScanner()
        }

        findViewById<Button>(R.id.stopButton).setOnClickListener {
            stopSession()
        }

        findViewById<Button>(R.id.muteButton).setOnClickListener { view ->
            microphoneEnabled = !microphoneEnabled
            rtc?.setMicrophoneEnabled(microphoneEnabled)

            (view as Button).text =
                if (microphoneEnabled) "Mute Microphone"
                else "Unmute Microphone"

            status.text =
                if (microphoneEnabled) "Microphone enabled."
                else "Microphone muted."
        }

        findViewById<Button>(R.id.reconnectButton).setOnClickListener {
            if (lastHost.isBlank()) {
                status.text = "No previous receiver address."
            } else {
                ipInput.setText(lastHost)
                passwordInput.setText(lastPassword)
                requestWatch()
            }
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return checkSelfPermission(permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun requestShare() {
        if (startingShare) return

        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO),
                audioRequest
            )
            status.text = "Allow microphone permission, then tap Share again."
            return
        }

        launchScreenPermission()
    }

    private fun requestWatch() {
        if (receiverConnecting) return

        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO),
                audioRequest
            )
            status.text = "Allow microphone permission, then tap Watch again."
            return
        }

        connectAsReceiver()
    }

    private fun launchScreenPermission() {
        try {
            val manager = getSystemService(
                Context.MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

            status.text = "Waiting for screen-sharing permission..."

            startActivityForResult(
                manager.createScreenCaptureIntent(),
                projectionRequest
            )
        } catch (e: Exception) {
            Log.e(TAG, "Screen permission request failed", e)
            status.text = "Screen permission error: ${e.message}"
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

        when (requestCode) {
            audioRequest -> {
                if (
                    grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED
                ) {
                    status.text = "Microphone permission granted. Tap Share or Watch again."
                } else {
                    status.text = "Microphone permission denied."
                }
            }

            cameraRequest -> {
                if (
                    grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED
                ) {
                    startQrScanner()
                } else {
                    status.text = "Camera permission denied."
                }
            }
        }
    }

    private fun startQrScanner() {
        if (!hasPermission(Manifest.permission.CAMERA)) {
            requestPermissions(
                arrayOf(Manifest.permission.CAMERA),
                cameraRequest
            )
            return
        }

        try {
            IntentIntegrator(this)
                .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                .setPrompt("Scan the LAN Share pairing QR code")
                .setBeepEnabled(false)
                .setOrientationLocked(false)
                .initiateScan()
        } catch (e: Exception) {
            Log.e(TAG, "QR scanner launch failed", e)
            status.text = "QR scanner error: ${e.message}"
        }
    }

    @Deprecated("Uses Activity result API for compatibility.")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        val scan = IntentIntegrator.parseActivityResult(
            requestCode,
            resultCode,
            data
        )

        if (scan != null) {
            if (!scan.contents.isNullOrBlank()) {
                handleScannedQr(scan.contents)
            } else {
                status.text = "QR scanning cancelled."
            }
            return
        }

        if (requestCode == projectionRequest) {
            if (resultCode == RESULT_OK && data != null) {
                startSharing(data)
            } else {
                status.text = "Screen-sharing permission was not granted."
            }
        }
    }

    private fun handleScannedQr(raw: String) {
        try {
            val uri = Uri.parse(raw)
            val scannedIp = uri.getQueryParameter("ip")
            val port = uri.getQueryParameter("port")

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
                status.text = "QR scanned successfully. Enter the password if needed, then tap Watch."
            } else {
                status.text = "Invalid LAN Share QR code or unsupported port."
            }
        } catch (e: Exception) {
            Log.e(TAG, "QR parsing failed", e)
            status.text = "Could not read QR code: ${e.message}"
        }
    }

    private fun startSharing(permissionData: Intent) {
        if (startingShare) return

        startingShare = true
        pendingProjectionData = permissionData

        try {
            status.text = "Starting screen-sharing service..."

            val filter = IntentFilter().apply {
                addAction(ProjectionForegroundService.ACTION_READY)
                addAction(ProjectionForegroundService.ACTION_FAILED)
            }

            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(
                    serviceReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(serviceReceiver, filter)
            }

            serviceReceiverRegistered = true

            val serviceIntent = Intent(
                this,
                ProjectionForegroundService::class.java
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Foreground service launch failed", e)
            stopSession("Could not start sharing service: ${e.message}")
        }
    }

    private fun initializeSharingAfterServiceReady() {
        val permissionData = pendingProjectionData

        if (permissionData == null) {
            stopSession("Screen-capture permission data is missing.")
            return
        }

        try {
            status.text = "Initializing screen capture..."

            // Sender renderer initialization is also performed on main thread.
            val newRtc = RtcPeer(this, renderer) { message ->
                runOnUiThread {
                    status.text = message
                }
            }

            rtc = newRtc
            newRtc.startScreenCapture(permissionData)

            check(newRtc.createPeer()) {
                "Could not create sender WebRTC peer."
            }

            val address = LanIp.address(this)

            check(isValidIpv4(address)) {
                "Could not find your Wi-Fi IPv4 address. Connect to Wi-Fi first."
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

            status.text = "Opening connection port..."

            // Display IP and QR only after the TCP server has bound successfully.
            newServer.start(
                port = SIGNAL_PORT,
                onReady = {
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread

                        ipText.text = "Sender IP: $address"
                        showPairingQr(address)
                        status.text =
                            "Ready! Scan the QR code or enter $address on the other phone."
                    }
                },
                onError = { error ->
                    runOnUiThread {
                        stopSession("Signaling server failed: $error")
                    }
                }
            )

        } catch (e: Exception) {
            Log.e(TAG, "Sender initialization failed", e)
            stopSession(
                "Could not start sharing: ${e.javaClass.simpleName}: ${e.message}"
            )
        } finally {
            pendingProjectionData = null
            startingShare = false
            unregisterServiceReceiver()
        }
    }

    private fun isValidIpv4(address: String): Boolean {
        return address.matches(
            Regex("""^(?:\d{1,3}\.){3}\d{1,3}$""")
        ) && address.split(".").all {
            (it.toIntOrNull() ?: -1) in 0..255
        }
    }

    private fun unregisterServiceReceiver() {
        if (serviceReceiverRegistered) {
            try {
                unregisterReceiver(serviceReceiver)
            } catch (e: Exception) {
                Log.w(TAG, "Receiver unregister failed", e)
            }
            serviceReceiverRegistered = false
        }
    }

    private fun connectAsReceiver() {
        val host = ipInput.text.toString().trim()

        if (!isValidIpv4(host)) {
            status.text = "Enter a valid sender IPv4 address."
            return
        }

        lastHost = host
        lastPassword = passwordInput.text.toString()

        // IMPORTANT: RtcPeer initializes SurfaceViewRenderer.
        // Therefore create it here, on the Android main thread.
        val oldClient = rtc
        rtc = null

        try {
            oldClient?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Previous WebRTC session cleanup failed", e)
        }

        val newClient = try {
            RtcPeer(this, renderer) { message ->
                runOnUiThread {
                    if (rtc === newClientPlaceholder()) {
                        status.text = message
                    } else {
                        status.text = message
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Renderer initialization failed", e)
            status.text =
                "Renderer initialization failed: ${e.javaClass.simpleName}: ${e.message}"
            return
        }

        rtc = newClient
        receiverConnecting = true
        status.text = "Preparing connection to $host..."

        io.execute {
            try {
                check(newClient.createPeer(asReceiver = true)) {
                    "Could not create receiver WebRTC peer."
                }

                runOnUiThread {
                    status.text = "Creating connection offer..."
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
                        InputStreamReader(socket.getInputStream())
                    )

                    val request = JSONObject()
                        .put("sdp", offer)
                        .put("password", lastPassword)

                    output.println(request.toString())

                    check(!output.checkError()) {
                        "Could not send connection request."
                    }

                    val answerLine = input.readLine()
                        ?: error("Sender closed the signaling connection.")

                    val answerJson = JSONObject(answerLine)

                    if (answerJson.has("error")) {
                        error(answerJson.getString("error"))
                    }

                    val answer = answerJson.optString("sdp", "")

                    check(answer.isNotBlank()) {
                        "Sender returned an empty WebRTC answer."
                    }

                    newClient.acceptAnswer(answer)
                }

                runOnUiThread {
                    receiverConnecting = false
                    if (rtc === newClient) {
                        status.text =
                            "Connected. Waiting for screen and voice..."
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Receiver connection failed", e)

                try {
                    newClient.close()
                } catch (closeError: Exception) {
                    Log.e(TAG, "Receiver cleanup failed", closeError)
                }

                runOnUiThread {
                    receiverConnecting = false
                    if (rtc === newClient) {
                        rtc = null
                        status.text =
                            "Connection failed: ${e.javaClass.simpleName}: ${e.message}"
                    }
                }
            }
        }
    }

    // Used only to keep the status callback safe before rtc is assigned.
    private fun newClientPlaceholder(): RtcPeer? = null

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
                        if (matrix[x, y]) Color.BLACK else Color.WHITE
                    )
                }
            }

            qrImage.setImageBitmap(bitmap)
            qrImage.visibility = View.VISIBLE

        } catch (e: Exception) {
            Log.e(TAG, "QR generation failed", e)
            status.text = "QR generation failed: ${e.message}"
        }
    }

    private fun stopSession(message: String = "Disconnected.") {
        unregisterServiceReceiver()

        pendingProjectionData = null
        startingShare = false
        receiverConnecting = false

        try {
            signalServer?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Server stop failed", e)
        }
        signalServer = null

        val oldRtc = rtc
        rtc = null

        try {
            oldRtc?.close()
        } catch (e: Exception) {
            Log.w(TAG, "WebRTC cleanup failed", e)
        }

        try {
            stopService(
                Intent(this, ProjectionForegroundService::class.java)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Service stop failed", e)
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
