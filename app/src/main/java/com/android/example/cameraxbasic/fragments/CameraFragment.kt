/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.example.cameraxbasic.fragments

import android.annotation.SuppressLint
import android.content.*
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.camera.core.*
import androidx.camera.core.AspectRatio.RATIO_4_3
import androidx.camera.core.ImageCapture.Metadata
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.net.toFile
import androidx.core.view.setPadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.Navigation
import androidx.window.WindowManager
import com.android.example.cameraxbasic.KEY_EVENT_ACTION
import com.android.example.cameraxbasic.KEY_EVENT_EXTRA
import com.android.example.cameraxbasic.MainActivity
import com.android.example.cameraxbasic.R
import com.android.example.cameraxbasic.utils.ANIMATION_FAST_MILLIS
import com.android.example.cameraxbasic.utils.ANIMATION_SLOW_MILLIS
import com.android.example.cameraxbasic.utils.simulateClick
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.google.android.material.internal.ViewOverlayImpl
import kotlinx.android.synthetic.main.camera_ui_container.*
import kotlinx.android.synthetic.main.fragment_camera.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.collections.ArrayList
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min


/** Helper type alias used for analysis use case callbacks */
typealias LumaListener = (luma: Double) -> Unit

/**
 * Main fragment for this app. Implements all camera operations including:
 * - Viewfinder
 * - Photo taking
 * - Image analysis
 */
@Suppress("DEPRECATION", "DEPRECATION")
class CameraFragment : Fragment() {

    private lateinit var container: ConstraintLayout
    private lateinit var viewFinder: PreviewView
    private lateinit var outputDirectory: File
    private lateinit var broadcastManager: LocalBroadcastManager

    private var displayId: Int = -1
    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var windowManager: WindowManager

    val describeImgText = null

    private val displayManager by lazy {
        requireContext().getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    }

    lateinit var savedUri:Uri
    //lateinit var currentPhotoPath: String
    lateinit var selected_bitmap : Bitmap
    var requestQueue: RequestOptions? = null
    var myUrl = "http://15.165.119.213:5000/formdata"
    var responsePhotoURI:String? = null
    var resultBody:String?=null

    lateinit var tv : Button


    /** Blocking camera operations are performed using this executor
     * :카메라 차단 작업은 이 실행기를 사용하여 수행됩니다. */
    private lateinit var cameraExecutor: ExecutorService

    /** Volume down button receiver used to trigger shutter
     * 셔터를 트리거하는 데 사용되는 볼륨 다운 버튼 수신기. */
    private val volumeDownReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(KEY_EVENT_EXTRA, KeyEvent.KEYCODE_UNKNOWN)) {
                // When the volume down button is pressed, simulate a shutter button click
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    val shutter = container
                            .findViewById<ImageButton>(R.id.camera_capture_button)
                    shutter.simulateClick()
                }
            }
        }
    }

    /**
     * We need a display listener for orientation changes that do not trigger a configuration
     * change, for example if we choose to override config change in manifest or for 180-degree
     * orientation changes.
     * 구성 변경을 트리거하지 않는 방향 변경을 위해 디스플레이 수신기가 필요합니다.
     * 예를 들어 매니페스트에서 구성 변경을 재정의하거나 180도 방향을 변경하도록 선택하는 경우입니다.
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = view?.let { view ->
            if (displayId == this@CameraFragment.displayId) {
                Log.d(TAG, "Rotation changed: ${view.display.rotation}")
                imageCapture?.targetRotation = view.display.rotation
                imageAnalyzer?.targetRotation = view.display.rotation
            }
        } ?: Unit
    }

    override fun onResume() {
        super.onResume()
        // Make sure that all permissions are still present, since the
        // user could have removed them while the app was in paused state.
        //앱이 일시 중지된 상태에서 사용자가 모든 권한을 제거할 수 있으므로 모든 권한이 여전히 존재하는지 확인하십시오.
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            Navigation.findNavController(requireActivity(), R.id.fragment_container).navigate(
                    CameraFragmentDirections.actionCameraToPermissions()
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()

        // Shut down our background executor
        // 배경 유언 집행인을 폐쇄하다
        cameraExecutor.shutdown()

        // Unregister the broadcast receivers and listeners
        //브로드캐스트 수신기 및 수신기 등록 취소
        broadcastManager.unregisterReceiver(volumeDownReceiver)
        displayManager.unregisterDisplayListener(displayListener)
    }

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?,
    ): View? =
            inflater.inflate(R.layout.fragment_camera, container, false)

    private fun setGalleryThumbnail(uri: Uri) {
        // Reference of the view that holds the gallery thumbnail
        // 갤러리 축소판 그림을 보관하는 뷰의 참조
        val thumbnail = container.findViewById<ImageButton>(R.id.photo_view_button)

        // Run the operations in the view's thread
        // 보기 스레드에서 작업 실행
        thumbnail.post {

            // Remove thumbnail padding
            // 축소판 그림 패딩 제거
            thumbnail.setPadding(resources.getDimension(R.dimen.stroke_small).toInt())

            // Load thumbnail into circular button using Glide
            // 글라이드(Glide)를 사용하여 섬네일을 원형 버튼에 로드
            Glide.with(thumbnail)
                    .load(uri)
                    .apply(RequestOptions.circleCropTransform())
                    .into(thumbnail)
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        container = view as ConstraintLayout
        viewFinder = container.findViewById(R.id.view_finder)

        // Initialize our background executor
        // 백그라운드 실행자 초기화
        cameraExecutor = Executors.newSingleThreadExecutor()

        broadcastManager = LocalBroadcastManager.getInstance(view.context)

        // Set up the intent filter that will receive events from our main activity
        // 기본 활동에서 이벤트를 수신할 의도 필터 설정
        val filter = IntentFilter().apply { addAction(KEY_EVENT_ACTION) }
        broadcastManager.registerReceiver(volumeDownReceiver, filter)

        // Every time the orientation of device changes, update rotation for use cases
        // 장치 방향이 변경될 때마다 사용 사례에 대한 회전 업데이트
        displayManager.registerDisplayListener(displayListener, null)

        //Initialize WindowManager to retrieve display metrics
        // 디스플레이 메트릭을 검색하도록 윈도우즈 매니저 초기화
        windowManager = WindowManager(view.context)

        // Determine the output directory
        // 출력 디렉터리 결정
        outputDirectory = MainActivity.getOutputDirectory(requireContext())

        // Wait for the views to be properly laid out
        //뷰가 올바르게 배치될 때까지 기다립니다.
        viewFinder.post {

            // Keep track of the display in which this view is attached
            // 이 보기가 연결된 디스플레이를 추적합니다.
            displayId = viewFinder.display.displayId

            // Build UI controls
            // 빌드 UI 컨트롤
            updateCameraUi()

            // Set up the camera and its use cases
            // 카메라 및 카메라 사용 사례 설정
            setUpCamera()
        }
    }

    /**
     * Inflate camera controls and update the UI manually upon config changes to avoid removing
     * and re-adding the view finder from the view hierarchy; this provides a seamless rotation
     * transition on devices that support it.
     * 카메라 컨트롤을 부풀리고 구성 변경 시 UI를 수동으로 업데이트하여 뷰 파인더를 뷰 계층에서 제거 및 다시 추가하지 않도록 합니다.
     * 이렇게 하면 뷰 파인더를 지원하는 장치에서 원활하게 회전할 수 있습니다.
     *
     * NOTE: The flag is supported starting in Android 8 but there still is a small flash on the
     * screen for devices that run Android 9 or below.
     * 참고: 이 플래그는 Android 8에서 지원되지만, Android 9 이하를
     * 실행하는 장치에 대해서는 화면에 작은 플래시가 있습니다.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        // Rebind the camera with the updated display metrics
        // 업데이트된 디스플레이 메트릭으로 카메라 다시 바인딩
        bindCameraUseCases()

        // Enable or disable switching between cameras
        // 카메라 간 전환 활성화 또는 비활성화
        updateCameraSwitchButton()
    }

    /** Initialize CameraX, and prepare to bind the camera use cases
     *  이니셜 라이즈 CameraX은 카메라 활용 사례를 묶기 위해서 준비합니다.*/
    private fun setUpCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener(Runnable {

            // CameraProvider 카메라 공급자
            cameraProvider = cameraProviderFuture.get()

            // Select lensFacing depending on the available cameras
            // 사용 가능한 카메라에 따라 렌즈대면 선택
            lensFacing = when {
                hasBackCamera() -> CameraSelector.LENS_FACING_BACK
                hasFrontCamera() -> CameraSelector.LENS_FACING_FRONT
                else -> throw IllegalStateException("Back and front camera are unavailable")
            }

            // Enable or disable switching between cameras
            //카메라 간 전환 활성화 또는 비활성화
            updateCameraSwitchButton()

            // Build and bind the camera use cases
            // 카메라 사용 사례 작성 및 바인딩
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    /** Declare and bind preview, capture and analysis use cases
     * 미리 보기, 캡처 및 분석 사용 사례 선언 및 바인딩 */
    private fun bindCameraUseCases() {

        // Get screen metrics used to setup camera for full screen resolution
        // 전체 화면 해상도를 위해 카메라를 설정하는 데 사용되는 화면 메트릭 가져오기
        val metrics = windowManager.getCurrentWindowMetrics().bounds
        Log.d(TAG, "Screen metrics: ${metrics.width()} x ${metrics.height()}")

        val screenAspectRatio = aspectRatio(metrics.width(), metrics.height())
        Log.d(TAG, "Preview aspect ratio: $screenAspectRatio")

        val rotation = viewFinder.display.rotation

        // CameraProvider 카메라 공급자
        val cameraProvider = cameraProvider
                ?: throw IllegalStateException("Camera initialization failed.")

        // CameraSelector 카메라 공급자
        val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        // Preview 미리보기
        preview = Preview.Builder()
                // We request aspect ratio but no resolution
                // 가로 세로 비율을 요청하지만 해상도가 없습니다.
                .setTargetAspectRatio(screenAspectRatio)
                // Set initial target rotation 초기 목표 회전 설정
                .setTargetRotation(rotation)
                .build()

        // ImageCapture 이미지 캡처
        imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                // We request aspect ratio but no resolution to match preview config, but letting
                // 가로 세로 비율을 요청하지만 미리 보기 구성과 일치하는 해상도는 없지만 허용
                // CameraX optimize for whatever specific resolution best fits our use cases
                // CameraX는 당사의 사용 사례에 가장 적합한 특정 해상도를 최적화합니다.
                .setTargetAspectRatio(screenAspectRatio)
                // Set initial target rotation, we will have to call this again if rotation changes
                // during the lifecycle of this use case
                //초기 목표 회전을 설정합니다. 이 사용 사례의 라이프사이클 동안 회전이 변경되면 다시 호출해야 합니다.
                .setTargetRotation(rotation)
                .build()

        // ImageAnalysis 이미지 분석
        imageAnalyzer = ImageAnalysis.Builder()
                // We request aspect ratio but no resolution
                // 가로 세로 비율을 요청하지만 해상도가 없습니다.
                .setTargetAspectRatio(screenAspectRatio)
                // Set initial target rotation, we will have to call this again if rotation changes
                // during the lifecycle of this use case
                // 초기 목표 회전을 설정합니다. 이 사용 사례의 라이프사이클 동안 회전이 변경되면 다시 호출해야 합니다.
                .setTargetRotation(rotation)
                .build()
                // The analyzer can then be assigned to the instance
                // 그런 다음 분석기를 인스턴스에 할당할 수 있습니다.
                .also {
                    it.setAnalyzer(cameraExecutor, LuminosityAnalyzer { luma ->
                        // Values returned from our analyzer are passed to the attached listener
                        // 분석기에서 반환된 값은 첨부된 수신기로 전달됩니다.
                        // We log image analysis results here - you should do something useful
                        // 이미지 분석 결과를 여기에 기록합니다. 유용한 작업을 수행해야 합니다.
                        // instead!
//                        Log.d(TAG, "Average luminosity: $luma")
                    })
                }

        // Must unbind the use-cases before rebinding them
        // 다시 바인딩하기 전에 사용 사례의 바인딩을 해제해야 합니다.
        cameraProvider.unbindAll()

        try {
            // A variable number of use-cases can be passed here -
            // camera provides access to CameraControl & CameraInfo
            // 카메라 컨트롤 & 카메라 정보에 액세스할 수 있는 다양한 사용 사례를 여기에서 전달할 수 있습니다.
            camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture, imageAnalyzer)

            // Attach the viewfinder's surface provider to preview use case
            // 사용 사례를 미리 볼 수 있도록 뷰파인더의 표면 공급자를 첨부합니다.
            preview?.setSurfaceProvider(viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }


    /**
     *  [androidx.camera.core.ImageAnalysisConfig] requires enum value of
     *  [androidx.camera.core.AspectRatio]. Currently it has values of 4:3 & 16:9.
     *
     *  Detecting the most suitable ratio for dimensions provided in @params by counting absolute
     *  of preview ratio to one of the provided values.
     *
     *  @param width - preview width
     *  @param height - preview height
     *  @return suitable aspect ratio
     */

    // 비율설정
    private fun aspectRatio(width: Int, height: Int): Int {
        val previewRatio = max(width, height).toDouble() / min(width, height)
        if (abs(previewRatio - RATIO_1_1_VALUE) <= abs(previewRatio - RATIO_1_1_VALUE)) {
            return RATIO_4_3
        }
        return RATIO_4_3
    }

    /** Method used to re-draw the camera UI controls, called every time configuration changes. */
    //구성이 변경될 때마다 호출되는 카메라 UI 컨트롤을 다시 그리는 데 사용되는 방법입니다.
    @RequiresApi(Build.VERSION_CODES.M)
    private fun updateCameraUi() {

        // Remove previous UI if any
        // 이전 UI(있는 경우) 제거
        container.findViewById<ConstraintLayout>(R.id.camera_ui_container)?.let {
            container.removeView(it)
        }

        // Inflate a new view containing all UI for controlling the camera
        // 카메라를 제어하기 위한 모든 UI가 포함된 새 보기를 가압합니다.
        val controls = View.inflate(requireContext(), R.layout.camera_ui_container, container)

        // In the background, load latest photo taken (if any) for gallery thumbnail
        // 배경에서 갤러리 축소판 그림으로 찍은 최신 사진(있는 경우)을 로드합니다.
        lifecycleScope.launch(Dispatchers.IO) {
            outputDirectory.listFiles { file ->
                EXTENSION_WHITELIST.contains(file.extension.toUpperCase(Locale.ROOT))
            }?.maxOrNull()?.let {
                setGalleryThumbnail(Uri.fromFile(it))
            }
        }
        //격자
        controls.findViewById<Button>(R.id.btn_grid).setOnClickListener{
            grid.visibility=if (grid.visibility==View.INVISIBLE){
                View.VISIBLE
            }else{
                View.INVISIBLE
            }
        }
        //카테고리
        controls.findViewById<Button>(R.id.btn_category).setOnClickListener{
            btn_t1.visibility=View.GONE
            btn_t2.visibility=View.GONE
            btn_h.visibility=View.GONE
            btn_v1.visibility=View.GONE
            btn_v2.visibility=View.GONE
            btn_p1.visibility=View.GONE
            btn_p2.visibility=View.GONE
            btn_p3.visibility=View.GONE
            btn_food1.visibility=View.GONE
            btn_food2.visibility=View.GONE

            gudo_h.visibility=View.INVISIBLE
            gudo_v.visibility=View.INVISIBLE
            gudo_v1.visibility=View.INVISIBLE
            gudo_v2.visibility=View.INVISIBLE
            gudo_p1.visibility=View.INVISIBLE
            gudo_p2.visibility=View.INVISIBLE
            person2.visibility=View.INVISIBLE
            gudo_t1.visibility=View.INVISIBLE
            gudo_t2.visibility=View.INVISIBLE
            gudo_t3.visibility=View.INVISIBLE
            gudo_t4.visibility=View.INVISIBLE
            food1.visibility=View.INVISIBLE
            food2.visibility=View.INVISIBLE

            if(btn_category.getText().toString().equals("beach")){
                Log.d(TAG,"이것은 beach")
                btn_h.visibility=View.VISIBLE
            }
            else if(btn_category.getText().toString().equals("building")){
                Log.d(TAG,"이것은 building")
                btn_v1.visibility=View.VISIBLE
                btn_v2.visibility=View.VISIBLE
            }
            else if(btn_category.getText().toString().equals("person")){
                Log.d(TAG,"이것은 person")
                btn_p1.visibility=View.VISIBLE
                btn_p2.visibility=View.VISIBLE
                btn_p3.visibility=View.VISIBLE
            }
            else if(btn_category.getText().toString().equals("road")){
                Log.d(TAG,"이것은 road")
                btn_t1.visibility=View.VISIBLE
                btn_t2.visibility=View.VISIBLE
            }
            else if(btn_category.getText().toString().equals("food")){
                Log.d(TAG,"이것은 food")
                btn_food1.visibility=View.VISIBLE
                btn_food2.visibility=View.VISIBLE
            }



//            gudo_h.visibility=if (gudo_h.visibility==View.INVISIBLE){
//                View.VISIBLE
//            }else{
//                View.INVISIBLE
//            }

        }
        //수평구도
        controls.findViewById<Button>(R.id.btn_h).setOnClickListener{
            gudo_h.visibility=if (gudo_h.visibility==View.INVISIBLE){
                View.VISIBLE
            }else{
                View.INVISIBLE
            }
        }
        //수직구도
        controls.findViewById<Button>(R.id.btn_v1).setOnClickListener{
            gudo_v.visibility=if (gudo_v.visibility==View.INVISIBLE){
                View.VISIBLE
            }else{
                View.INVISIBLE
            }
            gudo_v1.visibility=View.INVISIBLE
            gudo_v2.visibility=View.INVISIBLE

        }
        //수직구도2
        controls.findViewById<Button>(R.id.btn_v2).setOnClickListener{
            if(gudo_v1.visibility==View.INVISIBLE){
                gudo_v1.visibility=View.VISIBLE
                gudo_v2.visibility=View.VISIBLE
            }else{
                gudo_v1.visibility=View.INVISIBLE
                gudo_v2.visibility=View.INVISIBLE
            }
            gudo_v.visibility=View.INVISIBLE
        }
        //삼각구도
        controls.findViewById<Button>(R.id.btn_t1).setOnClickListener{
            if(gudo_t1.visibility==View.INVISIBLE){
                gudo_t1.visibility=View.VISIBLE
                gudo_t2.visibility=View.VISIBLE
            }else{
                gudo_t1.visibility=View.INVISIBLE
                gudo_t2.visibility=View.INVISIBLE
            }
            gudo_t3.visibility=View.INVISIBLE
            gudo_t4.visibility=View.INVISIBLE
        }
        //삼각구도
        controls.findViewById<Button>(R.id.btn_t2).setOnClickListener{
            if(gudo_t3.visibility==View.INVISIBLE){
                gudo_t3.visibility=View.VISIBLE
                gudo_t4.visibility=View.VISIBLE
            }else{
                gudo_t3.visibility=View.INVISIBLE
                gudo_t4.visibility=View.INVISIBLE
            }
            gudo_t1.visibility=View.INVISIBLE
            gudo_t2.visibility=View.INVISIBLE
        }

        //사람구도
        controls.findViewById<Button>(R.id.btn_p1).setOnClickListener{
            gudo_p1.visibility=if (gudo_p1.visibility==View.INVISIBLE){
                View.VISIBLE
            }else{
                View.INVISIBLE
            }
            gudo_p2.visibility=View.INVISIBLE
            person2.visibility=View.INVISIBLE
        }
        //사람구도
        controls.findViewById<Button>(R.id.btn_p2).setOnClickListener{
            gudo_p2.visibility=if (gudo_p2.visibility==View.INVISIBLE){
                View.VISIBLE
            }else{
                View.INVISIBLE
            }
            gudo_p1.visibility=View.INVISIBLE
            person2.visibility=View.INVISIBLE

        }
        //사람구도(상반신)
        controls.findViewById<Button>(R.id.btn_p3).setOnClickListener{
            person2.visibility=if (person2.visibility==View.INVISIBLE){
                View.VISIBLE
            }else{
                View.INVISIBLE
            }
            gudo_p1.visibility=View.INVISIBLE
            gudo_p2.visibility=View.INVISIBLE


        }
        //음식1
        controls.findViewById<Button>(R.id.btn_food1).setOnClickListener{
            food1.visibility=if (food1.visibility==View.INVISIBLE){
                View.VISIBLE
            }else{
                View.INVISIBLE
            }
            food2.visibility=View.INVISIBLE
        }
        //음식2
        controls.findViewById<Button>(R.id.btn_food2).setOnClickListener{
            food2.visibility=if (food2.visibility==View.INVISIBLE){
                View.VISIBLE
            }else{
                View.INVISIBLE
            }
            food1.visibility=View.INVISIBLE
        }
        //촬영장소인식 버튼을 눌렀을 때
        controls.findViewById<Button>(R.id.btn_c).setOnClickListener {
            btn_t1.visibility=View.GONE
            btn_t2.visibility=View.GONE
            btn_h.visibility=View.GONE
            btn_v1.visibility=View.GONE
            btn_v2.visibility=View.GONE
            btn_p1.visibility=View.GONE
            btn_p2.visibility=View.GONE
            btn_p3.visibility=View.GONE
            btn_food1.visibility=View.GONE
            btn_food2.visibility=View.GONE

            gudo_h.visibility=View.INVISIBLE
            gudo_v.visibility=View.INVISIBLE
            gudo_v1.visibility=View.INVISIBLE
            gudo_v2.visibility=View.INVISIBLE
            gudo_p1.visibility=View.INVISIBLE
            gudo_p2.visibility=View.INVISIBLE
            person2.visibility=View.INVISIBLE
            gudo_t1.visibility=View.INVISIBLE
            gudo_t2.visibility=View.INVISIBLE
            gudo_t3.visibility=View.INVISIBLE
            gudo_t4.visibility=View.INVISIBLE
            food1.visibility=View.INVISIBLE
            food2.visibility=View.INVISIBLE

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val baos = ByteArrayOutputStream()
                selected_bitmap.compress(Bitmap.CompressFormat.JPEG, 100, baos)
                val imageBytes = baos.toByteArray()
                val imageString = Base64.encodeToString(imageBytes, Base64.DEFAULT)
                send(imageString)
//                Log.d(TAG, "send() succeeded: $resultBody")
                // Display flash animation to indicate that photo was captured
                //사진이 캡처되었음을 나타내는 플래시 애니메이션 표시
                container.postDelayed({
                    container.foreground = ColorDrawable(Color.WHITE)
                    container.postDelayed(
                            { container.foreground = null }, ANIMATION_FAST_MILLIS)
                }, ANIMATION_SLOW_MILLIS)
            }


        }

        // Listener for button used to capture photo
        // 사진 캡처에 사용되는 버튼의 수신기
        controls.findViewById<ImageButton>(R.id.camera_capture_button).setOnClickListener {
            // Get a stable reference of the modifiable image capture use case
            // 수정 가능한 이미지 캡처 사용 사례에 대한 안정적인 참조 가져오기
            imageCapture?.let { imageCapture ->

                // Create output file to hold the image
                // 이미지를 저장할 출력 파일 생성
                val photoFile = createFile(outputDirectory, FILENAME, PHOTO_EXTENSION)

                // Setup image capture metadata
                // 이미지 캡처 메타데이터 설정
                val metadata = Metadata().apply {
                    // Mirror image when using the front camera
                    // 프론트 카메라 사용 시 이미지를 미러링합니다.
                    isReversedHorizontal = lensFacing == CameraSelector.LENS_FACING_FRONT
                }

                // Create output options object which contains file + metadata
                // 파일 + 메타데이터를 포함하는 출력 옵션 개체 생성
                val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile)
                        .setMetadata(metadata)
                        .build()

                // Setup image capture listener which is triggered after photo has been taken
                // 사진을 찍은 후 트리거되는 이미지 캡처 수신기 설정
                imageCapture.takePicture(
                        outputOptions, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
                    override fun onError(exc: ImageCaptureException) {
                        Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                    }

                    // 사진을 찍고 어떻게 저장할 지에 대한 구현부
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        savedUri = output.savedUri ?: Uri.fromFile(photoFile)
                        Log.d(TAG, "Photo capture succeeded: $savedUri")

                        selected_bitmap = MediaStore.Images.Media.getBitmap(
                                context?.contentResolver,
                                savedUri
                        )
//                        fun onImageSaved(bitmap: Bitmap, photoFile: File) {
//                            val photoFile = createFile(outputDirectory, FILENAME, PHOTO_EXTENSION)
//                            val OutputStream = FileOutputStream(photoFile)
//                            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, OutputStream)
//                            Log.d(TAG, "Photo capture succeeded: $savedUri")

                        selected_bitmap = MediaStore.Images.Media.getBitmap(
                                context?.contentResolver,
                                savedUri
                        )


                        val imageString = bitmapToString(selected_bitmap)
                        Log.d("IMAGE TO STRING!!", "$imageString")
//                            HttpCheck(myUrl, imageString)
//                            Log.d("run http check!!", myUrl)
//                        val request: StringRequest = object : StringRequest(Request.Method.POST, myUrl, Response.Listener { s ->
//                            Log.d("response", s.toString())
//
////                try{
//                            val jsonObject = JSONObject(s)
//                                //jsonObject.put("name", imageString)
//                            Log.d("response", jsonObject.get("url").toString())
//                                //Glide.with(this@CameraFragment).load(jsonObject.get("url").toString()).into(viewFinder)
//
////                                upload_img_button.visibility = GONE
////                                describeText.text = jsonObject.get("result").toString()
////                                describeText.visibility = VISIBLE
//
////                } catch (ex: java.lang.Exception){
////                    imageView.setImageBitmap(null)
////                }
//                        }, Response.ErrorListener { volleyError ->
//                            Toast.makeText(context, "Some error occurred -> $volleyError", Toast.LENGTH_LONG).show()
//
//                            }) {
//                                //adding parameters to send
//                                @Throws(AuthFailureError::class)
//                                override fun getParams(): Map<String, String> {
//                                    val parameters: MutableMap<String, String> = HashMap()
//                                    parameters["file"] = imageString
//                                    return parameters
//                                }
//                            }
//
//                            request.retryPolicy = DefaultRetryPolicy(
//                                5000,
//                                10,
//                                DefaultRetryPolicy.DEFAULT_BACKOFF_MULT
//                            )
//
//                            val rQueue = Volley.newRequestQueue(context)
//                            rQueue.add(request)

                        // Implicit broadcasts will be ignored for devices running API level >= 24
                        // API 수준 >= 24를 실행하는 장치에 대해서는 암시적 브로드캐스트가 무시됩니다.
                        // so if you only target API level 24+ you can remove this statement
                        //따라서 만약 당신이 24+ API 레벨만을 대상으로 한다면 당신은 이 문을 제거할 수 있다.
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                            requireActivity().sendBroadcast(
                                    Intent(android.hardware.Camera.ACTION_NEW_PICTURE, savedUri)
                            )
                        }
                        // If the folder selected is an external media directory, this is
                        // unnecessary but otherwise other apps will not be able to access our
                        // images unless we scan them using [MediaScannerConnection]
                        //선택한 폴더가 외부 미디어 디렉토리인 경우 불필요하지만 그렇지 않으면 [MediaScannerConnection]을 사용하여
                        // 이미지를 스캔하지 않으면 다른 앱에서 해당 이미지에 액세스할 수 없습니다.
                        val mimeType = MimeTypeMap.getSingleton()
                                .getMimeTypeFromExtension(savedUri.toFile().extension)
                        MediaScannerConnection.scanFile(
                                context,
                                arrayOf(savedUri.toFile().absolutePath),
                                arrayOf(mimeType)
                        ) { _, uri ->
                            Log.d(TAG, "Image capture scanned into media store: $uri")
                        }

                    }

//                        //서버로 이미지 보내기
//                        private fun HttpCheck(myUrl: String, imageString: String) {
//                            Log.d("httpCheck", "요청 시작")
//                            val requestBody: RequestBody = FormBody.Builder()
//                                .add("file", imageString)
//                                .build()
//                            Log.d("httpcheck", "requestbody")
//
//                            val request = okhttp3.Request.Builder()
//                                .url(myUrl)
//                                .post(requestBody)
//                                .build()
//
//                            val client = OkHttpClient.Builder()
//                                .connectTimeout(20, TimeUnit.SECONDS)
//                                .readTimeout(20, TimeUnit.SECONDS)
//                                .writeTimeout(20, TimeUnit.SECONDS)
//                                .build()

//                            val client= OkHttpClient()
//                                .connectTimeout(100, TimeUnit.MINUTES)
//                                .writeTimeout(100, TimeUnit.MINUTES) //write timeout
//                                .readTimeout(100, TimeUnit.MINUTES) //read timeout
//                                .build()
//                            Log.d("전송 주소 ", myUrl)
                    // 요청 전송
//                            client.newCall(request).enqueue(object : Callback {
//                                override fun onResponse(call: Call, response: okhttp3.Response) {
//                                    val jsonObject = response.body().toString()
//                                    Log.d("object", jsonObject)
//                                    Log.d("요청", "요청 완료")
//
//                                    if (response.equals("beach")){
//                                        Toast.makeText(context, "beach", Toast.LENGTH_LONG).show()
//                                        Log.d("반응","beach뜬다!!")
//                                    }
//
//                                }
//
//                                override fun onFailure(call: Call, e: IOException) {
//                                    Log.d(TAG, "요청 : $e")
//                                }
//
//                            })

//                        }

                    //비트맵을 스트링으로 변환
                    private fun bitmapToString(bitmap: Bitmap): String {

                        val byteArrayOutputStream = ByteArrayOutputStream()

                        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, byteArrayOutputStream)

                        val byteArray = byteArrayOutputStream.toByteArray()

                        return Base64.encodeToString(byteArray, Base64.DEFAULT)
                    }

                })


                // We can only change the foreground Drawable using API level 23+ API
                // API 레벨 23+ API를 사용해서만 foregroundDrawable을 변경할 수 있습니다.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

                    // Display flash animation to indicate that photo was captured
                    //사진이 캡처되었음을 나타내는 플래시 애니메이션 표시
                    container.postDelayed({
                        container.foreground = ColorDrawable(Color.WHITE)
                        container.postDelayed(
                                { container.foreground = null }, ANIMATION_FAST_MILLIS)
                    }, ANIMATION_SLOW_MILLIS)
                }

            }
        }
        // Setup for button used to switch cameras
        //카메라 전환에 사용되는 버튼 설정
        controls.findViewById<ImageButton>(R.id.camera_switch_button).let {

            // Disable the button until the camera is set up
            // 카메라가 설정될 때까지 버튼을 비활성화합니다.
            it.isEnabled = false

            // Listener for button used to switch cameras. Only called if the button is enabled
            // 카메라 전환에 사용되는 버튼의 수신기입니다. 버튼이 활성화되어 있는 경우에만 호출됩니다.
            it.setOnClickListener {
                lensFacing = if (CameraSelector.LENS_FACING_FRONT == lensFacing) {
                    CameraSelector.LENS_FACING_BACK
                } else {
                    CameraSelector.LENS_FACING_FRONT
                }
                // Re-bind use cases to update selected camera
                // 사용 사례를 다시 바인딩하여 선택한 카메라 업데이트
                bindCameraUseCases()
            }
        }

        // Listener for button used to view the most recent photo
        // 가장 최근 사진을 보는 데 사용되는 버튼의 수신기
        controls.findViewById<ImageButton>(R.id.photo_view_button).setOnClickListener {
            // Only navigate when the gallery has photos
            // 갤러리에 사진이 있을 때만 탐색
            if (true == outputDirectory.listFiles()?.isNotEmpty()) {
                Navigation.findNavController(
                        requireActivity(), R.id.fragment_container
                ).navigate(CameraFragmentDirections
                        .actionCameraToGallery(outputDirectory.absolutePath))
            }
        }
    }


    //서버로 이미지전송
    private fun send(imageString: String) {
        val request = okhttp3.Request
                .Builder()
                .url(myUrl)
                .build()
        val client = OkHttpClient()
        client.newCall(request).enqueue(object : Callback {

            override fun onResponse(call: Call, response: Response) {
//                Log.d(ContentValues.TAG, "body()?.string() : ${response.body()?.string()}")

                tv = container.findViewById<Button>(R.id.btn_category)
                tv.setText(response.body()?.string())

//                af1 = controls.findViewById(R.id.texViewA)
//                af1.setText(response.body().toString())
            }

            override fun onFailure(call: Call, e: IOException) {
                Log.d("요청","요청 실패")
                Log.e("Failure", Log.getStackTraceString(e))
            }


        })
    }

//    private fun sendGet(){
//        val url = URL(myUrl)
//        with(url.openConnection() as HttpURLConnection){
//            requestMethod = "GET"
//
//            println("\nSent 'GET' request to URL : $url; Response Code : $responseCode")
//
//            inputStream.bufferedReader().use{
//                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
//                    it.lines().forEach{ line ->
//                        println(line)
//                    }
//                }
//            }
//        }
//    }

//    private fun sendGet(){
//        val client = OkHttpClient()
//
//        val request = okhttp3.Request.Builder()
//            .url(myUrl)
//            .get()
//            .build()
//        val response = client.newCall(request).execute()
//
//        val responseBody = response.body().toString()
//
//        println("Respone Body : " + responseBody)
//
//    }

    /** Enabled or disabled a button to switch cameras depending on the available cameras
     * 사용 가능한 카메라에 따라 카메라를 전환하는 버튼 활성화 또는 비활성화 */
    private fun updateCameraSwitchButton() {
        val switchCamerasButton = container.findViewById<ImageButton>(R.id.camera_switch_button)
        try {
            switchCamerasButton.isEnabled = hasBackCamera() && hasFrontCamera()
        } catch (exception: CameraInfoUnavailableException) {
            switchCamerasButton.isEnabled = false
        }
    }

    /**  Returns true if the device has an available back camera. False otherwise
     * 장치에 사용 가능한 백 카메라가 있으면 true를 반환합니다. 그렇지 않으면 거짓*/
    private fun hasBackCamera(): Boolean {
        return cameraProvider?.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) ?: false
    }

    /**  Returns true if the device has an available front camera. False otherwise
     * 디바이스에 사용 가능한 전면 카메라가 있는 경우 true를 반환합니다. 그렇지 않으면 거짓*/
    private fun hasFrontCamera(): Boolean {
        return cameraProvider?.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ?: false
    }

    /**
     * Our custom image analysis class. 사용자 지정 이미지 분석 클래스입니다.
     *
     * <p>All we need to do is override the function `analyze` with our desired operations. Here,
     * we compute the average luminosity of the image by looking at the Y plane of the YUV frame.
     * 우리가 해야 할 일은 우리가 원하는 연산으로 '분석' 기능을 재정의하는 것이다.
     * 여기서는 YUV 프레임의 Y 평면을 보고 이미지의 평균 광도를 계산한다.
     */
    private class LuminosityAnalyzer(listener: LumaListener? = null) : ImageAnalysis.Analyzer {
        private val frameRateWindow = 8
        private val frameTimestamps = ArrayDeque<Long>(5)
        private val listeners = ArrayList<LumaListener>().apply { listener?.let { add(it) } }
        private var lastAnalyzedTimestamp = 0L
        var framesPerSecond: Double = -1.0
            private set

        /**
         * Used to add listeners that will be called with each luma computed
         * 각 루마가 계산된 상태에서 호출될 수신기를 추가하는 데 사용됩니다.
         */
        fun onFrameAnalyzed(listener: LumaListener) = listeners.add(listener)

        /**
         * Helper extension function used to extract a byte array from an image plane buffer
         * 이미지 평면 버퍼에서 바이트 배열을 추출하는 데 사용되는 도우미 확장 함수
         */
        private fun ByteBuffer.toByteArray(): ByteArray {
            rewind()    // Rewind the buffer to zero  버퍼를 0으로 되감기
            val data = ByteArray(remaining())
            get(data)   // Copy the buffer into a byte array 버퍼를 바이트 배열로 복사
            return data // Return the byte array 바이트 배열을 반환합니다.
        }

        /**
         * Analyzes an image to produce a result.
         *
         * <p>The caller is responsible for ensuring this analysis method can be executed quickly
         * enough to prevent stalls in the image acquisition pipeline. Otherwise, newly available
         * images will not be acquired and analyzed.
         *
         * <p>The image passed to this method becomes invalid after this method returns. The caller
         * should not store external references to this image, as these references will become
         * invalid.
         *
         * @param image image being analyzed VERY IMPORTANT: Analyzer method implementation must
         * call image.close() on received images when finished using them. Otherwise, new images
         * may not be received or the camera may stall, depending on back pressure setting.
         *
         */
        override fun analyze(image: ImageProxy) {
            // If there are no listeners attached, we don't need to perform analysis
            // 청취자가 첨부되어 있지 않으면 분석을 수행할 필요가 없습니다.
            if (listeners.isEmpty()) {
                image.close()
                return
            }

            // Keep track of frames analyzed  분석된 프레임 추적
            val currentTime = System.currentTimeMillis()
            frameTimestamps.push(currentTime)

            // Compute the FPS using a moving average  이동 평균을 사용하여 FPS 계산
            while (frameTimestamps.size >= frameRateWindow) frameTimestamps.removeLast()
            val timestampFirst = frameTimestamps.peekFirst() ?: currentTime
            val timestampLast = frameTimestamps.peekLast() ?: currentTime
            framesPerSecond = 1.0 / ((timestampFirst - timestampLast) /
                    frameTimestamps.size.coerceAtLeast(1).toDouble()) * 1000.0

            // Analysis could take an arbitrarily long amount of time
            // 분석에 임의로 긴 시간이 소요될 수 있음
            // Since we are running in a different thread, it won't stall other use cases
            // 다른 스레드에서 실행 중이므로 다른 사용 사례를 지연시키지 않습니다.

            lastAnalyzedTimestamp = frameTimestamps.first

            // Since format in ImageAnalysis is YUV, image.planes[0] contains the luminance plane
            // 이미지 분석의 형식은 YUV이므로 이미지.평면[0]은 휘도면을 포함한다.
            val buffer = image.planes[0].buffer

            // Extract image data from callback object
            // 콜백 개체에서 이미지 데이터 추출
            val data = buffer.toByteArray()

            // Convert the data into an array of pixel values ranging 0-255
            // 데이터를 0-255 범위의 픽셀 값의 배열로 변환
            val pixels = data.map { it.toInt() and 0xFF }

            // Compute average luminance for the image 이미지의 평균 휘도 계산
            val luma = pixels.average()

            // Call all listeners with new value 새 값으로 모든 수신기를 호출합니다.
            listeners.forEach { it(luma) }

            image.close()
        }
    }

    companion object {

        private const val TAG = "CameraXBasic"
        private const val FILENAME = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val PHOTO_EXTENSION = ".jpg"
        private const val RATIO_4_3_VALUE = 4.0 / 3.0
        private const val RATIO_1_1_VALUE = 1.0 / 1.0
        private const val RATIO_16_9_VALUE = 16.0 / 9.0


        /** Helper function used to create a timestamped file
         * 타임스탬프 파일을 만드는 데 사용되는 도우미 함수 */
        private fun createFile(baseFolder: File, format: String, extension: String) =
                File(baseFolder, SimpleDateFormat(format, Locale.US)
                        .format(System.currentTimeMillis()) + extension)
    }
}