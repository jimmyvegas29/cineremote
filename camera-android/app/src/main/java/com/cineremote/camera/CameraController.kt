package com.cineremote.camera

import android.os.BatteryManager
import android.util.Log
import cineremote.camctl.v5.CameraControl.*
import cineremote.camctl.v5.Common.FrameRate
import cineremote.camctl.v5.Common.Shutter
import cineremote.camctl.v5.Common.PointF
import cineremote.camctl.v5.Common.TimeCode
import cineremote.camctl.v5.MainMessages.*
import kotlin.math.roundToInt

/**
 * The camera side of CineRemote's control protocol (desktop side: cineremote/session.py + ui/model.py).
 * Turns the live [CameraEngine] / [Recorder] state into Property messages, applies WritePropertyRequests,
 * and drives preview (RTC media) and recording. One instance per authorized controller session.
 */
class CameraController(
    private val engine: CameraEngine,
    private val recorder: Recorder,
    private val takes: Takes,
    private val prefs: Prefs,
    private val batteryPct: () -> Float,
    private val send: (SubordinateMessage) -> Unit,
) {
    private var msgId = 0L
    private var slateReel = 1
    private var slateScene = "1"
    private var slateTake = 1

    @Synchronized private fun out(block: SubordinateMessage.Builder.() -> Unit) {
        send(SubordinateMessage.newBuilder().setMessageId(++msgId).apply(block).build())
    }

    // ------------------------------------------------------------------ incoming
    fun onControllerMessage(m: ControllerMessage) {
        when (m.messageCase) {
            ControllerMessage.MessageCase.HELLO_REQUEST -> out {
                helloResponse = HelloResponse.newBuilder()
                    .setAppInfo(AppInfo.newBuilder()
                        .setName("CineRemote Camera").setVersion("0.1.0")
                        .setPlatform(AppPlatform.APP_PLATFORM_ANDROID)
                        .setPlatformVersion(android.os.Build.VERSION.RELEASE)
                        .setDeviceModelName(prefs.deviceName).setLanguageCode("en"))
                    .addEncodingVideoCodecs(VideoCodec.VIDEO_CODEC_H264)
                    .build()
            }
            ControllerMessage.MessageCase.HEART_BEAT_REQUEST ->
                out { controllerMessageId = m.messageId; heartBeatResponse = HeartBeatResponse.getDefaultInstance() }
            ControllerMessage.MessageCase.READ_PROPERTY_REQUEST ->
                sendProperty(m.readPropertyRequest.propertyId, respondTo = m.messageId)
            ControllerMessage.MessageCase.WRITE_PROPERTY_REQUEST -> handleWrite(m)
            ControllerMessage.MessageCase.START_PREVIEW_REQUEST -> onStartPreview?.invoke(m.startPreviewRequest)
            ControllerMessage.MessageCase.STOP_PREVIEW_REQUEST ->
                out { controllerMessageId = m.messageId; stopPreviewResponse = StopPreviewResponse.getDefaultInstance() }
            ControllerMessage.MessageCase.START_RECORDING_REQUEST -> {
                val ok = startRecording()
                out {
                    controllerMessageId = m.messageId
                    startRecordingResponse = StartRecordingResponse.newBuilder()
                        .setStatus(if (ok) StartRecordingStatus.START_RECORDING_STATUS_OKAY
                                   else StartRecordingStatus.START_RECORDING_STATUS_FAILED).build()
                }
                emitRecordingState()
            }
            ControllerMessage.MessageCase.STOP_RECORDING_REQUEST -> {
                stopRecording()
                out {
                    controllerMessageId = m.messageId
                    stopRecordingResponse = StopRecordingResponse.newBuilder()
                        .setStatus(StopRecordingStatus.STOP_RECORDING_STATUS_OKAY).build()
                }
                emitRecordingState()
            }
            ControllerMessage.MessageCase.IDENTIFY_REQUEST -> {
                onIdentify?.invoke()
                out { controllerMessageId = m.messageId; identifyResponse = IdentifyCamResponse.getDefaultInstance() }
            }
            ControllerMessage.MessageCase.START_SETTINGS_EDIT_REQUEST -> out {
                // No phone-side settings menu: reject so CineRemote doesn't wait for a reply.
                controllerMessageId = m.messageId
                startSettingsEditResponse = StartSettingsEditResponse.newBuilder()
                    .setStatus(StartSettingsEditStatus.START_SETTINGS_EDIT_STATUS_REJECTED).build()
            }
            ControllerMessage.MessageCase.PAUSE_RECORDING_REQUEST -> out {
                controllerMessageId = m.messageId
                pauseRecordingResponse = PauseRecordingResponse.newBuilder()
                    .setStatus(PauseRecordingStatus.PAUSE_RECORDING_STATUS_FAILED).build()
            }
            ControllerMessage.MessageCase.DISCONNECT_EVENT -> onDisconnect?.invoke()
            else -> Log.d(TAG, "unhandled ${m.messageCase}")
        }
    }

    var onStartPreview: ((StartPreviewRequest) -> Unit)? = null
    var onIdentify: (() -> Unit)? = null
    var onDisconnect: (() -> Unit)? = null

    fun previewStartedOk() { /* camera never sends StartPreviewResponse; frames arriving = success */ }

    // ------------------------------------------------------------------ properties: read
    /** Send every property CineRemote reads at connect (read_all_properties). */
    fun sendInitialProperties() {
        FIELDS.forEach { emitProperty(it) }
    }

    private fun sendProperty(id: PropertyId, respondTo: Long) {
        val prop = buildProperty(fieldFor(id) ?: return) ?: return
        out { controllerMessageId = respondTo; readPropertyResponse = ReadPropertyResponse.newBuilder().setProperty(prop).build() }
    }

    /** Push a changed property as an event (used when the camera or a live auto value changes). */
    fun emitProperty(field: String) {
        val prop = buildProperty(field) ?: return
        out { propertyChangedEvent = PropertyChangedEvent.newBuilder().setProperty(prop).build() }
    }

    fun emitChanged(fields: Set<String>) = fields.forEach { emitProperty(it) }

    fun emitRecordingState() = emitProperty("recording_state")

    private fun prop(id: PropertyId, build: Property.Builder.() -> Unit): Property =
        Property.newBuilder().setId(id).apply(build).build()

    private fun buildProperty(field: String): Property? = when (field) {
        "available_cams" -> prop(PropertyId.PROPERTY_ID_AVAILABLE_CAMS) {
            val b = AvailableCamsProperty.newBuilder().setIsAutoSupported(false)
            engine.lenses.forEach { l ->
                b.addCameras(Camera.newBuilder()
                    .setId(l.id)
                    .setLens(Lens.newBuilder()
                        .setType(LensType.forNumber(l.type) ?: LensType.LENS_TYPE_BACK_MAIN)
                        .setFocalLengthMm(l.equivMm)
                        .setOpticalZoomFactor(l.zoomVsMain)
                        .setZoomMin(l.chars.get(android.hardware.camera2.CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.lower ?: 1f)
                        .setZoomMax(l.chars.get(android.hardware.camera2.CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.upper ?: 1f)
                        .setIsLightSupported(engine.hasTorchFor(l))))
            }
            availableCams = b.build()
        }
        "active_cam" -> prop(PropertyId.PROPERTY_ID_ACTIVE_CAM) {
            activeCam = ActiveCamProperty.newBuilder().setCameraId(engine.lens.id).setIsAuto(false).build()
        }
        "cam_framerate" -> prop(PropertyId.PROPERTY_ID_CAM_FRAMERATE) {
            camFramerate = CamFramerateProperty.newBuilder()
                .setPlaybackFrameRate(FrameRate.newBuilder().setNum(engine.fps).setDen(1)).build()
        }
        "cam_frame_rate_range" -> prop(PropertyId.PROPERTY_ID_CAM_FRAME_RATE_RANGE) {
            val b = CamFrameRateRangeProperty.newBuilder().setCameraId(engine.lens.id)
            val choices = engine.fpsChoices.ifEmpty { listOf(30) }
            choices.forEach { b.addFrameRateValues(FrameRate.newBuilder().setNum(it).setDen(1)) }
            b.setFrameRateMin(FrameRate.newBuilder().setNum(choices.first()).setDen(1))
            b.setFrameRateMax(FrameRate.newBuilder().setNum(choices.last()).setDen(1))
            camFrameRateRange = b.build()
        }
        "cam_sensibility" -> prop(PropertyId.PROPERTY_ID_CAM_SENSIBILITY) {
            camSensibility = CamSensibilityProperty.newBuilder()
                .setMeteringMode(if (engine.isoAuto) MeteringMode.METERING_MODE_AUTO else MeteringMode.METERING_MODE_MANUAL)
                .setSensibilityIso(if (engine.isoAuto) engine.isoStop(engine.liveIso) else engine.iso).build()
        }
        "cam_sensibility_range" -> prop(PropertyId.PROPERTY_ID_CAM_SENSIBILITY_RANGE) {
            camSensibilityRange = CamSensibilityRangeProperty.newBuilder().setCameraId(engine.lens.id)
                .setSensibilityIsoMin(engine.isoRange.lower).setSensibilityIsoMax(engine.isoRange.upper).build()
        }
        "cam_shutter" -> prop(PropertyId.PROPERTY_ID_CAM_SHUTTER) {
            val den = if (engine.shutterAuto) engine.shutterDenFromNs(engine.liveExposureNs) else engine.shutterDen
            camShutter = CamShutterProperty.newBuilder()
                .setMeteringMode(if (engine.shutterAuto) MeteringMode.METERING_MODE_AUTO else MeteringMode.METERING_MODE_MANUAL)
                .setShutterSpeed(Shutter.newBuilder().setNum(1).setDen(den)).build()
        }
        "cam_shutter_range" -> prop(PropertyId.PROPERTY_ID_CAM_SHUTTER_RANGE) {
            // Shutter is stored as a fraction num/den seconds; min = slowest (1/fps), max = fastest
            camShutterRange = CamShutterRangeProperty.newBuilder().setCameraId(engine.lens.id)
                .setShutterMin(Shutter.newBuilder().setNum(1).setDen(engine.fps.coerceAtLeast(1)))
                .setShutterMax(Shutter.newBuilder().setNum(1).setDen(8000)).build()
        }
        "cam_white_balance" -> prop(PropertyId.PROPERTY_ID_CAM_WHITE_BALANCE) {
            camWhiteBalance = CamWhiteBalanceProperty.newBuilder()
                .setMeteringMode(if (engine.wbAuto) MeteringMode.METERING_MODE_AUTO else MeteringMode.METERING_MODE_MANUAL)
                .setWhiteBalanceKelvin(if (engine.wbAuto) engine.liveKelvin else engine.kelvin).build()
        }
        "cam_white_balance_range" -> prop(PropertyId.PROPERTY_ID_CAM_WHITE_BALANCE_RANGE) {
            camWhiteBalanceRange = CamWhiteBalanceRangeProperty.newBuilder().setCameraId(engine.lens.id)
                .setWhiteBalanceKelvinMin(2500).setWhiteBalanceKelvinMax(10000).build()
        }
        "cam_tint" -> prop(PropertyId.PROPERTY_ID_CAM_TINT) {
            camTint = CamTintProperty.newBuilder()
                .setMeteringMode(if (engine.wbAuto) MeteringMode.METERING_MODE_AUTO else MeteringMode.METERING_MODE_MANUAL)
                .setTint(engine.tint).build()
        }
        "cam_tint_range" -> prop(PropertyId.PROPERTY_ID_CAM_TINT_RANGE) {
            camTintRange = CamTintRangeProperty.newBuilder().setCameraId(engine.lens.id).setTintMin(-50).setTintMax(50).build()
        }
        "cam_exposure_comp" -> prop(PropertyId.PROPERTY_ID_CAM_EXPOSURE_COMP) {
            camExposureComp = CamExposureCompProperty.newBuilder()
                .setMeteringMode(MeteringMode.METERING_MODE_AUTO).setExposureCompStops(engine.evStops).build()
        }
        "cam_exposure_comp_range" -> prop(PropertyId.PROPERTY_ID_CAM_EXPOSURE_COMP_RANGE) {
            val (lo, hi) = engine.evRange
            camExposureCompRange = CamExposureCompRangeProperty.newBuilder().setCameraId(engine.lens.id)
                .setExposureCompStopsMin(lo).setExposureCompStopsMax(hi).build()
        }
        "cam_focus" -> prop(PropertyId.PROPERTY_ID_CAM_FOCUS) {
            val b = CamFocusProperty.newBuilder().setIsAuto(engine.afAuto)
            if (!engine.afAuto) b.setFocusValue(engine.focus01) else if (engine.focusLockedByTap) b.setIsLocked(true)
            camFocus = b.build()
        }
        "cam_focus_range" -> prop(PropertyId.PROPERTY_ID_CAM_FOCUS_RANGE) {
            camFocusRange = CamFocusRangeProperty.newBuilder().setCameraId(engine.lens.id)
                .setFocusMin(0f).setFocusMax(1f).build()
        }
        "cam_zoom" -> prop(PropertyId.PROPERTY_ID_CAM_ZOOM) {
            camZoom = CamZoomProperty.newBuilder().setZoomFactor(engine.zoom)
                .setIsDisabled(engine.zoomRange.upper <= engine.zoomRange.lower).build()
        }
        "cam_stabilization" -> prop(PropertyId.PROPERTY_ID_CAM_STABILIZATION) {
            camStabilization = CamStabilizationProperty.newBuilder()
                .setLevel(if (engine.stabilization) StabilizationLevel.STABILIZATION_LEVEL_STANDARD
                          else StabilizationLevel.STABILIZATION_LEVEL_OFF).build()
        }
        "cam_stabilization_values" -> prop(PropertyId.PROPERTY_ID_CAM_STABILIZATION_VALUES) {
            camStabilizationValues = CamStabilizationValuesProperty.newBuilder().setCameraId(engine.lens.id)
                .addStabilizationLevels(StabilizationLevel.STABILIZATION_LEVEL_OFF)
                .addStabilizationLevels(StabilizationLevel.STABILIZATION_LEVEL_STANDARD).build()
        }
        "cam_light" -> prop(PropertyId.PROPERTY_ID_CAM_LIGHT) {
            camLight = CamLightProperty.newBuilder().setIsEnabled(engine.torch).build()
        }
        "cam_orientation" -> prop(PropertyId.PROPERTY_ID_CAM_ORIENTATION) {
            camOrientation = CamOrientationProperty.newBuilder().setOrientation(CamOrientation.CAM_ORIENTATION_LANDSCAPE).build()
        }
        "recording_state" -> prop(PropertyId.PROPERTY_ID_RECORDING_STATE) {
            recordingState = recordingState()
        }
        "recording_format" -> prop(PropertyId.PROPERTY_ID_RECORDING_FORMAT) {
            val f = engine.format
            recordingFormat = CamRecordingFormatProperty.newBuilder().setWidth(f.width).setHeight(f.height)
                .setIsOpenGate(f.isOpenGate).setColorSpace(ColorSpace.COLOR_SPACE_REC709).build()
        }
        "battery" -> prop(PropertyId.PROPERTY_ID_BATTERY) {
            battery = BatteryProperty.newBuilder().setAvailablePercentage(batteryPct()).build()
        }
        "active_storage" -> prop(PropertyId.PROPERTY_ID_ACTIVE_STORAGE) {
            val (total, free, runtime) = recorder.storage(takes.config().bitrate)
            activeStorage = ActiveStorageProperty.newBuilder().setStorageDevice(
                StorageDevice.newBuilder().setName("Internal").setIsInternal(true)
                    .setTotalSizeBytes(total).setFreeSizeBytes(free).setRuntimeSecs(runtime)).build()
        }
        "audio_source" -> prop(PropertyId.PROPERTY_ID_AUDIO_SOURCE) {
            audioSource = AudioSourceProperty.newBuilder().setActiveSourceName("Built-in").setNumOfChannels(2).build()
        }
        "slate" -> prop(PropertyId.PROPERTY_ID_SLATE) {
            slate = SlateProperty.newBuilder().setReel(slateReel).setScene(slateScene).setTake(slateTake).build()
        }
        "state" -> prop(PropertyId.PROPERTY_ID_STATE) {
            state = StateProperty.newBuilder().setControlMode(ControlMode.CONTROL_MODE_FULL_CONTROL).build()
        }
        else -> null
    }

    private fun recordingState(): RecordingStateProperty {
        val b = RecordingStateProperty.newBuilder()
            .setTimecodeMode(TimeCodeMode.TIME_CODE_MODE_RECORD_RUN)
        if (recorder.isRecording) {
            // CineRemote counts the take from system_timecode = wall-clock START (Unix seconds + frames)
            val startSec = recorder.startWallMs / 1000.0
            b.setIsRecording(true).setIsWritingToStorage(true)
                .setSystemTimecode(timecode(startSec))
        } else {
            b.setIsRecording(false)
            if (recorder.lastClipSecs >= 0) b.setRecordedDuration(timecode(recorder.lastClipSecs))
            b.setSystemTimecode(timecode(System.currentTimeMillis() / 1000.0))
        }
        return b.build()
    }

    private fun timecode(seconds: Double): TimeCode =
        TimeCode.newBuilder().setSeconds(seconds.toInt())
            .setFrameCount(((seconds - seconds.toInt()) * engine.fps).toInt()).build()

    // ------------------------------------------------------------------ properties: write
    private fun handleWrite(m: ControllerMessage) {
        val prop = m.writePropertyRequest.property
        val field = applyWrite(prop)
        out {
            controllerMessageId = m.messageId
            val b = WritePropertyResponse.newBuilder().setStatus(WritePropertyStatus.WRITE_PROPERTY_STATUS_OK)
            if (field != null) buildProperty(field)?.let { b.setCurrentValue(it) }   // echo so iOS-style readback isn't needed
            writePropertyResponse = b.build()
        }
    }

    /** Apply a WriteProperty to the engine; returns the property field name to echo, or null. */
    private fun applyWrite(prop: Property): String? = when (prop.valueCase) {
        Property.ValueCase.ACTIVE_CAM -> { engine.switchLens(prop.activeCam.cameraId); "active_cam" }
        Property.ValueCase.CAM_FRAMERATE -> {
            val fr = prop.camFramerate.playbackFrameRate
            engine.fps = (fr.num / fr.den.coerceAtLeast(1)).coerceIn(1, 240); prefs.fps = engine.fps
            engine.apply(); "cam_framerate"
        }
        Property.ValueCase.CAM_SENSIBILITY -> {
            val p = prop.camSensibility
            engine.isoAuto = p.meteringMode == MeteringMode.METERING_MODE_AUTO
            if (!engine.isoAuto && p.hasSensibilityIso()) engine.iso = p.sensibilityIso
            engine.apply(); "cam_sensibility"
        }
        Property.ValueCase.CAM_SHUTTER -> {
            val p = prop.camShutter
            engine.shutterAuto = p.meteringMode == MeteringMode.METERING_MODE_AUTO
            if (!engine.shutterAuto && p.hasShutterSpeed() && p.shutterSpeed.den > 0)
                engine.shutterDen = (p.shutterSpeed.den / p.shutterSpeed.num.coerceAtLeast(1))
            engine.apply(); "cam_shutter"
        }
        Property.ValueCase.CAM_WHITE_BALANCE -> {
            val p = prop.camWhiteBalance
            engine.wbAuto = p.meteringMode == MeteringMode.METERING_MODE_AUTO
            if (!engine.wbAuto && p.hasWhiteBalanceKelvin()) engine.kelvin = p.whiteBalanceKelvin
            engine.apply(); "cam_white_balance"
        }
        Property.ValueCase.CAM_TINT -> {
            val p = prop.camTint
            if (p.meteringMode == MeteringMode.METERING_MODE_MANUAL) engine.wbAuto = false
            if (p.hasTint()) engine.tint = p.tint
            engine.apply(); "cam_tint"
        }
        Property.ValueCase.CAM_EXPOSURE_COMP -> {
            if (prop.camExposureComp.hasExposureCompStops()) engine.evStops = prop.camExposureComp.exposureCompStops
            engine.apply(); "cam_exposure_comp"
        }
        Property.ValueCase.CAM_FOCUS -> {
            val p = prop.camFocus
            engine.afAuto = p.isAuto
            engine.focusLockedByTap = false
            if (!p.isAuto && p.hasFocusValue()) engine.focus01 = p.focusValue.coerceIn(0f, 1f)
            engine.apply(); "cam_focus"
        }
        Property.ValueCase.CAM_ZOOM -> { engine.zoom = prop.camZoom.zoomFactor; engine.apply(); "cam_zoom" }
        Property.ValueCase.CAM_STABILIZATION -> {
            engine.stabilization = prop.camStabilization.level != StabilizationLevel.STABILIZATION_LEVEL_OFF
            engine.apply(); "cam_stabilization"
        }
        Property.ValueCase.CAM_LIGHT -> { engine.torch = prop.camLight.isEnabled; engine.apply(); "cam_light" }
        Property.ValueCase.CAM_POINT_OF_INTEREST -> {
            val pt = prop.camPointOfInterest.point
            engine.tapFocus(pt.x, pt.y); "cam_focus"
        }
        Property.ValueCase.SLATE -> {
            val s = prop.slate
            if (s.hasReel()) slateReel = s.reel
            if (s.hasScene()) slateScene = s.scene
            if (s.hasTake()) slateTake = s.take
            "slate"
        }
        else -> { Log.d(TAG, "unhandled write ${prop.valueCase}"); null }
    }

    // ------------------------------------------------------------------ recording
    // Takes notifies MainActivity when a take really starts/ends, which emits recording_state to us.
    private fun startRecording(): Boolean = takes.start()

    private fun stopRecording() = takes.stop()

    private fun fieldFor(id: PropertyId): String? = ID_TO_FIELD[id]

    companion object {
        const val TAG = "CRCam.Control"
        // Order matters a little: cameras first, then per-camera ranges, then values.
        val FIELDS = listOf(
            "available_cams", "active_cam", "state", "battery", "active_storage", "audio_source", "slate",
            "cam_orientation", "recording_format", "recording_state",
            "cam_framerate", "cam_frame_rate_range", "cam_sensibility", "cam_sensibility_range",
            "cam_shutter", "cam_shutter_range", "cam_white_balance", "cam_white_balance_range",
            "cam_tint", "cam_tint_range", "cam_exposure_comp", "cam_exposure_comp_range",
            "cam_focus", "cam_focus_range", "cam_zoom", "cam_stabilization", "cam_stabilization_values", "cam_light")
        private val ID_TO_FIELD = mapOf(
            PropertyId.PROPERTY_ID_AVAILABLE_CAMS to "available_cams",
            PropertyId.PROPERTY_ID_ACTIVE_CAM to "active_cam",
            PropertyId.PROPERTY_ID_STATE to "state",
            PropertyId.PROPERTY_ID_BATTERY to "battery",
            PropertyId.PROPERTY_ID_ACTIVE_STORAGE to "active_storage",
            PropertyId.PROPERTY_ID_AUDIO_SOURCE to "audio_source",
            PropertyId.PROPERTY_ID_SLATE to "slate",
            PropertyId.PROPERTY_ID_CAM_ORIENTATION to "cam_orientation",
            PropertyId.PROPERTY_ID_RECORDING_FORMAT to "recording_format",
            PropertyId.PROPERTY_ID_RECORDING_STATE to "recording_state",
            PropertyId.PROPERTY_ID_CAM_FRAMERATE to "cam_framerate",
            PropertyId.PROPERTY_ID_CAM_FRAME_RATE_RANGE to "cam_frame_rate_range",
            PropertyId.PROPERTY_ID_CAM_SENSIBILITY to "cam_sensibility",
            PropertyId.PROPERTY_ID_CAM_SENSIBILITY_RANGE to "cam_sensibility_range",
            PropertyId.PROPERTY_ID_CAM_SHUTTER to "cam_shutter",
            PropertyId.PROPERTY_ID_CAM_SHUTTER_RANGE to "cam_shutter_range",
            PropertyId.PROPERTY_ID_CAM_WHITE_BALANCE to "cam_white_balance",
            PropertyId.PROPERTY_ID_CAM_WHITE_BALANCE_RANGE to "cam_white_balance_range",
            PropertyId.PROPERTY_ID_CAM_TINT to "cam_tint",
            PropertyId.PROPERTY_ID_CAM_TINT_RANGE to "cam_tint_range",
            PropertyId.PROPERTY_ID_CAM_EXPOSURE_COMP to "cam_exposure_comp",
            PropertyId.PROPERTY_ID_CAM_EXPOSURE_COMP_RANGE to "cam_exposure_comp_range",
            PropertyId.PROPERTY_ID_CAM_FOCUS to "cam_focus",
            PropertyId.PROPERTY_ID_CAM_FOCUS_RANGE to "cam_focus_range",
            PropertyId.PROPERTY_ID_CAM_ZOOM to "cam_zoom",
            PropertyId.PROPERTY_ID_CAM_STABILIZATION to "cam_stabilization",
            PropertyId.PROPERTY_ID_CAM_STABILIZATION_VALUES to "cam_stabilization_values",
            PropertyId.PROPERTY_ID_CAM_LIGHT to "cam_light")
    }
}
