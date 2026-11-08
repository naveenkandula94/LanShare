
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
 * One peer connection per session. The receiver creates the offer; the sender answers.
 * ICE candidates are embedded in the SDP by waiting for ICE gathering to complete.
 */
class RtcPeer(
    private val context: Context,
    private val remoteRenderer: SurfaceViewRenderer,
    private val onStatus: (String) -> Unit
) {
    private val egl = EglBase.create()
    private val audioModule =
        JavaAudioDeviceModule.builder(context).createAudioDeviceModule()

    private val factory: PeerConnectionFactory

    private var peer: PeerConnection? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var audioSource: AudioSource? = null

    private val iceGathered = CountDownLatch(1)

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions
                .builder(context)
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
                DefaultVideoDecoderFactory(egl.eglBaseContext)
            )
            .createPeerConnectionFactory()

        remoteRenderer.init(egl.eglBaseContext, null)
        remoteRenderer.setEnableHardwareScaler(true)
        remoteRenderer.setMirror(false)
    }

    fun startScreenCapture(permissionData: Intent) {
        val source = factory.createVideoSource(false)
        videoSource = source

        val helper = SurfaceTextureHelper.create(
            "ScreenCaptureThread",
            egl.eglBaseContext
        )

        val screenCapturer = ScreenCapturerAndroid(
            permissionData,
            object : MediaProjection.Callback() {
                override fun onStop() {
                    onStatus("Android stopped screen capture.")
                }
            }
        )

        capturer = screenCapturer

        screenCapturer.initialize(
            helper,
            context,
            source.capturerObserver
        )

        screenCapturer.startCapture(1280, 720, 15)

        localVideoTrack = factory.createVideoTrack(
            "screen-video",
            source
        )
    }

    fun createPeer(asReceiver: Boolean = false): Boolean {
        val iceServers = listOf(
            PeerConnection.IceServer
                .builder("stun:stun.l.google.com:19302")
                .createIceServer()
        )

        val config = PeerConnection.RTCConfiguration(iceServers)

        peer = factory.createPeerConnection(
            config,
            object : PeerConnection.Observer {

                override fun onSignalingChange(
                    state: PeerConnection.SignalingState
                ) {}

                override fun onIceConnectionChange(
                    state: PeerConnection.IceConnectionState
                ) {
                    onStatus("WebRTC connection: $state")
                }

                override fun onIceConnectionReceivingChange(
                    receiving: Boolean
                ) {}

                override fun onIceGatheringChange(
                    state: PeerConnection.IceGatheringState
                ) {
                    if (state == PeerConnection.IceGatheringState.COMPLETE) {
                        iceGathered.countDown()
                    }
                }

                override fun onIceCandidate(candidate: IceCandidate) {}

                override fun onIceCandidatesRemoved(
                    candidates: Array<IceCandidate>
                ) {}

                override fun onAddStream(stream: MediaStream) {}

                override fun onRemoveStream(stream: MediaStream) {}

                override fun onDataChannel(channel: DataChannel) {}

                override fun onRenegotiationNeeded() {}

                override fun onAddTrack(
                    receiver: RtpReceiver,
                    mediaStreams: Array<out MediaStream>
                ) {
                    val track = receiver.track()

                    if (track is VideoTrack) {
                        track.addSink(remoteRenderer)
                        onStatus("Receiving live screen and voice.")
                    }
                }

                override fun onTrack(transceiver: RtpTransceiver) {
                    val track = transceiver.receiver.track()

                    if (track is VideoTrack) {
                        track.addSink(remoteRenderer)
                    }
                }
            }
        )

        val connection = peer ?: return false

        audioSource = factory.createAudioSource(MediaConstraints())

        localAudioTrack = factory.createAudioTrack(
            "voice-audio",
            audioSource
        )

        // Both phones publish microphone audio.
        connection.addTrack(
            localAudioTrack,
            listOf("lan-share-stream")
        )

        if (asReceiver) {
            // Receiver requests incoming video.
            val videoInit = RtpTransceiver.RtpTransceiverInit(
                RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
            )

            connection.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                videoInit
            )
        } else {
            // Sender publishes screen video.
            localVideoTrack?.let {
                connection.addTrack(
                    it,
                    listOf("lan-share-stream")
                )
            }
        }

        return true
    }

    fun makeOffer(): String {
        val connection = requireNotNull(peer) {
            "Peer connection not initialized"
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
                                result.set(description.description)
                                latch.countDown()
                            }

                            override fun onSetFailure(error: String) {
                                result.set("ERROR:$error")
                                latch.countDown()
                            }

                            override fun onCreateSuccess(
                                p0: SessionDescription
                            ) {}

                            override fun onCreateFailure(p0: String) {}
                        },
                        description
                    )
                }

                override fun onSetSuccess() {}

                override fun onCreateFailure(error: String) {
                    result.set("ERROR:$error")
                    latch.countDown()
                }

                override fun onSetFailure(error: String) {
                    result.set("ERROR:$error")
                    latch.countDown()
                }
            },
            MediaConstraints()
        )

        check(latch.await(20, TimeUnit.SECONDS)) {
            "Timed out creating offer"
        }

        awaitIce()

        return result.get()
    }

    fun acceptOfferAndCreateAnswer(offerSdp: String): String {
        val connection = requireNotNull(peer)

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
                                result.set(description.description)
                                latch.countDown()
                            }

                            override fun onSetFailure(error: String) {
                                result.set("ERROR:$error")
                                latch.countDown()
                            }

                            override fun onCreateSuccess(
                                p0: SessionDescription
                            ) {}

                            override fun onCreateFailure(p0: String) {}
                        },
                        description
                    )
                }

                override fun onSetSuccess() {}

                override fun onCreateFailure(error: String) {
                    result.set("ERROR:$error")
                    latch.countDown()
                }

                override fun onSetFailure(error: String) {
                    result.set("ERROR:$error")
                    latch.countDown()
                }
            },
            MediaConstraints()
        )

        check(latch.await(20, TimeUnit.SECONDS)) {
            "Timed out creating answer"
        }

        awaitIce()

        return result.get()
    }

    fun acceptAnswer(answerSdp: String) {
        setRemote(
            requireNotNull(peer),
            SessionDescription.Type.ANSWER,
            answerSdp
        )
    }

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

                override fun onSetFailure(error: String) {
                    failure.set(error)
                    latch.countDown()
                }

                override fun onCreateSuccess(
                    p0: SessionDescription
                ) {}

                override fun onCreateFailure(p0: String) {}
            },
            SessionDescription(type, sdp)
        )

        check(latch.await(20, TimeUnit.SECONDS)) {
            "Timed out setting remote SDP"
        }

        check(failure.get() == null) {
            "Remote SDP error: ${failure.get()}"
        }
    }

    private fun awaitIce() {
        check(iceGathered.await(25, TimeUnit.SECONDS)) {
            "ICE gathering timed out"
        }
    }

    fun setMicrophoneEnabled(enabled: Boolean) {
        localAudioTrack?.setEnabled(enabled)
    }

    fun setVideoQuality(
        width: Int,
        height: Int,
        fps: Int
    ) {
        try {
            capturer?.changeCaptureFormat(width, height, fps)
        } catch (e: Exception) {
            onStatus("Could not change video quality: ${e.message}")
        }
    }

    fun close() {
        try {
            capturer?.stopCapture()
        } catch (_: Exception) {
        }

        capturer?.dispose()
        localVideoTrack?.dispose()
        videoSource?.dispose()
        localAudioTrack?.dispose()
        audioSource?.dispose()

        peer?.close()
        peer?.dispose()

        factory.dispose()
        audioModule.release()
        remoteRenderer.release()
        egl.release()
    }
}
