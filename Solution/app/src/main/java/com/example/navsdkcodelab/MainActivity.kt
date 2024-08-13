// Copyright 2024 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.example.navsdkcodelab

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
import com.google.android.libraries.navigation.NavigationApi
import com.google.android.libraries.navigation.NavigationView
import com.google.android.libraries.navigation.Navigator
import com.google.android.libraries.navigation.SimulationOptions
import com.google.android.libraries.navigation.Waypoint
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private var mNavigator: Navigator? = null
    private lateinit var navView: NavigationView
    private var arrivalListener: Navigator.ArrivalListener? = null
    private var routeChangedListener: Navigator.RouteChangedListener? = null

    var navigatorScope: InitializedNavScope? = null
    var pendingNavActions = mutableListOf<InitializedNavRunnable>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        //obtain a reference to the NavigationView
        setContentView(R.layout.activity_main)
        navView = findViewById(R.id.navigation_view)
        navView.onCreate(savedInstanceState)

        // Ensure the screen stays on during navigation.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.navigation_view)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        //Configure app permissions
        val permissions =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }

        if (permissions.any { !checkPermissionGranted(it) }) {

            if (permissions.any { shouldShowRequestPermissionRationale(it) }) {
                // Display a dialogue explaining the required permissions.
            }

            val permissionsLauncher =
                registerForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { permissionResults ->
                    if (permissionResults.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false)) {
                        onLocationPermissionGranted()
                    } else {
                        finish()
                    }
                }

            permissionsLauncher.launch(permissions)
        } else {
            android.os.Handler(Looper.getMainLooper()).postDelayed({ onLocationPermissionGranted() }, TimeUnit.SECONDS.toMillis(2))
        }
    }
    private fun checkPermissionGranted(permissionToCheck: String): Boolean =
        ContextCompat.checkSelfPermission(this, permissionToCheck) == PackageManager.PERMISSION_GRANTED

    private fun onLocationPermissionGranted() {
        initializeNavigationApi()
    }

    /** Starts the Navigation API, capturing a reference when ready. */
    @SuppressLint("MissingPermission")
    private fun initializeNavigationApi() {
        NavigationApi.getNavigator(
            this,
            object : NavigationApi.NavigatorListener {
                override fun onNavigatorReady(navigator: Navigator) {
                    val scope = InitializedNavScope(navigator)
                    navigatorScope = scope
                    pendingNavActions.forEach { block -> scope.block() }
                    pendingNavActions.clear()
                    // Disables the guidance notifications and shuts down the app and background service
                    // when the user dismisses/swipes away the app from Android's recent tasks.
                    navigator.setTaskRemovedBehavior(Navigator.TaskRemovedBehavior.QUIT_SERVICE)

                    mNavigator = navigator

                    if (BuildConfig.DEBUG) {
                        mNavigator?.simulator?.setUserLocation(MainActivity.startLocation)
                    }
                    //listen for events en route
                    registerNavigationListeners()

                    navView.getMapAsync {
                            googleMap  ->
                        googleMap.followMyLocation(GoogleMap.CameraPerspective.TILTED)
                    }

                    //navigate to a destination
                    navigateToPlace(MainActivity.TRAFALGAR_SQUARE)
                }

                override fun onError(@NavigationApi.ErrorCode errorCode: Int) {
                    when (errorCode) {
                        NavigationApi.ErrorCode.NOT_AUTHORIZED -> {
                            // Note: If this message is displayed, you may need to check that
                            // your API_KEY is specified correctly in AndroidManifest.xml
                            // and is been enabled to access the Navigation API
                            showToast(
                                "Error loading Navigation API: Your API key is " +
                                        "invalid or not authorized to use Navigation."
                            )
                        }
                        NavigationApi.ErrorCode.TERMS_NOT_ACCEPTED -> {
                            showToast(
                                "Error loading Navigation API: User did not " +
                                        "accept the Navigation Terms of Use."
                            )
                        }
                        else -> showToast("Error loading Navigation API: $errorCode")
                    }
                }
            },
        )

    }

    /**
     * Requests directions from the user's current location to a specific place (provided by the
     * Place ID).
     */
    private fun navigateToPlace(placeId: String) {
        val waypoint: Waypoint? =
            // Set a destination by using a Place ID (the recommended method)
            try {
                Waypoint.builder().setPlaceIdString(placeId).build()
            } catch (e: Waypoint.UnsupportedPlaceIdException) {
                showToast("Place ID was unsupported.")
                return
            }

        withNavigatorAsync {
            val pendingRoute = mNavigator?.setDestination(waypoint)

            // Set an action to perform when a route is determined to the destination
            pendingRoute?.setOnResultListener { code ->
                when (code) {
                    Navigator.RouteStatus.OK -> {
                        // Hide the toolbar to maximize the navigation UI
                        supportActionBar?.hide()

                        // Enable voice audio guidance (through the device speaker)
                        mNavigator?.setAudioGuidance(Navigator.AudioGuidance.VOICE_ALERTS_AND_GUIDANCE)

                        // Simulate vehicle progress along the route (for demo/debug builds)
                        if (BuildConfig.DEBUG) {
                            //mNavigator?.simulator?.setUserLocation(startLocation)
                            mNavigator?.simulator?.simulateLocationsAlongExistingRoute(
                                SimulationOptions().speedMultiplier(5f)
                            )
                        }

                        // Start turn-by-turn guidance along the current route
                        mNavigator?.startGuidance()
                    }

                    Navigator.RouteStatus.ROUTE_CANCELED -> showToast("Route guidance cancelled.")
                    Navigator.RouteStatus.NO_ROUTE_FOUND,
                    Navigator.RouteStatus.NETWORK_ERROR ->
                        // TODO: Add logic to handle when a route could not be determined
                        showToast("Error starting guidance: $code")

                    else -> showToast("Error starting guidance: $code")
                }

            }
        }
    }

    /**
     * Registers a number of example event listeners that show an on screen message when certain
     * navigation events occur (e.g. the driver's route changes or the destination is reached).
     */
    private fun registerNavigationListeners() {
        withNavigatorAsync {
            arrivalListener =
                Navigator.ArrivalListener { // Show an onscreen message
                    showToast("User has arrived at the destination!")
                    mNavigator?.clearDestinations()

                    // Stop simulating vehicle movement.
                    if (BuildConfig.DEBUG) {
                        mNavigator?.simulator?.unsetUserLocation()
                    }
                }
            mNavigator?.addArrivalListener(arrivalListener)

            routeChangedListener =
                Navigator.RouteChangedListener { // Show an onscreen message when the route changes
                    showToast("onRouteChanged: the driver's route changed")
                }
            mNavigator?.addRouteChangedListener(routeChangedListener)
        }
    }

    /**
     * Use https://developers.google.com/maps/documentation/javascript/examples/places-placeid-finder to choose
     * other Place IDs instead
     * right click on Google Maps in a browser, or long press in the mobile app,
     * to get a latitude longitude to use as the start location
     */
    companion object{
        const val TRAFALGAR_SQUARE ="ChIJH-tBOc4EdkgRJ8aJ8P1CUxo" //London, UK
        const val SYDNEY_OPERA_HOUSE = "ChIJ3S-JXmauEmsRUcIaWtf4MzE" //Sydney, Australia
        const val CENTRAL_PARK_ZOO = "ChIJaWjW_PFYwokRFD8a2YQu12U" //NYC USA
        val startLocation = LatLng(51.345678, -0.1234456)
    }

    /**
     * Runs [block] once navigator is initialized. Block is ignored if the navigator is never
     * initialized (error, etc.).
     *
     * This ensures that calls using the navigator before the navigator is initialized gets executed
     * after the navigator has been initialized.
     */
    private fun withNavigatorAsync(block: InitializedNavRunnable) {
        val navigatorScope = navigatorScope
        if (navigatorScope != null) {
            navigatorScope.block()
        } else {
            pendingNavActions.add(block)
        }
    }

    override fun onSaveInstanceState(savedInstanceState: Bundle) {
        super.onSaveInstanceState(savedInstanceState)

        navView.onSaveInstanceState(savedInstanceState)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        navView.onTrimMemory(level)
    }

    override fun onStart() {
        super.onStart()
        navView.onStart()
    }

    override fun onResume() {
        super.onResume()
        navView.onResume()
    }

    override fun onPause() {
        navView.onPause()
        super.onPause()
    }

    override fun onConfigurationChanged(configuration: Configuration) {
        super.onConfigurationChanged(configuration)
        navView.onConfigurationChanged(configuration)
    }

    override fun onStop() {
        navView.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        navView.onDestroy()
        withNavigatorAsync {
            // Unregister event listeners to avoid memory leaks.
            if (arrivalListener != null) {
                navigator.removeArrivalListener(arrivalListener)
            }
            if (routeChangedListener != null) {
                navigator.removeRouteChangedListener(routeChangedListener)
            }

            navigator.simulator?.unsetUserLocation()
            navigator.cleanup()
        }
        super.onDestroy()
    }

    //Show feedback to the user.
    private fun showToast(errorMessage: String) {
        Toast.makeText(this@MainActivity, errorMessage, Toast.LENGTH_LONG).show()
    }
}

open class InitializedNavScope(val navigator: Navigator)

typealias InitializedNavRunnable = InitializedNavScope.() -> Unit

