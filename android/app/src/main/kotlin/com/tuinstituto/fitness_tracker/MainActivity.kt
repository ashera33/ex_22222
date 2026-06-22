package com.tuinstituto.fitness_tracker

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
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

    private lateinit var fusedLocationClient: FusedLocationProviderClient

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        executor = ContextCompat.getMainExecutor(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

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

    // ─── BIOMETRÍA (sin cambios) ───────────────────────────────────────────────

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

    // ─── ACELERÓMETRO (mejorado para A03s sin giroscopio) ────────────────────

    private fun setupAccelerometerChannel(flutterEngine: FlutterEngine) {
        val sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        // A03s: usar acelerómetro lineal si está disponible, si no el normal
        val linearAccel = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val normalAccel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val accelerometer = linearAccel ?: normalAccel

        var stepCount = 0
        var lastMagnitude = 0.0
        var sensorEventListener: SensorEventListener? = null

        // Ventana grande para suavizar ruido del A03s (sin giroscopio)
        val windowSize = 25
        val magnitudeHistory = mutableListOf<Double>()
        var sampleCount = 0
        var lastActivityType = "stationary"
        var activityConfidence = 0

        // Filtro paso bajo para reducir ruido de alta frecuencia
        val alpha = 0.8f
        var filteredX = 0f
        var filteredY = 0f
        var filteredZ = 0f
        var firstSample = true

        EventChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            ACCELEROMETER_CHANNEL
        ).setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                sensorEventListener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent?) {
                        event?.let {
                            val rawX = it.values[0]
                            val rawY = it.values[1]
                            val rawZ = it.values[2]

                            // Filtro paso bajo: elimina vibraciones y ruido rápido
                            if (firstSample) {
                                filteredX = rawX
                                filteredY = rawY
                                filteredZ = rawZ
                                firstSample = false
                            } else {
                                filteredX = alpha * filteredX + (1 - alpha) * rawX
                                filteredY = alpha * filteredY + (1 - alpha) * rawY
                                filteredZ = alpha * filteredZ + (1 - alpha) * rawZ
                            }

                            val magnitude = sqrt(
                                (filteredX * filteredX +
                                 filteredY * filteredY +
                                 filteredZ * filteredZ).toDouble()
                            )

                            // Ventana deslizante de 25 muestras
                            magnitudeHistory.add(magnitude)
                            if (magnitudeHistory.size > windowSize) {
                                magnitudeHistory.removeAt(0)
                            }
                            val avgMagnitude = magnitudeHistory.average()

                            // Varianza: mide cuánto varía la señal (clave para A03s)
                            val variance = magnitudeHistory.map {
                                (it - avgMagnitude) * (it - avgMagnitude)
                            }.average()

                            // Detección de pasos con señal filtrada
                            if (magnitude > 12 && lastMagnitude <= 12) stepCount++
                            lastMagnitude = magnitude

                            // Clasificación por varianza (no por magnitud fija)
                            val newActivityType = when {
                                variance < 0.8 -> "stationary"   // sin movimiento real
                                variance < 4.0 -> "walking"      // pasos regulares
                                else           -> "running"      // impacto fuerte
                            }

                            // Requiere 8 lecturas consistentes para cambiar estado
                            if (newActivityType == lastActivityType) {
                                activityConfidence++
                            } else {
                                activityConfidence = 0
                            }

                            val finalActivityType =
                                if (activityConfidence >= 8) newActivityType
                                else lastActivityType

                            lastActivityType = newActivityType

                            // Enviar cada 3 muestras (reduce carga en Flutter)
                            sampleCount++
                            if (sampleCount >= 3) {
                                sampleCount = 0
                                events?.success(mapOf(
                                    "stepCount"    to stepCount,
                                    "activityType" to finalActivityType,
                                    "magnitude"    to avgMagnitude,
                                    "variance"     to variance
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
                "start"  -> { stepCount = 0; result.success(null) }
                "stop"   -> result.success(null)
                "reset"  -> { stepCount = 0; result.success(null) }
                else     -> result.notImplemented()
            }
        }
    }

    // ─── GPS (FusedLocationProvider — estable desde el primer punto) ──────────

    private fun setupGpsChannel(flutterEngine: FlutterEngine) {
        var locationCallback: LocationCallback? = null

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            GPS_CHANNEL
        ).setMethodCallHandler { call, result ->
            when (call.method) {
                "isGpsEnabled" -> {
                    val lm = getSystemService(LOCATION_SERVICE) as LocationManager
                    result.success(lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
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
                    fusedLocationClient.lastLocation
                        .addOnSuccessListener { loc ->
                            if (loc != null) result.success(locationToMap(loc))
                            else result.error("NO_LOCATION", "Ubicación no disponible", null)
                        }
                        .addOnFailureListener { e ->
                            result.error("ERROR", e.message, null)
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

                val locationRequest = LocationRequest.Builder(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    2000L                        // actualizar cada 2 segundos
                )
                    .setMinUpdateDistanceMeters(1f)   // mínimo 1 metro de cambio
                    .setWaitForAccurateLocation(true) // esperar señal precisa antes del primer punto
                    .build()

                locationCallback = object : LocationCallback() {
                    override fun onLocationResult(result: LocationResult) {
                        for (location in result.locations) {
                            // Filtrar puntos con baja precisión (ruido GPS)
                            if (location.accuracy <= 30f) {
                                events?.success(locationToMap(location))
                            }
                        }
                    }
                }

                fusedLocationClient.requestLocationUpdates(
                    locationRequest,
                    locationCallback!!,
                    Looper.getMainLooper()
                )
            }

            override fun onCancel(arguments: Any?) {
                locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
                locationCallback = null
            }
        })
    }

    // ─── UTILIDADES ───────────────────────────────────────────────────────────

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun locationToMap(location: android.location.Location): Map<String, Any> {
        return mapOf(
            "latitude"  to location.latitude,
            "longitude" to location.longitude,
            "altitude"  to location.altitude,
            "speed"     to location.speed.toDouble(),
            "accuracy"  to location.accuracy.toDouble(),
            "timestamp" to location.time
        )
    }
}
