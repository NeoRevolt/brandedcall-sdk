package com.dartmedia.brandedlibraryclient.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.dartmedia.brandedlibraryclient.R
import com.dartmedia.brandedlibraryclient.databinding.ActivityCallBinding
import com.dartmedia.brandedlibraryclient.ui.viewmodel.CallLogViewModel
import com.dartmedia.brandedlibraryclient.ui.viewmodel.ViewModelFactory
import com.dartmedia.brandedsdk.library.BrandedSDK
import com.dartmedia.brandedsdk.model.UserStatusEnum
import com.dartmedia.brandedsdk.service.BrandedService
import com.dartmedia.brandedsdk.utils.audio.manager.RTCAudioManager
import com.dartmedia.brandedsdk.utils.date.DateUtils.getCurrentDateDetailed
import com.dartmedia.brandedsdk.utils.extension.convertToHumanTime
import com.dartmedia.brandedsdk.utils.image.WhiteBackgroundTransformation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CallActivity : AppCompatActivity(), BrandedSDK.EndCallListener {

    private var brandedSDK: BrandedSDK? = null

    private lateinit var requestScreenCaptureLauncher: ActivityResultLauncher<Intent>
    private lateinit var callLogViewModel: CallLogViewModel
    private lateinit var binding: ActivityCallBinding

    private var sender: String? = null
    private var target: String? = null
    private var targetName: String? = null
    private var isVideoCall: Boolean = true
    private var isCaller: Boolean = true
    private var targetImgUrl: String? = null
    private var callMessage: String? = null

    private var isMicrophoneMuted = false
    private var isCameraMuted = false
    private var isSpeakerMode = true
    private var isScreenCasting = false

    private var callDuration: Int = 0
    private var callStartTime: String = ""

    override fun onStart() {
        super.onStart()
        requestScreenCaptureLauncher = registerForActivityResult(
            ActivityResultContracts
                .StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val intent = result.data
                //its time to give this intent to our service and service passes it to our webrtc client
                BrandedService.screenPermissionIntent = intent
                isScreenCasting = true
                updateUiToScreenCaptureIsOn()
                brandedSDK?.toggleScreenShare(true)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        callLogViewModel = obtainViewModel(this@CallActivity)

        init()
    }

    private fun obtainViewModel(activity: AppCompatActivity): CallLogViewModel {
        val factory = ViewModelFactory.getInstance(activity.application)
        return ViewModelProvider(activity, factory).get(CallLogViewModel::class.java)
    }

    private fun init() {
        intent.getStringExtra("target")?.let {
            this.target = it
        } ?: kotlin.run {
            finish()
        }
        brandedSDK = BrandedSDK.initialize(this)
        brandedSDK?.endCallListener = this
        targetName = intent.getStringExtra("targetName") ?: target
        targetImgUrl = intent.getStringExtra("targetImg")
        callMessage = intent.getStringExtra("message")
        sender = intent.getStringExtra("sender")
        isVideoCall = intent.getBooleanExtra("isVideoCall", true)
        isCaller = intent.getBooleanExtra("isCaller", true)
        if (targetImgUrl != null) {
            Glide.with(this)
                .load(targetImgUrl)
                .transform(WhiteBackgroundTransformation())
                .apply(
                    RequestOptions()
                        .placeholder(R.drawable.rounded_message_gray)
                        .error(R.drawable.asset_person_ic)
                        .circleCrop()
                )
                .into(binding.voiceCallCallerImage)
        } else {
            Glide.with(this)
                .load(R.drawable.asset_person_ic)
                .apply(RequestOptions.circleCropTransform())
                .into(binding.voiceCallCallerImage)
        }

        target?.let { target ->
            targetName?.let { targetName ->
                brandedSDK?.observeTargetContact(target) {
                    when (it) {

                        UserStatusEnum.ONLINE -> {
                            binding.apply {
                                callTitleTv.text = targetName
                                voiceCallCallerName.text = targetName
                                callTimerTv.text = "Calling..."
                                voiceCallStatusText.text = "Calling..."
                            }
                        }

                        UserStatusEnum.CALLING -> {
                            binding.apply {
                                //                            callTimeoutTimer.cancel()
                                callTitleTv.text = targetName
                                voiceCallCallerName.text = targetName
                                callTimerTv.text = "Connecting..."
                                voiceCallStatusText.text = "Connecting..."
                            }
                        }

                        UserStatusEnum.IN_CALL -> {
                            binding.apply {
                                //                            callTimeoutTimer.cancel()
                                callTitleTv.text = targetName
                                voiceCallCallerName.text = targetName
                                callStartTime = getCurrentDateDetailed()
                                CoroutineScope(Dispatchers.IO).launch {
                                    for (duration in 0..3600) {
                                        delay(1000)
                                        withContext(Dispatchers.Main) {
                                            //convert this int to human readable time
                                            callTimerTv.text = duration.convertToHumanTime()
                                            voiceCallStatusText.text = duration.convertToHumanTime()
                                            callDuration = duration
                                        }
                                    }
                                }
                            }
                        }

                        else -> {}
                    }
                }
            }
        }

        binding.apply {
            if (!isVideoCall) {
                toggleCameraButton.isVisible = false
                screenShareButton.isVisible = false
                switchCameraButton.isVisible = false
                voiceCallLayouts.isVisible = true
                voiceCallEndCallButton.setOnClickListener {
                    brandedSDK?.sendEndCall()
                }
            }

            BrandedService.remoteSurfaceView = remoteView
            BrandedService.localSurfaceView = localView
            brandedSDK?.setupViews(isVideoCall, isCaller, target!!)

            endCallButton.setOnClickListener {
                brandedSDK?.sendEndCall()
            }

            switchCameraButton.setOnClickListener {
                brandedSDK?.switchCamera()
            }
        }
        setupMicToggleClicked()
        setupCameraToggleClicked()
        setupToggleAudioDevice()
        setupScreenCasting()

    }

    private fun setupScreenCasting() {
        binding.apply {
            screenShareButton.setOnClickListener {
                if (!isScreenCasting) {
                    //we have to start casting
                    AlertDialog.Builder(this@CallActivity)
                        .setTitle("Screen Casting")
                        .setMessage("You sure to start casting ?")
                        .setPositiveButton("Yes") { dialog, _ ->
                            //start screen casting process
                            startScreenCapture()
                            dialog.dismiss()
                        }.setNegativeButton("No") { dialog, _ ->
                            dialog.dismiss()
                        }.create().show()
                } else {
                    //we have to end screen casting
                    isScreenCasting = false
                    updateUiToScreenCaptureIsOff()
                    brandedSDK?.toggleScreenShare(false)
                }
            }
        }
    }

    private fun startScreenCapture() {
        val mediaProjectionManager = application.getSystemService(
            Context.MEDIA_PROJECTION_SERVICE
        ) as MediaProjectionManager

        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
        requestScreenCaptureLauncher.launch(captureIntent)

    }

    private fun updateUiToScreenCaptureIsOn() {
        binding.apply {
            localView.isVisible = false
            switchCameraButton.isVisible = false
            toggleCameraButton.isVisible = false
            screenShareButton.setImageResource(R.drawable.ic_stop_screen_share)
        }

    }

    private fun updateUiToScreenCaptureIsOff() {
        binding.apply {
            localView.isVisible = true
            switchCameraButton.isVisible = true
            toggleCameraButton.isVisible = true
            screenShareButton.setImageResource(R.drawable.ic_screen_share)
        }
    }

    private fun setupMicToggleClicked() {
        binding.apply {
            toggleMicrophoneButton.setOnClickListener {
                if (!isMicrophoneMuted) {
                    //we should mute our mic
                    //1. send a command to repository
                    brandedSDK?.toggleAudio(true)
                    //2. update ui to mic is muted
                    toggleMicrophoneButton.setImageResource(R.drawable.ic_mic_on)
                } else {
                    //we should set it back to normal
                    //1. send a command to repository to make it back to normal status
                    brandedSDK?.toggleAudio(false)
                    //2. update ui
                    toggleMicrophoneButton.setImageResource(R.drawable.ic_mic_off)
                }
                isMicrophoneMuted = !isMicrophoneMuted
            }
            voiceCallMuteButton.setOnClickListener {
                if (!isMicrophoneMuted) {
                    //we should mute our mic
                    //1. send a command to repository
                    brandedSDK?.toggleAudio(true)
                    //2. update ui to mic is muted
                    voiceCallMuteButton.setImageResource(R.drawable.ic_mic_on)
                } else {
                    //we should set it back to normal
                    //1. send a command to repository to make it back to normal status
                    brandedSDK?.toggleAudio(false)
                    //2. update ui
                    voiceCallMuteButton.setImageResource(R.drawable.ic_mic_off)
                }
                isMicrophoneMuted = !isMicrophoneMuted
            }
        }
    }

    private fun setupToggleAudioDevice() {
        binding.apply {
            toggleAudioDevice.setOnClickListener {
                if (isSpeakerMode) {
                    //we should set it to earpiece mode
                    toggleAudioDevice.setImageResource(R.drawable.ic_speaker)
                    //we should send a command to our service to switch between devices
                    brandedSDK?.toggleAudioDevice(RTCAudioManager.AudioDevice.EARPIECE.name)

                } else {
                    //we should set it to speaker mode
                    toggleAudioDevice.setImageResource(R.drawable.ic_ear)
                    brandedSDK?.toggleAudioDevice(RTCAudioManager.AudioDevice.SPEAKER_PHONE.name)

                }
                isSpeakerMode = !isSpeakerMode
            }
        }
    }

    private fun setupCameraToggleClicked() {
        binding.apply {
            toggleCameraButton.setOnClickListener {
                if (!isCameraMuted) {
                    brandedSDK?.toggleVideo(true)
                    toggleCameraButton.setImageResource(R.drawable.ic_camera_on)
                } else {
                    brandedSDK?.toggleVideo(false)
                    toggleCameraButton.setImageResource(R.drawable.ic_camera_off)
                }

                isCameraMuted = !isCameraMuted
            }
        }
    }

    override fun onCallEnded() {
        finish()
    }

    override fun onBackPressed() {
        super.onBackPressed()
        brandedSDK?.sendEndCall()
    }

    override fun onDestroy() {
        super.onDestroy()
        BrandedService.remoteSurfaceView?.release()
        BrandedService.remoteSurfaceView = null

        BrandedService.localSurfaceView?.release()
        BrandedService.localSurfaceView = null
    }
}