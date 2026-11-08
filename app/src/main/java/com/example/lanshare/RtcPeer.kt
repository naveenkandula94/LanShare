
package com.example.lanshare

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * WebRTC engine for LAN Share.
 *
 * Sender:
 * - Captures the Android screen.
 * - Sends screen video and microphone audio.
 * - Receives the other phone's microphone audio.
 *
 * Receiver:
 * - Sends microphone audio.
 * - Receives screen video and microphone audio.
 */
class RtcPeer(
    private val context: Context,
    private val remoteRenderer: SurfaceViewRenderer,
    private val onStatus: (String) -> Unit
) {

    private val egl = EglBase.create()

    private val audioModule =
        JavaAudioDeviceModule.builder(context.applicationContext)
            .createAudioDeviceModule()

    private val factory: PeerConnectionFactory

    private var peer: PeerConnection? = null

    private var capturer: ScreenCapturerAndroid? = null
    private var textureHelper: SurfaceTextureHelper? = null

    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null

    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null

    private var remoteVideoTrack: VideoTrack? = null
    private var remoteAudioTrack: AudioTrack? = null

    private var captureWidth = 1280
    private var captureHeight = 720
    private var captureFps = 15

    private var initialized = false
    private var closed = false

    private val iceGathered = CountDownLatch(1)

    private val lock = Any()

    init {

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions
                .builder(context.applicationContext)
                .createInitializationOptions()
        )

        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioModule)
            .setVideoEncoderFactory(
                DefaultVideoEncoderFactory(
                    egl.eglBaseContext,
                    true,
                    true
                )
            )
            .setVideoDecoderFactory(
                DefaultVideoDecoderFactory(
                    egl.eglBaseContext
                )
            )
            .createPeerConnectionFactory()

        remoteRenderer.init(
            egl.eglBaseContext,
            null
        )

        remoteRenderer.setEnableHardwareScaler(true)
        remoteRenderer.setMirror(false)

        initialized = true
    }

    // ---------------------------------------------------------
    // SCREEN CAPTURE
    // ---------------------------------------------------------

    fun startScreenCapture(permissionData: Intent) {

        check(!closed) {
            "WebRTC session has already been closed."
        }

        check(capturer == null) {
            "Screen capture is already running."
        }

        val source = factory.createVideoSource(false)

        videoSource = source

        val helper = SurfaceTextureHelper.create(
            "LanShareScreenCapture",
            egl.eglBaseContext
        ) ?: error("Could not create screen capture thread.")

        textureHelper = helper

        val screenCapturer = ScreenCapturerAndroid(
            permissionData,
            object : MediaProjection.Callback() {

                override fun onStop() {

                    onStatus(
                        "Android stopped screen capture."
                    )
                }
            }
        )

        capturer = screenCapturer

        try {

            screenCapturer.initialize(
                helper,
                context.applicationContext,
                source.capturerObserver
            )

            screenCapturer.startCapture(
                captureWidth,
                captureHeight,
                captureFps
            )

            localVideoTrack = factory.createVideoTrack(
                "lan-share-screen-video",
                source
            )

            localVideoTrack?.setEnabled(true)

            onStatus(
                "Screen capture started: ${captureWidth}x${captureHeight} at ${captureFps} fps."
            )

        } catch (e: Exception) {

            try {
                screenCapturer.stopCapture()
            } catch (_: Exception) {
            }

            try {
                screenCapturer.dispose()
            } catch (_: Exception) {
            }

            capturer = null

            try {
                helper.dispose()
            } catch (_: Exception) {
            }

            textureHelper = null

            try {
                source.dispose()
            } catch (_: Exception) {
            }

            videoSource = null

            throw IllegalStateException(
                "Screen capture initialization failed: ${e.message}",
                e
            )
        }
    }

    // ---------------------------------------------------------
    // PEER CONNECTION
    // ---------------------------------------------------------

    fun createPeer(asReceiver: Boolean = false): Boolean {

        check(!closed) {
            "WebRTC session has already been closed."
        }

        check(peer == null) {
            "Peer connection already exists."
        }

        if (!asReceiver) {

            check(localVideoTrack != null) {
                "Start screen capture before creating the sender peer."
            }
        }

        val iceServers = listOf(

            PeerConnection.IceServer
                .builder("stun:stun.l.google.com:19302")
                .createIceServer()

        )

        val config = PeerConnection.RTCConfiguration(
            iceServers
        )

        config.sdpSemantics =
            PeerConnection.SdpSemantics.UNIFIED_PLAN

        val connection = factory.createPeerConnection(
            config,
            object : PeerConnection.Observer {

                override fun onSignalingChange(
                    state: PeerConnection.SignalingState
                ) {
                    onStatus(
                        "Signaling state: $state"
                    )
                }

                override fun onIceConnectionChange(
                    state: PeerConnection.IceConnectionState
                ) {

                    onStatus(
                        "WebRTC connection: $state"
                    )
                }

                override fun onIceConnectionReceivingChange(
                    receiving: Boolean
                ) {
                }

                override fun onIceGatheringChange(
                    state: PeerConnection.IceGatheringState
                ) {

                    if (
                        state ==
                        PeerConnection.IceGatheringState.COMPLETE
                    ) {
                        iceGathered.countDown()
                    }
                }

                override fun onIceCandidate(
                    candidate: IceCandidate
                ) {
                    // ICE candidates are included in the SDP
                    // after gathering completes.
                }

                override fun onIceCandidatesRemoved(
                    candidates: Array<IceCandidate>
                ) {
                }

                override fun onAddStream(
                    stream: MediaStream
                ) {
                }

                override fun onRemoveStream(
                    stream: MediaStream
                ) {
                }

                override fun onDataChannel(
                    channel: DataChannel
                ) {
                }

                override fun onRenegotiationNeeded() {
                }

                override fun onAddTrack(
                    receiver: RtpReceiver,
                    mediaStreams: Array<out MediaStream>
                ) {

                    handleRemoteTrack(
                        receiver.track()
                    )
                }

                override fun onTrack(
                    transceiver: RtpTransceiver
                ) {

                    handleRemoteTrack(
                        transceiver.receiver.track()
                    )
                }
            }
        )

        peer = connection
            ?: return false

        try {

            // Local microphone audio track.

            val constraints = MediaConstraints()

            val source = factory.createAudioSource(
                constraints
            )

            audioSource = source

            val audioTrack = factory.createAudioTrack(
                "lan-share-microphone",
                source
            )

            localAudioTrack = audioTrack

            audioTrack.setEnabled(true)

            connection.addTrack(
                audioTrack,
                listOf("lan-share-stream")
            )

            if (asReceiver) {

                // Receiver requests the sender's screen video.

                val videoInit =
                    RtpTransceiver.RtpTransceiverInit(
                        RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
                    )

                connection.addTransceiver(
                    MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                    videoInit
                )

            } else {

                // Sender publishes its screen video.

                val screenTrack = localVideoTrack
                    ?: error("Screen video track is unavailable.")

                connection.addTrack(
                    screenTrack,
                    listOf("lan-share-stream")
                )
            }

            return true

        } catch (e: Exception) {

            close()

            throw IllegalStateException(
                "Could not configure WebRTC tracks: ${e.message}",
                e
            )
        }
    }

    // ---------------------------------------------------------
    // REMOTE TRACKS
    // ---------------------------------------------------------

    private fun handleRemoteTrack(
        track: MediaStreamTrack?
    ) {

        when (track) {

            is VideoTrack -> {

                synchronized(lock) {

                    if (remoteVideoTrack !== track) {

                        remoteVideoTrack?.removeSink(
                            remoteRenderer
                        )

                        remoteVideoTrack = track

                        track.setEnabled(true)

                        track.addSink(
                            remoteRenderer
                        )
                    }
                }

                onStatus(
                    "Receiving screen video."
                )
            }

            is AudioTrack -> {

                synchronized(lock) {

                    remoteAudioTrack = track

                    track.setEnabled(true)
                }

                onStatus(
                    "Receiving remote microphone audio."
                )
            }
        }
    }

    // ---------------------------------------------------------
    // CREATE OFFER
    // ---------------------------------------------------------

    fun makeOffer(): String {

        val connection = requireNotNull(peer) {
            "Peer connection not initialized."
        }

        val result = AtomicReference<String>()
        val latch = CountDownLatch(1)

        connection.createOffer(
            object : SdpObserver {

                override fun onCreateSuccess(
                    description: SessionDescription
                ) {

                    connection.setLocalDescription(
                        object : SdpObserver {

                            override fun onSetSuccess() {

                                result.set(
                                    description.description
                                )

                                latch.countDown()
                            }

                            override fun onSetFailure(
                                error: String
                            ) {

                                result.set(
                                    "ERROR:$error"
                                )

                                latch.countDown()
                            }

                            override fun onCreateSuccess(
                                p0: SessionDescription
                            ) {
                            }

                            override fun onCreateFailure(
                                p0: String
                            ) {
                            }
                        },
                        description
                    )
                }

                override fun onSetSuccess() {
                }

                override fun onCreateFailure(
                    error: String
                ) {

                    result.set(
                        "ERROR:$error"
                    )

                    latch.countDown()
                }

                override fun onSetFailure(
                    error: String
                ) {

                    result.set(
                        "ERROR:$error"
                    )

                    latch.countDown()
                }
            },
            MediaConstraints()
        )

        check(
            latch.await(20, TimeUnit.SECONDS)
        ) {
            "Timed out creating WebRTC offer."
        }

        val offer = result.get()

        check(!offer.startsWith("ERROR:")) {
            offer
        }

        awaitIce()

        return offer
    }

    // ---------------------------------------------------------
    // ACCEPT OFFER AND CREATE ANSWER
    // ---------------------------------------------------------

    fun acceptOfferAndCreateAnswer(
        offerSdp: String
    ): String {

        val connection = requireNotNull(peer) {
            "Peer connection not initialized."
        }

        setRemote(
            connection,
            SessionDescription.Type.OFFER,
            offerSdp
        )

        val result = AtomicReference<String>()
        val latch = CountDownLatch(1)

        connection.createAnswer(
            object : SdpObserver {

                override fun onCreateSuccess(
                    description: SessionDescription
                ) {

                    connection.setLocalDescription(
                        object : SdpObserver {

                            override fun onSetSuccess() {

                                result.set(
                                    description.description
                                )

                                latch.countDown()
                            }

                            override fun onSetFailure(
                                error: String
                            ) {

                                result.set(
                                    "ERROR:$error"
                                )

                                latch.countDown()
                            }

                            override fun onCreateSuccess(
                                p0: SessionDescription
                            ) {
                            }

                            override fun onCreateFailure(
                                p0: String
                            ) {
                            }
                        },
                        description
                    )
                }

                override fun onSetSuccess() {
                }

                override fun onCreateFailure(
                    error: String
                ) {

                    result.set(
                        "ERROR:$error"
                    )

                    latch.countDown()
                }

                override fun onSetFailure(
                    error: String
                ) {

                    result.set(
                        "ERROR:$error"
                    )

                    latch.countDown()
                }
            },
            MediaConstraints()
        )

        check(
            latch.await(20, TimeUnit.SECONDS)
        ) {
            "Timed out creating WebRTC answer."
        }

        val answer = result.get()

        check(!answer.startsWith("ERROR:")) {
            answer
        }

        awaitIce()

        return answer
    }

    // ---------------------------------------------------------
    // ACCEPT ANSWER
    // ---------------------------------------------------------

    fun acceptAnswer(
        answerSdp: String
    ) {

        setRemote(
            requireNotNull(peer),
            SessionDescription.Type.ANSWER,
            answerSdp
        )
    }

    // ---------------------------------------------------------
    // REMOTE SDP
    // ---------------------------------------------------------

    private fun setRemote(
        connection: PeerConnection,
        type: SessionDescription.Type,
        sdp: String
    ) {

        val latch = CountDownLatch(1)

        val failure = AtomicReference<String?>()

        connection.setRemoteDescription(
            object : SdpObserver {

                override fun onSetSuccess() {
                    latch.countDown()
                }

                override fun onSetFailure(
                    error: String
                ) {

                    failure.set(error)

                    latch.countDown()
                }

                override fun onCreateSuccess(
                    p0: SessionDescription
                ) {
                }

                override fun onCreateFailure(
                    p0: String
                ) {
                }
            },
            SessionDescription(type, sdp)
        )

        check(
            latch.await(20, TimeUnit.SECONDS)
        ) {
            "Timed out setting remote SDP."
        }

        check(failure.get() == null) {
            "Remote SDP error: ${failure.get()}"
        }
    }

    // ---------------------------------------------------------
    // ICE GATHERING
    // ---------------------------------------------------------

    private fun awaitIce() {

        check(
            iceGathered.await(25, TimeUnit.SECONDS)
        ) {
            "ICE gathering timed out. Check network connectivity."
        }
    }

    // ---------------------------------------------------------
    // MICROPHONE CONTROL
    // ---------------------------------------------------------

    fun setMicrophoneEnabled(
        enabled: Boolean
    ) {

        localAudioTrack?.setEnabled(enabled)
    }

    // ---------------------------------------------------------
    // VIDEO QUALITY
    // ---------------------------------------------------------

    fun setVideoQuality(
        width: Int,
        height: Int,
        fps: Int
    ) {

        if (width <= 0 || height <= 0 || fps <= 0) {
            return
        }

        captureWidth = width
        captureHeight = height
        captureFps = fps

        try {

            capturer?.changeCaptureFormat(
                width,
                height,
                fps
            )

            onStatus(
                "Video quality selected: ${width}x${height} at ${fps} fps."
            )

        } catch (e: Exception) {

            onStatus(
                "Could not change video quality: ${e.message}"
            )
        }
    }

    // ---------------------------------------------------------
    // CLEANUP
    // ---------------------------------------------------------

    fun close() {

        synchronized(lock) {

            if (closed) return

            closed = true
        }

        try {
            remoteVideoTrack?.removeSink(
                remoteRenderer
            )
        } catch (_: Exception) {
        }

        remoteVideoTrack = null
        remoteAudioTrack = null

        try {
            capturer?.stopCapture()
        } catch (_: Exception) {
        }

        try {
            capturer?.dispose()
        } catch (_: Exception) {
        }

        capturer = null

        try {
            textureHelper?.dispose()
        } catch (_: Exception) {
        }

        textureHelper = null

        try {
            localVideoTrack?.dispose()
        } catch (_: Exception) {
        }

        localVideoTrack = null

        try {
            videoSource?.dispose()
        } catch (_: Exception) {
        }

        videoSource = null

        try {
            localAudioTrack?.dispose()
        } catch (_: Exception) {
        }

        localAudioTrack = null

        try {
            audioSource?.dispose()
        } catch (_: Exception) {
        }

        audioSource = null

        try {
            peer?.close()
        } catch (_: Exception) {
        }

        try {
            peer?.dispose()
        } catch (_: Exception) {
        }

        peer = null

        try {
            if (initialized) {
                remoteRenderer.release()
                initialized = false
            }
        } catch (_: Exception) {
        }

        try {
            factory.dispose()
        } catch (_: Exception) {
        }

        try {
            audioModule.release()
        } catch (_: Exception) {
        }

        try {
            egl.release()
        } catch (_: Exception) {
        }
    }
}
