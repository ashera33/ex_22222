package com.tuinstituto.fitness_tracker

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executor
import kotlin.math.sqrt

class MainActivity : FlutterFragmentActivity() {

    private val BIOMETRIC_CHANNEL = "com.tuinstituto.fitness/biometric"
    private val ACCELEROMETER_CHANNEL = "com.tuinstituto.fitness/accelerometer"
    private val GPS_CHANNEL = "com.tuinstituto.fitness/gps"
    private val LOCATION_PERMISSION_REQUEST_CODE = 1001

    private lateinit var executor: Executor
    private lateinit var biometricPrompt: BiometricPrompt
    private var pendingResult: MethodChannel.Result? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        executor = ContextCompat.getMainExecutor(this)

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            BIOMETRIC_CHANNEL
        ).setMethodCallHandler { call, result ->
            when (call.method) {
                "checkBiometricSupport" -> result.success(checkBiometricSupport())
                "authenticate" -> {
                    pendingResult = result
                    showBiometricPrompt()
                }
                else -> result.notImplemented()
            }
        }

        setupAccelerometerChannel(flutterEngine)
        setupGpsChannel(flutterEngine)
    }

    private fun checkBiometricSupport(): Boolean {
        val biometricManager = BiometricManager.from(this)
        return when (biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        )) {
            BiometricManager.BIOMETRIC_SUCCESS -> true
            else -> false
        }
    }

    private fun showBiometricPrompt() {
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Autenticación Biométrica")
            .setSubtitle("Usa tu huella dactilar")
            .setDescription("Coloca tu dedo en el sensor")
            .setNegativeButtonText("Cancelar")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()

        biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    pendingResult?.success(true)
                    pendingResult = null
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    pendingResult?.success(false)
                    pendingResult = null
                }
                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                }
            }
        )
        biometricPrompt.authenticate(promptInfo)
    }

    private fun setupAccelerometerChannel(flutterEngine: FlutterEngine) {
        val sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        var stepCount = 0
        var lastMagnitude = 0.0
        var sensorEventListener: SensorEventListener? = null
        val magnitudeHistory = mutableListOf<Double>()
        val historySize = 10
        var sampleCount = 0
        var lastActivityType = "stationary"
        var activityConfidence = 0

        EventChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            ACCELEROMETER_CHANNEL
        ).setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                sensorEventListener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent?) {
                        event?.let {
                            val x = it.values[0]
                            val y = it.values[1]
                            val z = it.values[2]
                            val magnitude = sqrt((x * x + y * y + z * z).toDouble())

                            magnitudeHistory.add(magnitude)
                            if (magnitudeHistory.size > historySize) magnitudeHistory.removeAt(0)
                            val avgMagnitude = magnitudeHistory.average()

                            if (magnitude > 12 && lastMagnitude <= 12) stepCount++
                            lastMagnitude = magnitude

                            val newActivityType = when {
                                avgMagnitude < 10.5 -> "stationary"
                                avgMagnitude < 13.5 -> "walking"
                                else -> "running"
                            }

                            if (newActivityType == lastActivityType) activityConfidence++
                            else activityConfidence = 0

                            val finalActivityType = if (activityConfidence >= 3) newActivityType else lastActivityType
                            lastActivityType = newActivityType

                            sampleCount++
                            if (sampleCount >= 3) {
                                sampleCount = 0
                                events?.success(mapOf(
                                    "stepCount" to stepCount,
                                    "activityType" to finalActivityType,
                                    "magnitude" to avgMagnitude
                                ))
                            }
                        }
                    }
                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                }
                sensorManager.registerListener(
                    sensorEventListener,
                    accelerometer,
                    SensorManager.SENSOR_DELAY_GAME
                )
            }
            override fun onCancel(arguments: Any?) {
                sensorEventListener?.let { sensorManager.unregisterListener(it) }
                sensorEventListener = null
            }
        })

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            "$ACCELEROMETER_CHANNEL/control"
        ).setMethodCallHandler { call, result ->
            when (call.method) {
                "start" -> { stepCount = 0; result.success(null) }
                "stop" -> result.success(null)
                "reset" -> { stepCount = 0; result.success(null) }
                else -> result.notImplemented()
            }
        }
    }

    private fun setupGpsChannel(flutterEngine: FlutterEngine) {
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        var locationListener: LocationListener? = null

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            GPS_CHANNEL
        ).setMethodCallHandler { call, result ->
            when (call.method) {
                "isGpsEnabled" -> {
                    result.success(locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
                }
                "requestPermissions" -> {
                    if (hasLocationPermission()) {
                        result.success(true)
                    } else {
                        ActivityCompat.requestPermissions(
                            this,
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            ),
                            LOCATION_PERMISSION_REQUEST_CODE
                        )
                        result.success(hasLocationPermission())
                    }
                }
                "getCurrentLocation" -> {
                    if (!hasLocationPermission()) {
                        result.error("PERMISSION_DENIED", "Sin permisos", null)
                        return@setMethodCallHandler
                    }
                    try {
                        val location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                            ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                        if (location != null) result.success(locationToMap(location))
                        else result.error("NO_LOCATION", "No disponible", null)
                    } catch (e: SecurityException) {
                        result.error("SECURITY_ERROR", e.message, null)
                    }
                }
                else -> result.notImplemented()
            }
        }

        EventChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            "$GPS_CHANNEL/stream"
        ).setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                if (!hasLocationPermission()) {
                    events?.error("PERMISSION_DENIED", "Sin permisos", null)
                    return
                }
                locationListener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        events?.success(locationToMap(location))
                    }
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }
                try {
                    locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        1000L,
                        0f,
                        locationListener!!
                    )
                } catch (e: SecurityException) {
                    events?.error("SECURITY_ERROR", e.message, null)
                }
            }
            override fun onCancel(arguments: Any?) {
                locationListener?.let { locationManager.removeUpdates(it) }
                locationListener = null
            }
        })
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun locationToMap(location: Location): Map<String, Any> {
        return mapOf(
            "latitude" to location.latitude,
            "longitude" to location.longitude,
            "altitude" to location.altitude,
            "speed" to location.speed.toDouble(),
            "accuracy" to location.accuracy.toDouble(),
            "timestamp" to location.time
        )
    }
}