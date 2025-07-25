/*
 * Copyright (C) 2024 pedroSG94.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.pedro.encoder.input.video

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import androidx.annotation.RequiresApi
import com.pedro.common.secureGet
import com.pedro.encoder.input.video.Camera2ResolutionCalculator.getOptimalResolution
import com.pedro.encoder.input.video.CameraHelper.Facing
import com.pedro.encoder.input.video.facedetector.FaceDetectorCallback
import com.pedro.encoder.input.video.facedetector.mapCamera2Faces
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Created by pedro on 4/03/17.
 *
 *
 *
 * Class for use surfaceEncoder to buffer encoder.
 * Advantage = you can use all resolutions.
 * Disadvantages = you cant control fps of the stream, because you cant know when the inputSurface
 * was renderer.
 *
 *
 * Note: you can use opengl for surfaceEncoder to buffer encoder on devices 21 < API > 16:
 * https://github.com/google/grafika
 */
@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
class Camera2ApiManager(context: Context) : CameraDevice.StateCallback() {
    private val TAG = "Camera2ApiManager"

    private var cameraDevice: CameraDevice? = null
    private var surfaceEncoder = Surface(SurfaceTexture(-1).apply { release() }) //input surfaceEncoder from videoEncoder
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraHandler: Handler? = null
    private var cameraCaptureSession: CameraCaptureSession? = null
    private var isPrepared: Boolean = false
    private var cameraId: String = "0"
    private var physicalCameraId: String? = null
    private var facing = Facing.BACK
    private var builderInputSurface: CaptureRequest.Builder? = null
    private var fingerSpacing = 0f
    private var zoomLevel = 0f
    var isLanternEnabled: Boolean = false
        private set
    var isVideoStabilizationEnabled: Boolean = false
        private set
    var isOpticalStabilizationEnabled: Boolean = false
        private set
    var isAutoFocusEnabled: Boolean = true
        private set
    var isAutoExposureEnabled: Boolean = false
        private set
    var isAutoWhiteBalanceEnabled: Boolean = true
        private set
    var isRunning: Boolean = false
        private set
    private var fps = 30
    private val semaphore = Semaphore(0)
    private var cameraCallbacks: CameraCallbacks? = null
    private var requiredSize: Size? = null

    interface ImageCallback {
        fun onImageAvailable(image: Image)
    }

    private var sensorOrientation = 0
    private var faceSensorScale: Rect? = null
    private var faceDetectorCallback: FaceDetectorCallback? = null
    private var frameCapturedCallback: FrameCapturedCallback? = null
    private var faceDetectionEnabled = false
    private var faceDetectionMode = 0
    private var imageReader: ImageReader? = null

    init {
        cameraId = try { getCameraIdForFacing(Facing.BACK) } catch (_: Exception) { "0" }
    }

    fun prepareCamera(surfaceTexture: SurfaceTexture, width: Int, height: Int, fps: Int) {
        val optimalResolution = requiredSize ?: getOptimalResolution(Size(width, height), getCameraResolutions(facing))
        Log.i(TAG, "optimal resolution set to: " + optimalResolution.width + "x" + optimalResolution.height)
        surfaceTexture.setDefaultBufferSize(optimalResolution.width, optimalResolution.height)
        this.surfaceEncoder = Surface(surfaceTexture)
        this.fps = fps
        isPrepared = true
    }

    fun prepareCamera(surfaceTexture: SurfaceTexture, width: Int, height: Int, fps: Int, facing: Facing) {
        this.facing = facing
        prepareCamera(surfaceTexture, width, height, fps)
    }

    fun prepareCamera(surfaceTexture: SurfaceTexture, width: Int, height: Int, fps: Int, cameraId: String) {
        this.cameraId = cameraId
        this.facing = getFacingByCameraId(cameraManager, cameraId)
        prepareCamera(surfaceTexture, width, height, fps)
    }

    fun prepareCamera(surface: Surface, fps: Int) {
        this.surfaceEncoder = surface
        this.fps = fps
        isPrepared = true
    }

    private fun startPreview(cameraDevice: CameraDevice) {
        try {
            val listSurfaces = mutableListOf<Surface>()
            listSurfaces.add(surfaceEncoder)
            imageReader?.let { listSurfaces.add(it.surface) }
            val captureRequest = drawSurface(cameraDevice, listSurfaces)
            createCaptureSession(
                cameraDevice,
                listSurfaces,
                onConfigured = {
                    cameraCaptureSession = it
                    try {
                        it.setRepeatingRequest(
                            captureRequest,
                            if (faceDetectionEnabled || frameCapturedCallback != null) cb else null,
                            cameraHandler
                        )
                    } catch (_: IllegalStateException) {
                        reOpenCamera(cameraId)
                    } catch (e: Exception) {
                        cameraCallbacks?.onCameraError("Create capture session failed: " + e.message)
                        Log.e(TAG, "Error", e)
                    }
                },
                onConfiguredFailed = {
                    it.close()
                    cameraCallbacks?.onCameraError("Configuration failed")
                    Log.e(TAG, "Configuration failed")
                },
                cameraHandler
            )
        } catch (_: IllegalStateException) {
            reOpenCamera(cameraId)
        } catch (e: Exception) {
            cameraCallbacks?.onCameraError("Create capture session failed: " + e.message)
            Log.e(TAG, "Error", e)
        }
    }

    @Throws(IllegalStateException::class, Exception::class)
    private fun drawSurface(cameraDevice: CameraDevice, surfaces: List<Surface>): CaptureRequest {
        val builderInputSurface = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        for (surface in surfaces) builderInputSurface.addTarget(surface)
        builderInputSurface.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        val validFps = min(60, fps)
        builderInputSurface.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(validFps, validFps))
        this.builderInputSurface = builderInputSurface
        return builderInputSurface.build()
    }

    fun getSupportedFps(size: Size?, facing: Facing): List<Range<Int>> {
        try {
            val characteristics = cameraManager.getCameraCharacteristics(getCameraIdForFacing(facing))
            val fpsSupported = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return emptyList()
            return if (size != null) {
                val streamConfigurationMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val list = mutableListOf<Range<Int>>()
                val fd = streamConfigurationMap?.getOutputMinFrameDuration(SurfaceTexture::class.java, size) ?: return emptyList()
                val maxFPS = (10f / "0.$fd".toFloat()).toInt()
                for (r in fpsSupported) { if (r.upper <= maxFPS) { list.add(r) } }
                list
            } else listOf(*fpsSupported)
        } catch (e: Exception) {
            Log.e(TAG, "Error", e)
            return emptyList()
        }
    }

    val levelSupported: Int
        get() {
            val characteristics = cameraCharacteristics ?: return -1
            return characteristics.secureGet(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: -1
        }

    fun setRequiredResolution(size: Size?) {
        requiredSize = size
    }

    fun openCameraBack() {
        openCameraFacing(Facing.BACK)
    }

    fun openCameraFront() {
        openCameraFacing(Facing.FRONT)
    }

    fun openLastCamera() {
        openCameraId(cameraId)
    }

    fun setCameraId(cameraId: String) {
        this.cameraId = cameraId
    }

    var cameraFacing: Facing
        get() = facing
        set(cameraFacing) {
            try {
                val cameraId = getCameraIdForFacing(cameraFacing)
                facing = cameraFacing
                this.cameraId = cameraId
            } catch (e: Exception) {
                Log.e(TAG, "Error", e)
            }
        }

    val cameraResolutionsBack: Array<Size>
        get() = getCameraResolutions(Facing.BACK)

    val cameraResolutionsFront: Array<Size>
        get() = getCameraResolutions(Facing.FRONT)

    fun getCameraResolutions(facing: Facing): Array<Size> = getCameraResolutions(getCameraIdForFacing(facing))

    fun getCameraResolutions(cameraId: String): Array<Size> {
        try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val streamConfigurationMap = characteristics.secureGet(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return arrayOf()
            val outputSizes = streamConfigurationMap.getOutputSizes(SurfaceTexture::class.java)
            return outputSizes ?: arrayOf()
        } catch (e: Exception) {
            Log.e(TAG, "Error", e)
            return arrayOf()
        }
    }

    val cameraCharacteristics: CameraCharacteristics?
        get() {
            try {
                return cameraManager.getCameraCharacteristics(cameraId)
            } catch (e: Exception) {
                Log.e(TAG, "Error", e)
                return null
            }
        }

    @RequiresApi(Build.VERSION_CODES.P)
    fun getPhysicalCamerasAvailable(): List<String> {
        return cameraCharacteristics?.physicalCameraIds?.toList() ?: emptyList()
    }

    @RequiresApi(Build.VERSION_CODES.P)
    fun setPhysicalCamera(id: String?) {
        physicalCameraId = id
    }

    @RequiresApi(Build.VERSION_CODES.P)
    fun openPhysicalCamera(id: String?) {
        setPhysicalCamera(id)
        if (isRunning) reOpenCamera(cameraId)
        else openCameraId(cameraId)
    }

    private fun applyRequest(builder: CaptureRequest.Builder): Boolean {
        this.builderInputSurface = builder
        val cameraCaptureSession = this.cameraCaptureSession ?: return false
        try {
            cameraCaptureSession.setRepeatingRequest(
                builder.build(),
                if (faceDetectionEnabled || frameCapturedCallback != null) cb else null, null
            )
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error", e)
            return false
        }
    }

    /**
     * @param mode value from CameraCharacteristics.CONTROL_AWB_MODE_*
     */
    fun enableAutoWhiteBalance(mode: Int): Boolean {
        val characteristics = cameraCharacteristics ?: return false
        val builderInputSurface = this.builderInputSurface ?: return false
        val modes = characteristics.secureGet(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: return false
        if (!modes.contains(mode)) return false
        builderInputSurface.set(CaptureRequest.CONTROL_AWB_MODE, mode)
        isAutoWhiteBalanceEnabled = applyRequest(builderInputSurface)
        return isAutoWhiteBalanceEnabled
    }

    fun disableAutoWhiteBalance() {
        val characteristics = cameraCharacteristics ?: return
        val builderInputSurface = this.builderInputSurface ?: return
        val modes = characteristics.secureGet(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: return
        if (!modes.contains(CaptureRequest.CONTROL_AWB_MODE_OFF)) return
        builderInputSurface.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
        applyRequest(builderInputSurface)
        isAutoWhiteBalanceEnabled = false
    }

    fun getAutoWhiteBalanceModesAvailable(): List<Int> {
        val characteristics = cameraCharacteristics ?: return listOf()
        return characteristics.secureGet(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toList() ?: listOf()
    }

    fun getWhiteBalance(): Int {
        val default = CameraCharacteristics.CONTROL_AWB_MODE_AUTO
        val builderInputSurface = this.builderInputSurface ?: return default
        return builderInputSurface.secureGet(CaptureRequest.CONTROL_AWB_MODE) ?: default
    }

    fun setColorCorrectionGains(red: Float, greenEven: Float, greenOdd: Float, blue: Float): Boolean {
        val characteristics = cameraCharacteristics ?: return false
        val builderInputSurface = this.builderInputSurface ?: return false
        val modes = characteristics.secureGet(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES) ?: return false
        if (!modes.contains(CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)) return false
        builderInputSurface.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
        builderInputSurface.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
        builderInputSurface.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, characteristics.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1))
        val vector = RggbChannelVector(red, greenEven, greenOdd, blue)
        builderInputSurface.set(CaptureRequest.COLOR_CORRECTION_GAINS, vector)
        return applyRequest(builderInputSurface)
    }

    fun enableAutoExposure(): Boolean {
        val characteristics = cameraCharacteristics ?: return false
        val builderInputSurface = this.builderInputSurface ?: return false
        val modes = characteristics.secureGet(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES) ?: return false
        if (!modes.contains(CaptureRequest.CONTROL_AE_MODE_ON)) return false
        builderInputSurface.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        isAutoExposureEnabled = applyRequest(builderInputSurface)
        return isAutoExposureEnabled
    }

    fun disableAutoExposure() {
        val characteristics = cameraCharacteristics ?: return
        val builderInputSurface = this.builderInputSurface ?: return
        val modes = characteristics.secureGet(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES) ?: return
        if (!modes.contains(CaptureRequest.CONTROL_AE_MODE_ON)) return
        builderInputSurface.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        applyRequest(builderInputSurface)
        isAutoExposureEnabled = false
    }

    fun enableVideoStabilization(): Boolean {
        val characteristics = cameraCharacteristics ?: return false
        val builderInputSurface = this.builderInputSurface ?: return false
        val modes = characteristics.secureGet(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: return false
        if (!modes.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)) return false
        builderInputSurface.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
        isVideoStabilizationEnabled = applyRequest(builderInputSurface)
        return isVideoStabilizationEnabled
    }

    fun disableVideoStabilization() {
        val characteristics = cameraCharacteristics ?: return
        val builderInputSurface = this.builderInputSurface ?: return
        val modes = characteristics.secureGet(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: return
        if (!modes.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)) return
        builderInputSurface.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        applyRequest(builderInputSurface)
        isVideoStabilizationEnabled = false
    }

    fun enableOpticalVideoStabilization(): Boolean {
        val characteristics = cameraCharacteristics ?: return false
        val builderInputSurface = this.builderInputSurface ?: return false
        val opticalStabilizationModes = characteristics.secureGet(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: return false
        if (!opticalStabilizationModes.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)) return false
        builderInputSurface.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
        isOpticalStabilizationEnabled = applyRequest(builderInputSurface)
        return isOpticalStabilizationEnabled
    }

    fun disableOpticalVideoStabilization() {
        val characteristics = cameraCharacteristics ?: return
        val builderInputSurface = this.builderInputSurface ?: return
        val modes = characteristics.secureGet(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: return
        if (!modes.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)) return
        builderInputSurface.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
        applyRequest(builderInputSurface)
        isOpticalStabilizationEnabled = false
    }

    fun setFocusDistance(distance: Float) {
        val builderInputSurface = this.builderInputSurface ?: return
        builderInputSurface.set(CaptureRequest.LENS_FOCUS_DISTANCE, max(0f, distance))
        applyRequest(builderInputSurface)
    }

    fun getCurrentCameraId()  = cameraId

    var exposure: Int
        get() {
            val builderInputSurface = this.builderInputSurface ?: return 0
            return builderInputSurface.secureGet(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION) ?: 0
        }
        set(value) {
            val characteristics = cameraCharacteristics ?: return
            val builderInputSurface = this.builderInputSurface ?: return
            val supportedExposure = characteristics.secureGet(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: return
            val v = value.coerceIn(supportedExposure.lower, supportedExposure.upper)
            builderInputSurface.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, v)
            applyRequest(builderInputSurface)
        }


    val maxExposure: Int
        get() {
            val characteristics = cameraCharacteristics ?: return 0
            val supportedExposure = characteristics.secureGet(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.upper ?: 0
            return supportedExposure
        }

    val minExposure: Int
        get() {
            val characteristics = cameraCharacteristics ?: return 0
            val supportedExposure = characteristics.secureGet(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.lower ?: 0
            return supportedExposure
        }

    fun tapToFocus(view: View, event: MotionEvent): Boolean {
        val builderInputSurface = this.builderInputSurface ?: return false
        val characteristics = cameraCharacteristics ?: return false
        val session = cameraCaptureSession ?: return false
        if (characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) == 0) return false
        val focusTag = "focus"
        val sensorArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return false
        val x = event.x
        val y = event.y
        val focusX = (x / view.width.toFloat()) * sensorArraySize.width()
        val focusY = (y / view.height.toFloat()) * sensorArraySize.height()
        val focusRect = MeteringRectangle(
            (focusX - 100).toInt().coerceIn(0, sensorArraySize.width()),
            (focusY - 100).toInt().coerceIn(0, sensorArraySize.height()),
            (100 * 2).coerceIn(0, sensorArraySize.width()),
            (100 * 2).coerceIn(0, sensorArraySize.height()),
            MeteringRectangle.METERING_WEIGHT_MAX
        )

        val captureCallbackHandler: CameraCaptureSession.CaptureCallback =
            object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    super.onCaptureCompleted(session, request, result)
                    if (request.tag == focusTag) {
                        session.stopRepeating()
                        val area = request.get(CaptureRequest.CONTROL_AF_REGIONS)
                        builderInputSurface.setTag("")
                        builderInputSurface.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                        builderInputSurface.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
                        builderInputSurface.set(CaptureRequest.CONTROL_AF_REGIONS, area)
                        applyRequest(builderInputSurface)
                    }
                }
            }
        try {
            session.stopRepeating()
            builderInputSurface.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            builderInputSurface.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            session.capture(builderInputSurface.build(), captureCallbackHandler, null)

            builderInputSurface.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(focusRect))
            builderInputSurface.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
            builderInputSurface.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            builderInputSurface.setTag(focusTag)
            session.capture(builderInputSurface.build(), captureCallbackHandler, null)
            isAutoFocusEnabled = true
            return true
        } catch (_: Exception) {
            return false
        }
    }

    /**
     * Select camera facing
     *
     * @param selectedCameraFacing - CameraCharacteristics.LENS_FACING_FRONT,
     * CameraCharacteristics.LENS_FACING_BACK,
     * CameraCharacteristics.LENS_FACING_EXTERNAL
     */
    fun openCameraFacing(selectedCameraFacing: Facing) {
        try {
            val cameraId = getCameraIdForFacing(selectedCameraFacing)
            openCameraId(cameraId)
        } catch (e: Exception) {
            Log.e(TAG, "Error", e)
        }
    }

    val isLanternSupported: Boolean
        get() {
            val characteristics = cameraCharacteristics ?: return false
            val available = characteristics.secureGet(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: return false
            return available
        }

    /**
     * @required: <uses-permission android:name="android.permission.FLASHLIGHT"></uses-permission>
     */
    @Throws(Exception::class)
    fun enableLantern() {
        val builderInputSurface = this.builderInputSurface ?: return
        if (isLanternSupported) {
            try {
                builderInputSurface.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
                isLanternEnabled = applyRequest(builderInputSurface)
            } catch (e: Exception) {
                Log.e(TAG, "Error", e)
            }
        } else {
            Log.e(TAG, "Lantern unsupported")
            throw Exception("Lantern unsupported")
        }
    }

    /**
     * @required: <uses-permission android:name="android.permission.FLASHLIGHT"></uses-permission>
     */
    fun disableLantern() {
        val characteristics = cameraCharacteristics ?: return
        val builderInputSurface = this.builderInputSurface ?: return
        val available = characteristics.secureGet(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: return
        if (available) {
            try {
                builderInputSurface.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
                applyRequest(builderInputSurface)
                isLanternEnabled = false
            } catch (e: Exception) {
                Log.e(TAG, "Error", e)
            }
        }
    }

    fun enableAutoFocus(): Boolean {
        var result = false
        val characteristics = cameraCharacteristics ?: return false
        val supportedFocusModes = characteristics.secureGet(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: return false
        val builderInputSurface = this.builderInputSurface ?: return false
        try {
            if (supportedFocusModes.isNotEmpty()) {
                //cancel any existing AF trigger
                builderInputSurface.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
                builderInputSurface.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                applyRequest(builderInputSurface)
                if (supportedFocusModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
                    builderInputSurface.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    isAutoFocusEnabled = true
                } else if (supportedFocusModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO)) {
                    builderInputSurface.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                    isAutoFocusEnabled = true
                } else {
                    builderInputSurface.set(CaptureRequest.CONTROL_AF_MODE, supportedFocusModes[0])
                    isAutoFocusEnabled = false
                }
                applyRequest(builderInputSurface)
            }
            result = isAutoFocusEnabled
        } catch (e: Exception) {
            isAutoFocusEnabled = false
            Log.e(TAG, "Error", e)
        }
        return result
    }

    fun disableAutoFocus(): Boolean {
        val result = false
        val characteristics = cameraCharacteristics ?: return false
        val builderInputSurface = this.builderInputSurface ?: return false
        val supportedFocusModes = characteristics.secureGet(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: return false
        for (mode in supportedFocusModes) {
            try {
                if (mode == CaptureRequest.CONTROL_AF_MODE_OFF) {
                    builderInputSurface.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    applyRequest(builderInputSurface)
                    isAutoFocusEnabled = false
                    return true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error", e)
            }
        }
        return result
    }

    fun enableFaceDetection(faceDetectorCallback: FaceDetectorCallback?): Boolean {
        val characteristics = cameraCharacteristics ?: return false
        val builderInputSurface = this.builderInputSurface ?: return false
        faceSensorScale = characteristics.secureGet(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        sensorOrientation = characteristics.secureGet(CameraCharacteristics.SENSOR_ORIENTATION) ?: return false
        val fd = characteristics.secureGet(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES) ?: return false
        val maxFD = characteristics.secureGet(CameraCharacteristics.STATISTICS_INFO_MAX_FACE_COUNT) ?: return false
        if (fd.isEmpty() || maxFD <= 0) return false
        this.faceDetectorCallback = faceDetectorCallback
        faceDetectionEnabled = true
        faceDetectionMode = fd.toList().max()
        if (faceDetectionEnabled) {
            builderInputSurface.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, faceDetectionMode)
        }
        applyRequest(builderInputSurface)
        return true
    }


    fun enableFrameCaptureCallback(frameCapturedCallback: FrameCapturedCallback?) {
        this.frameCapturedCallback = frameCapturedCallback
    }

    fun disableFaceDetection() {
        if (faceDetectionEnabled) {
            faceDetectorCallback = null
            faceDetectionEnabled = false
            faceDetectionMode = 0
            builderInputSurface?.let { applyRequest(it) }
        }
    }

    fun isFaceDetectionEnabled() = faceDetectorCallback != null

    fun setCameraCallbacks(cameraCallbacks: CameraCallbacks?) {
        this.cameraCallbacks = cameraCallbacks
    }

    private val cb: CameraCaptureSession.CaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            val faces = result.get(CaptureResult.STATISTICS_FACES) ?: return
            faceDetectorCallback?.onGetFaces(
                faces = mapCamera2Faces(faces = faces),
                scaleSensor = faceSensorScale,
                sensorOrientation = sensorOrientation
            )
        }

        override fun onCaptureStarted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            timestamp: Long,
            frameNumber: Long
        ) {
            frameCapturedCallback?.onFrameCaptured(
                frameNumber = frameNumber,
                timestamp = timestamp
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun openCameraId(cameraId: String) {
        this.cameraId = cameraId
        if (isPrepared) {
            val cameraHandlerThread = HandlerThread("$TAG Id = $cameraId")
            cameraHandlerThread.start()
            cameraHandler = Handler(cameraHandlerThread.looper)
            try {
                cameraManager.openCamera(cameraId, this, cameraHandler)
                semaphore.acquireUninterruptibly()
                val cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)
                isRunning = true
                val facing = cameraCharacteristics.secureGet(CameraCharacteristics.LENS_FACING) ?: return
                this.facing = if (CameraMetadata.LENS_FACING_FRONT == facing) Facing.FRONT else Facing.BACK
                cameraCallbacks?.onCameraChanged(this.facing)
            } catch (e: Exception) {
                cameraCallbacks?.onCameraError("Open camera $cameraId failed")
                Log.e(TAG, "Error", e)
            }
        } else {
            throw IllegalStateException("You need prepare camera before open it")
        }
    }

    val camerasAvailable: Array<String> = cameraManager.cameraIdList

    fun switchCamera() {
        try {
            val cameraId = if (cameraDevice == null || facing == Facing.FRONT) {
                getCameraIdForFacing(Facing.BACK)
            } else {
                getCameraIdForFacing(Facing.FRONT)
            }
            reOpenCamera(cameraId)
        } catch (e: Exception) {
            Log.e(TAG, "Error", e)
        }
    }

    fun reOpenCamera(cameraId: String) {
        if (cameraDevice != null) {
            closeCamera(false)
            prepareCamera(surfaceEncoder, fps)
            openCameraId(cameraId)
        }
    }

    val zoomRange: Range<Float>
        get() {
            val characteristics = cameraCharacteristics ?: return Range(1f, 1f)
            var zoomRanges: Range<Float>? = null
            //only camera limited or better support this feature.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && levelSupported != CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) {
                zoomRanges = characteristics.secureGet(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            }
            if (zoomRanges == null) {
                val maxZoom = characteristics.secureGet(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
                zoomRanges = Range(1f, maxZoom)
            }
            return zoomRanges
        }

    var zoom: Float
        get() = zoomLevel
        set(level) {
            val characteristics = cameraCharacteristics ?: return
            val builderInputSurface = this.builderInputSurface ?: return
            val l = level.coerceIn(zoomRange.lower, zoomRange.upper)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && levelSupported != CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) {
                    builderInputSurface.set(CaptureRequest.CONTROL_ZOOM_RATIO, l)
                } else {
                    val rect = characteristics.secureGet(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
                    //This ratio is the ratio of cropped Rect to Camera's original(Maximum) Rect
                    val ratio = 1f / l
                    //croppedWidth and croppedHeight are the pixels cropped away, not pixels after cropped
                    val croppedWidth = rect.width() - (rect.width().toFloat() * ratio).roundToInt()
                    val croppedHeight = rect.height() - (rect.height().toFloat() * ratio).roundToInt()
                    //Finally, zoom represents the zoomed visible area
                    val zoom = Rect(
                        croppedWidth / 2, croppedHeight / 2, rect.width() - croppedWidth / 2,
                        rect.height() - croppedHeight / 2
                    )
                    builderInputSurface.set(CaptureRequest.SCALER_CROP_REGION, zoom)
                }
                applyRequest(builderInputSurface)
                zoomLevel = l
            } catch (e: Exception) {
                Log.e(TAG, "Error", e)
            }
        }

    fun getOpticalZooms(): Array<Float> {
        val characteristics = cameraCharacteristics ?: return arrayOf()
        return characteristics.secureGet(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toTypedArray() ?: arrayOf()
    }

    fun setOpticalZoom(level: Float) {
        val builderInputSurface = this.builderInputSurface ?: return
        try {
            builderInputSurface.set(CaptureRequest.LENS_FOCAL_LENGTH, level)
            applyRequest(builderInputSurface)
        } catch (e: Exception) {
            Log.e(TAG, "Error", e)
        }
    }

    @JvmOverloads
    fun setZoom(event: MotionEvent, delta: Float = 0.1f) {
        if (event.pointerCount < 2 || event.action != MotionEvent.ACTION_MOVE) return
        val currentFingerSpacing = CameraHelper.getFingerSpacing(event)
        if (currentFingerSpacing > fingerSpacing) {
            zoom += delta
        } else if (currentFingerSpacing < fingerSpacing) {
            zoom -= delta
        }
        fingerSpacing = currentFingerSpacing
    }

    @JvmOverloads
    fun closeCamera(resetSurface: Boolean = true) {
        isLanternEnabled = false
        zoomLevel = 1.0f
        cameraCaptureSession?.close()
        cameraCaptureSession = null
        cameraDevice?.close()
        cameraDevice = null
        cameraHandler?.looper?.quitSafely()
        cameraHandler = null
        if (resetSurface) {
            surfaceEncoder = Surface(SurfaceTexture(-1).apply { release() })
            builderInputSurface = null
        }
        isPrepared = false
        isRunning = false
    }

    fun addImageListener(width: Int, height: Int, format: Int, maxImages: Int, autoClose: Boolean, listener: ImageCallback) {
        val wasRunning = isRunning
        closeCamera(false)
        this.imageReader?.close()
        val imageThread = HandlerThread("$TAG imageThread")
        imageThread.start()
        val imageReader = ImageReader.newInstance(width, height, format, maxImages)
        imageReader.setOnImageAvailableListener({ reader: ImageReader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                listener.onImageAvailable(image)
                if (autoClose) image.close()
            }
        }, Handler(imageThread.looper))
        this.imageReader = imageReader
        if (wasRunning) {
            prepareCamera(surfaceEncoder, fps)
            openLastCamera()
        }
    }

    fun removeImageListener() {
        val imageReader = this.imageReader ?: return
        val wasRunning = isRunning
        if (wasRunning) closeCamera(false)
        imageReader.close()
        this.imageReader = null
        if (wasRunning) {
            prepareCamera(surfaceEncoder, fps)
            openLastCamera()
        }
    }

    override fun onOpened(cameraDevice: CameraDevice) {
        this.cameraDevice = cameraDevice
        startPreview(cameraDevice)
        semaphore.release()
        cameraCallbacks?.onCameraOpened()
        Log.i(TAG, "Camera opened")
    }

    override fun onDisconnected(cameraDevice: CameraDevice) {
        cameraDevice.close()
        semaphore.release()
        cameraCallbacks?.onCameraDisconnected()
        Log.i(TAG, "Camera disconnected")
    }

    override fun onError(cameraDevice: CameraDevice, i: Int) {
        cameraDevice.close()
        semaphore.release()
        cameraCallbacks?.onCameraError("Open camera failed: $i")
        Log.e(TAG, "Open failed: $i")
    }

    @JvmOverloads
    fun getCameraIdForFacing(facing: Facing, cameraManager: CameraManager = this.cameraManager): String {
        val selectedFacing = if (facing == Facing.BACK) CameraMetadata.LENS_FACING_BACK else CameraMetadata.LENS_FACING_FRONT
        val ids = cameraManager.cameraIdList
        for (cameraId in ids) {
            val cameraFacing = cameraManager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.LENS_FACING)
            if (cameraFacing != null && cameraFacing == selectedFacing) {
                return cameraId
            }
        }
        if (ids.isEmpty()) throw CameraOpenException("Camera no detected")
        return ids[0]
    }

    private fun getFacingByCameraId(cameraManager: CameraManager, cameraId: String): Facing {
        try {
            for (id in cameraManager.cameraIdList) {
                if (id == cameraId) {
                    val cameraFacing = cameraManager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.LENS_FACING)
                    return if (cameraFacing == CameraMetadata.LENS_FACING_BACK) Facing.BACK
                    else Facing.FRONT
                }
            }
            return Facing.BACK
        } catch (_: Exception) {
            return Facing.BACK
        }
    }

    @Suppress("DEPRECATION")
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun createCaptureSession(
        cameraDevice: CameraDevice,
        surfaces: List<Surface>,
        onConfigured: (CameraCaptureSession) -> Unit,
        onConfiguredFailed: (CameraCaptureSession) -> Unit,
        handler: Handler?
    ) {
        val callback = object: CameraCaptureSession.StateCallback() {
            override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                onConfigured(cameraCaptureSession)
            }

            override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                onConfiguredFailed(cameraCaptureSession)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val configurations = surfaces.map { OutputConfiguration(it) }
            configurations.forEach { it.setPhysicalCameraId(physicalCameraId) }
            val config = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                configurations,
                Executors.newSingleThreadExecutor(),
                callback
            )
            cameraDevice.createCaptureSession(config)
        } else {
            cameraDevice.createCaptureSession(surfaces, callback, handler)
        }
    }
}