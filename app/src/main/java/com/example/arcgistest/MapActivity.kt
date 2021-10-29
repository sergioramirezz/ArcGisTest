package com.example.arcgistest

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Color
import android.os.Bundle
import android.os.Environment
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.esri.arcgisruntime.ArcGISRuntimeEnvironment
import com.esri.arcgisruntime.data.TransportationNetworkDataset
import com.esri.arcgisruntime.geometry.Point
import com.esri.arcgisruntime.geometry.SpatialReferences
import com.esri.arcgisruntime.loadable.LoadStatus
import com.esri.arcgisruntime.location.LocationDataSource
import com.esri.arcgisruntime.mapping.ArcGISMap
import com.esri.arcgisruntime.mapping.BasemapStyle
import com.esri.arcgisruntime.mapping.MobileMapPackage
import com.esri.arcgisruntime.mapping.Viewpoint
import com.esri.arcgisruntime.mapping.view.Graphic
import com.esri.arcgisruntime.mapping.view.GraphicsOverlay
import com.esri.arcgisruntime.mapping.view.LocationDisplay
import com.esri.arcgisruntime.navigation.DestinationStatus
import com.esri.arcgisruntime.navigation.RouteTracker
import com.esri.arcgisruntime.symbology.SimpleLineSymbol
import com.esri.arcgisruntime.tasks.networkanalysis.RouteParameters
import com.esri.arcgisruntime.tasks.networkanalysis.RouteResult
import com.esri.arcgisruntime.tasks.networkanalysis.RouteTask
import com.esri.arcgisruntime.tasks.networkanalysis.Stop
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.layout_navigation_controls.*
import java.io.File
import java.util.concurrent.ExecutionException

class MapActivity : AppCompatActivity() {

    private lateinit var locationDisplay: LocationDisplay

    // Voice instructions
    private var textToSpeech: TextToSpeech? = null
    private var isTextToSpeechInitialized = false

    private var location: LocationDataSource.Location? = null
    private var transportationNetworkDataset: TransportationNetworkDataset? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        requestPermissions()
        initLocation()
    }

    private fun getMapPath(): String? {
        val dir = File(getMapDir())
        val filteredFiles = dir.listFiles { file ->
            file.absolutePath.contains(".mmpk", ignoreCase = true)
        }

        return if (filteredFiles != null && filteredFiles.isNotEmpty()) filteredFiles[0].absolutePath else null
    }

    private fun getMapDir(): String {
        var path = ""

        val absolutPath = Environment.getExternalStorageDirectory().absolutePath
        val fileList = File("/storage/").listFiles()
        for (file in fileList) {
            if (file.absolutePath.equals(absolutPath, ignoreCase = true).not()
                && file.isDirectory && file.canRead()
            ) {
                path = file.absolutePath
            }
        }

        return path
    }

    private fun setupMap() {
        ArcGISRuntimeEnvironment.setApiKey(BuildConfig.API_KEY)
        mapView.map = ArcGISMap(BasemapStyle.ARCGIS_STREETS)
        mapView.graphicsOverlays.add(GraphicsOverlay())
    }

    private fun setupTextToSpeech() {
        val onInitListener = TextToSpeech.OnInitListener { status ->
            if (status != TextToSpeech.ERROR) {
                textToSpeech?.language = Resources.getSystem().configuration.locale
                isTextToSpeechInitialized = true
            }
        }
        textToSpeech = TextToSpeech(this, onInitListener)
    }

    private fun setupRoute() {
        val routeTask = RouteTask(this, transportationNetworkDataset)
        val routeParametersFuture = routeTask.createDefaultParametersAsync()

        routeParametersFuture.addDoneListener {
            if (routeParametersFuture.isDone.not()) return@addDoneListener
            val routeParameters = routeParametersFuture.get()
            setupStops(routeParameters)
            solveRoute(routeTask, routeParameters)
        }
    }

    private fun setupStops(routeParameters: RouteParameters) {
        routeParameters.setStops(
            listOf(
                Stop(Point(-81.257815, 33.979253, SpatialReferences.getWgs84())),
                Stop(Point(-81.252928, 33.978554, SpatialReferences.getWgs84())),
                Stop(Point(-81.244195, 33.978477, SpatialReferences.getWgs84()))
            )
            /*listOf(
                Stop(Point(-117.160386, 32.706608, SpatialReferences.getWgs84())),
                Stop(Point(-117.173034, 32.712327, SpatialReferences.getWgs84())),
                Stop(Point(-117.147230, 32.730467, SpatialReferences.getWgs84()))
            )*/
        )
        routeParameters.isReturnDirections = true
        routeParameters.isReturnStops = true
        routeParameters.isReturnRoutes = true
    }

    private fun solveRoute(routeTask: RouteTask, routeParameters: RouteParameters) {
        val routeResultFuture = routeTask.solveRouteAsync(routeParameters)
        routeResultFuture.addDoneListener {
            try {
                val routeResult = routeResultFuture.get()
                val routeGeometry = routeResult.routes[0].routeGeometry
                val routeGraphic = Graphic(
                    routeGeometry, SimpleLineSymbol(SimpleLineSymbol.Style.SOLID, Color.BLUE, 5f)
                )
                mapView.graphicsOverlays[OVERLAY_ROUTE].graphics.add(routeGraphic)
                mapView.setViewpointAsync(Viewpoint(routeGeometry.extent))
                startNavigation(routeTask, routeParameters, routeResult)
            } catch (e: Exception) {
                when (e) {
                    is InterruptedException, is ExecutionException -> {
                        val error = "Error creating the route result: " + e.message
                        Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                        Log.e("SERGIO: ERROR -> ", error)
                    }
                    else -> throw e
                }
            }
        }
    }

    private fun startNavigation(
        routeTask: RouteTask,
        routeParameters: RouteParameters,
        routeResult: RouteResult
    ) {
        mapView.graphicsOverlays[OVERLAY_ROUTE].graphics.clear()
        val routeGeometry = routeResult.routes[0].routeGeometry

        val routeAheadGraphic = Graphic(
            routeGeometry, SimpleLineSymbol(SimpleLineSymbol.Style.DASH, Color.MAGENTA, 5f)
        )

        val routeTraveledGraphic = Graphic(
            routeGeometry, SimpleLineSymbol(SimpleLineSymbol.Style.SOLID, Color.BLUE, 5f)
        )

        mapView.graphicsOverlays[OVERLAY_ROUTE].graphics.addAll(
            listOf(routeAheadGraphic, routeTraveledGraphic)
        )

        val routeTracker = RouteTracker(applicationContext, routeResult, 0, true)
        val reRoutingFuture = routeTracker.enableReroutingAsync(
            routeTask, routeParameters, RouteTracker.ReroutingStrategy.TO_NEXT_STOP, true
        )

        reRoutingFuture.addDoneListener {
            if (reRoutingFuture.isDone.not()) return@addDoneListener

            locationDisplay.addLocationChangedListener { locationChangedEvent ->
                location = locationChangedEvent.location

                routeTracker.trackLocationAsync(location)

                if (routeTracker.trackingStatus == null) return@addLocationChangedListener

                val trackingStatus = routeTracker.trackingStatus

                routeAheadGraphic.geometry = trackingStatus.routeProgress.remainingGeometry
                routeTraveledGraphic.geometry = trackingStatus.routeProgress.traversedGeometry

                if (trackingStatus.destinationStatus == DestinationStatus.REACHED) {
                    if (routeTracker.trackingStatus.remainingDestinationCount > 1) {
                        routeTracker.switchToNextDestinationAsync()
                        Toast.makeText(
                            this,
                            "Navigating to the second stop, the Fleet Science Center.",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(
                            this, "Arrived at the final destination.", Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }

            locationDisplay.startAsync()
        }

        locationDisplay.autoPanMode = LocationDisplay.AutoPanMode.NAVIGATION

        routeTracker.addNewVoiceGuidanceListener { newVoiceGuidanceEvent ->
            speakVoiceGuidance(newVoiceGuidanceEvent.voiceGuidance.text)
        }

        initLocation()
    }

    private fun speakVoiceGuidance(voiceGuidanceText: String) {
        if (isTextToSpeechInitialized && textToSpeech?.isSpeaking == false) {
            textToSpeech?.speak(voiceGuidanceText, TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    private fun requestPermissions() {
        val requestCode = 2
        val reqPermissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        )

        val permissionCheckFineLocation = ContextCompat.checkSelfPermission(
            this,
            reqPermissions[0]
        ) == PackageManager.PERMISSION_GRANTED

        val permissionCheckCoarseLocation = ContextCompat.checkSelfPermission(
            this,
            reqPermissions[1]
        ) == PackageManager.PERMISSION_GRANTED

        val permissionCheckReadStorage = ContextCompat.checkSelfPermission(
            this,
            reqPermissions[2]
        ) == PackageManager.PERMISSION_GRANTED

        val permissionCheckWriteStorage = ContextCompat.checkSelfPermission(
            this,
            reqPermissions[3]
        ) == PackageManager.PERMISSION_GRANTED

        if ((permissionCheckFineLocation && permissionCheckCoarseLocation
                    && permissionCheckReadStorage && permissionCheckWriteStorage).not()) {
            ActivityCompat.requestPermissions(this, reqPermissions, requestCode)
        } else {
            Toast.makeText(this, "Error getting location", Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            permissionsDone()
        } else {
            Toast.makeText(
                this@MapActivity, "Permission denied", Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun permissionsDone() {
        map()
        setupMap()

        setupTextToSpeech()
    }

    private fun map() {
        val mapPath = getMapPath()
        if (mapPath == null) {
            Toast.makeText(this, "Can't find the map", Toast.LENGTH_SHORT).show()
            return
        }

        loadMap(getMapPath()!!)
    }

    private fun initLocation() {
        locationDisplay = mapView.locationDisplay
        if (locationDisplay.isStarted.not()) {
            locationDisplay.startAsync()
        }
    }

    private fun loadMap(mapPath: String) {
        val mapPackageFile = File(mapPath)
        if (mapPackageFile.exists().not()) {
            Toast.makeText(this, "Local Map not found", Toast.LENGTH_SHORT).show()
            return
        }

        val mobileMapPackage = MobileMapPackage(mapPackageFile.path)
        mobileMapPackage.addDoneLoadingListener {
            with(mobileMapPackage) {
                if (loadStatus == LoadStatus.LOADED && maps.size > 0) {
                    transportationNetworkDataset = maps[0].transportationNetworks[0]
                    setupRoute()
                    initLocation()
                }
            }
        }
        mobileMapPackage.loadAsync()
    }

    override fun onPause() {
        mapView.pause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        mapView.resume()
    }

    override fun onDestroy() {
        mapView.dispose()
        super.onDestroy()
    }

    companion object {

        private const val OVERLAY_ROUTE = 0
    }
}