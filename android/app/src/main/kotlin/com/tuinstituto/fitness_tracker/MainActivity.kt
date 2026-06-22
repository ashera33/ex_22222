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

    private val BIOMETRIC_CHANNEL      = "com.tuinstituto.fitness/biometric"
    private val ACCELEROMETER_CHANNEL  = "com.tuinstituto.fitness/accelerometer"
    private val FALL_CHANNEL           = "com.tuinstituto.fitness/fall"
    private val GPS_CHANNEL            = "com.tuinstituto.fitness/gps"
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

    // ─── BIOMETRÍA ────────────────────────────────────────────────────────────

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

    // ─── ACELERÓMETRO + DETECCIÓN DE CAÍDA ───────────────────────────────────
    //
    // Dos pipelines paralelos sobre el MISMO listener:
    //
    //   señal raw ──► [sin filtro]  ──► detección de caída  (SENSOR_DELAY_GAME)
    //             └──► [filtro LPF] ──► pasos + actividad   (enviado cada 3 muestras)
    //
    // Lógica de caída (teléfono en mano):
    //   Fase 1 — IMPACTO:   magnitud raw > 25 m/s²  (sacudida brusca)
    //   Fase 2 — QUIETUD:   magnitud raw < 3  m/s²  dentro de 300 ms
    //   Debounce 2 s:       no re-disparar mientras Flutter procesa el modal

    private fun setupAccelerometerChannel(flutterEngine: FlutterEngine) {
        val sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        val linearAccel  = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val normalAccel  = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val accelerometer = linearAccel ?: normalAccel

        // Umbral de pasos: acelerómetro lineal no incluye gravedad, el normal sí
        val stepThreshold = if (linearAccel != null) 12.0 else 15.0
        val sensorType    = if (linearAccel != null) "LINEAR" else "NORMAL"

        // ── Estado pipeline actividad ─────────────────────────────────────────
        var stepCount       = 0
        var lastMagnitude   = 0.0
        var filteredX       = 0f
        var filteredY       = 0f
        var filteredZ       = 0f
        var firstSample     = true
        val windowSize      = 25
        val magnitudeHistory = mutableListOf<Double>()
        var sampleCount     = 0
        var lastActivityType   = "stationary"
        var activityConfidence = 0

        // ── Estado pipeline caída ─────────────────────────────────────────────
        // Umbral de impacto: 25 m/s² coincide con el valor del UI de Flutter
        val FALL_IMPACT_THRESHOLD  = 25.0   // m/s² — pico de sacudida brusca
        val FALL_QUIET_THRESHOLD   =  3.0   // m/s² — quietud post-impacto
        val FALL_QUIET_WINDOW_MS   = 300L   // ms — ventana para detectar quietud
        val FALL_DEBOUNCE_MS       = 2000L  // ms — igual al debounce del UI Flutter

        var fallImpactTime     = 0L   // timestamp del último pico detectado
        var fallDebounceTime   = 0L   // timestamp del último evento enviado a Flutter
        var waitingForQuiet    = false

        // ── EventSinks ───────────────────────────────────────────────────────
        var activitySink: EventChannel.EventSink? = null
        var fallSink:     EventChannel.EventSink? = null

        var sensorEventListener: SensorEventListener? = null

        // Helper para registrar/des-registrar con la misma referencia
        fun registerSensor() {
            if (sensorEventListener != null) return

            sensorEventListener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent?) {
                    event ?: return

                    val rawX = event.values[0]
                    val rawY = event.values[1]
                    val rawZ = event.values[2]
                    val now  = System.currentTimeMillis()

                    // ── PIPELINE CAÍDA: señal RAW, sin filtro ────────────────
                    val rawMag = sqrt(
                        (rawX * rawX + rawY * rawY + rawZ * rawZ).toDouble()
                    )

                    // Fase 1: detectar pico de impacto
                    if (rawMag > FALL_IMPACT_THRESHOLD &&
                        (now - fallDebounceTime) > FALL_DEBOUNCE_MS) {
                        fallImpactTime  = now
                        waitingForQuiet = true
                    }

                    // Fase 2: dentro de la ventana de 300ms, buscar quietud
                    if (waitingForQuiet &&
                        (now - fallImpactTime) <= FALL_QUIET_WINDOW_MS &&
                        rawMag < FALL_QUIET_THRESHOLD) {

                        waitingForQuiet  = false
                        fallDebounceTime = now
                        // Enviar true a Flutter → activa el modal de caída
                        fallSink?.success(true)
                    }

                    // Cancelar espera si la ventana expiró sin quietud
                    if (waitingForQuiet &&
                        (now - fallImpactTime) > FALL_QUIET_WINDOW_MS) {
                        waitingForQuiet = false
                    }

                    // ── PIPELINE ACTIVIDAD: señal FILTRADA ───────────────────
                    val currentVariance = if (magnitudeHistory.size >= 5) {
                        val avg = magnitudeHistory.takeLast(5).average()
                        magnitudeHistory.takeLast(5).map {
                            (it - avg) * (it - avg)
                        }.average()
                    } else 0.0

                    val alpha = if (currentVariance > 4.0) 0.6f else 0.8f

                    if (firstSample) {
                        filteredX = rawX; filteredY = rawY; filteredZ = rawZ
                        firstSample = false
                    } else {
                        filteredX = alpha * filteredX + (1 - alpha) * rawX
                        filteredY = alpha * filteredY + (1 - alpha) * rawY
                        filteredZ = alpha * filteredZ + (1 - alpha) * rawZ
                    }

                    val filteredMag = sqrt(
                        (filteredX * filteredX +
                         filteredY * filteredY +
                         filteredZ * filteredZ).toDouble()
                    )

                    magnitudeHistory.add(filteredMag)
                    if (magnitudeHistory.size > windowSize) magnitudeHistory.removeAt(0)

                    val avgMagnitude = magnitudeHistory.average()
                    val variance = magnitudeHistory.map {
                        (it - avgMagnitude) * (it - avgMagnitude)
                    }.average()

                    if (filteredMag > stepThreshold && lastMagnitude <= stepThreshold) stepCount++
                    lastMagnitude = filteredMag

                    val newActivityType = when {
                        variance < 0.8 -> "stationary"
                        variance < 4.0 -> "walking"
                        else           -> "running"
                    }

                    if (newActivityType == lastActivityType) activityConfidence++
                    else activityConfidence = 0

                    val finalActivityType =
                        if (activityConfidence >= 8) newActivityType else lastActivityType
                    lastActivityType = newActivityType

                    sampleCount++
                    if (sampleCount >= 3) {
                        sampleCount = 0
                        activitySink?.success(mapOf(
                            "stepCount"    to stepCount,
                            "activityType" to finalActivityType,
                            "magnitude"    to avgMagnitude,
                            "variance"     to variance,
                            "sensorType"   to sensorType
                        ))
                    }
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }

            // SENSOR_DELAY_GAME (20ms): necesario para capturar el pico de caída
            // Para la actividad sería suficiente DELAY_UI, pero la caída manda
            sensorManager.registerListener(
                sensorEventListener,
                accelerometer,
                SensorManager.SENSOR_DELAY_GAME
            )
        }

        fun unregisterSensor() {
            sensorEventListener?.let { sensorManager.unregisterListener(it) }
            sensorEventListener = null
        }

        // ── Canal de actividad ────────────────────────────────────────────────
        EventChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            ACCELEROMETER_CHANNEL
        ).setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                activitySink = events
                registerSensor()
            }
            override fun onCancel(arguments: Any?) {
                activitySink = null
                if (fallSink == null) unregisterSensor()
            }
        })

        // ── Canal de caída ────────────────────────────────────────────────────
        EventChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            FALL_CHANNEL
        ).setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                fallSink = events
                registerSensor()
            }
            override fun onCancel(arguments: Any?) {
                fallSink = null
                if (activitySink == null) unregisterSensor()
            }
        })

        // ── Control ───────────────────────────────────────────────────────────
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

    // ─── GPS (optimizado para GPS L1 del A03s) ────────────────────────────────

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
                    2000L
                )
                    .setMinUpdateDistanceMeters(1f)
                    .setWaitForAccurateLocation(true)
                    .build()

                locationCallback = object : LocationCallback() {
                    override fun onLocationResult(result: LocationResult) {
                        for (location in result.locations) {
                            if (location.accuracy <= 20f) {
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
