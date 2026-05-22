package com.torqeedo.controller.ble

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import com.torqeedo.controller.protocol.TorqeedoProtocol
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.osmdroid.util.GeoPoint
import java.util.Locale
import kotlin.math.*

data class Waypoint(val name: String, val point: GeoPoint)

enum class Direction { FORWARD, REVERSE }
enum class SeaState { CALM, MODERATE, ROUGH }

/**
 * Singleton repository to maintain BLE connections and global motor/autopilot state.
 */
object BleRepository : TextToSpeech.OnInitListener {
    private const val TAG = "BleRepository"
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // --- BLE Managers ---
    private var motorManager: TorqeedoBleManager? = null
    private var imuManager: TorqeedoBleManager? = null
    private var gpsManager: TorqeedoBleManager? = null
    private var remote: LookbonRemote? = null

    // --- TTS ---
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    // --- Constants ---
    const val SPEED_MAX = 1000
    const val SPEED_MIN = 0
    const val STEER_MAX = 100
    private const val STATUS_QUERY_DELAY = 500L
    private const val STEER_REPEAT_DELAY = 80L
    private const val AUTOPILOT_MAX_I = 20f

    // --- Shared Control State ---
    val speedMagnitude = MutableStateFlow(0)
    val direction = MutableStateFlow(Direction.FORWARD)
    val steerValue = MutableStateFlow(0)
    val autoPilotActive = MutableStateFlow(false)
    val targetHeading = MutableStateFlow(0f)
    val slaveMode = MutableStateFlow(false)
    
    val currentSpeed: StateFlow<Int> = combine(direction, speedMagnitude) { dir, mag ->
        if (dir == Direction.FORWARD) mag else -mag
    }.stateIn(scope, SharingStarted.Eagerly, 0)

    val remoteConnected = MutableStateFlow(false)

    // --- Shared Sensor Data (Synced from Managers/ViewModels) ---
    val trueHeading = MutableStateFlow(0f)
    val gyroZDegS = MutableStateFlow(0f)
    var lastGyroUpdateTime = 0L
    val rudderPosition = MutableStateFlow(0f)
    val sensorCurrent = MutableStateFlow(0f)

    // --- Configurable Parameters (Synced from ViewModels/Prefs) ---
    var speedStep = 20
    var autoIncrementDelay = 200L
    var throttleDelay = 200L
    var steerScale = 200
    var apKp = 2.5f
    var apKi = 0.1f
    var apKd = 1.0f
    var apDeadband = 3.0f
    var maxTurnRate = 12f
    var autoPilotDelay = 500L
    var useRudderSensor = false
    var showMotorStatus = false
    var enableVoicePrompts = true

    // --- PID Internal State ---
    private var autopilotLastError = 0f
    private var autopilotIntegral = 0f
    private var isUpdatingAutoPilot = false

    // --- Voice Command Flow (ViewModels listen to this to call TTS) ---
    private val _voiceCommand = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val voiceCommand = _voiceCommand.asSharedFlow()
    
    fun initTts(context: Context) {
        if (tts == null) {
            tts = TextToSpeech(context.applicationContext, this)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            ttsReady = true
        } else {
            Log.e(TAG, "TTS Initialization failed")
        }
    }

    fun speak(text: String) {
        if (enableVoicePrompts) {
            if (ttsReady) {
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "voice_prompt")
            } else {
                Log.w(TAG, "TTS not ready yet: $text")
            }
        }
        _voiceCommand.tryEmit(text)
    }

    fun speakThrottle() {
        val speed = if (direction.value == Direction.FORWARD) speedMagnitude.value else -speedMagnitude.value
        speak("${speed / 10} percent")
    }

    // --- Global Job Management ---
    private var throttleJob: Job? = null
    private var statusQueryJob: Job? = null
    private var autoAdjustmentJob: Job? = null
    private var steerRepeatJob: Job? = null
    private var autoPilotJob: Job? = null
    private var slaveJob: Job? = null

    // --- Navigation State ---
    private val _targetLocation = MutableStateFlow<GeoPoint?>(null)
    val targetLocation: StateFlow<GeoPoint?> = _targetLocation.asStateFlow()

    private val _targetName = MutableStateFlow<String?>(null)
    val targetName: StateFlow<String?> = _targetName.asStateFlow()

    private val _waypoints = MutableStateFlow<List<Waypoint>>(emptyList())
    val waypoints: StateFlow<List<Waypoint>> = _waypoints.asStateFlow()
    private val _currentLocation = MutableStateFlow<GeoPoint?>(null)
    val currentLocation: StateFlow<GeoPoint?> = _currentLocation.asStateFlow()

    // --- Lifecycle & Initialization ---

    fun getMotorManager(context: Context): TorqeedoBleManager {
        initTts(context)
        return motorManager ?: TorqeedoBleManager(context.applicationContext).also {
            motorManager = it
            observeMotorConnection(it)
        }
    }

    fun getImuManager(context: Context): TorqeedoBleManager {
        initTts(context)
        return imuManager ?: TorqeedoBleManager(context.applicationContext).also {
            imuManager = it
            observeImuConnection(it)
        }
    }

    fun getGpsManager(context: Context): TorqeedoBleManager {
        initTts(context)
        return gpsManager ?: TorqeedoBleManager(context.applicationContext).also {
            gpsManager = it
            observeGpsConnection(it)
        }
    }

    fun getRemote(context: Context): LookbonRemote {
        initTts(context)
        return remote ?: LookbonRemote(context.applicationContext).also {
            remote = it
            setupRemoteCommands()
        }
    }

    private fun observeMotorConnection(manager: TorqeedoBleManager) {
        scope.launch {
            manager.connectionState.collect { state ->
                when (state) {
                    TorqeedoBleManager.ConnectionState.CONNECTED -> {
                        speak("Motor connected")
                        if (slaveMode.value) {
                            startSlaveLoop()
                        } else {
                            startThrottleLoop()
                            startStatusQueryLoop()
                        }
                    }
                    TorqeedoBleManager.ConnectionState.DISCONNECTED -> {
                        speak("Motor disconnected")
                        stopAllLoops()
                    }
                    else -> {}
                }
            }
        }

        scope.launch {
            manager.imuA1Data.collect {
                lastGyroUpdateTime = System.currentTimeMillis()
            }
        }

        // Watch for slaveMode changes while connected
        scope.launch {
            slaveMode.collect { isSlave ->
                if (motorManager?.connectionState?.value == TorqeedoBleManager.ConnectionState.CONNECTED) {
                    if (isSlave) {
                        stopThrottleLoop()
                        stopStatusQueryLoop()
                        startSlaveLoop()
                    } else {
                        stopSlaveLoop()
                        startThrottleLoop()
                        startStatusQueryLoop()
                    }
                }
            }
        }
    }

    private fun observeImuConnection(manager: TorqeedoBleManager) {
        scope.launch {
            manager.connectionState.collect { state ->
                when (state) {
                    TorqeedoBleManager.ConnectionState.CONNECTED -> speak("Heading sensor connected")
                    TorqeedoBleManager.ConnectionState.DISCONNECTED -> speak("Heading sensor disconnected")
                    else -> {}
                }
            }
        }
        scope.launch {
            manager.imuA1Data.collect {
                lastGyroUpdateTime = System.currentTimeMillis()
            }
        }
    }

    private fun observeGpsConnection(manager: TorqeedoBleManager) {
        scope.launch {
            manager.connectionState.collect { state ->
                when (state) {
                    TorqeedoBleManager.ConnectionState.CONNECTED -> speak("G P S connected")
                    TorqeedoBleManager.ConnectionState.DISCONNECTED -> speak("G P S disconnected")
                    else -> {}
                }
            }
        }
        scope.launch {
            manager.imuA1Data.collect {
                lastGyroUpdateTime = System.currentTimeMillis()
            }
        }
    }

    // --- Control Logic ---

    fun setDirection(dir: Direction) {
        if (direction.value != dir) {
            direction.value = dir
            speak(dir.name.lowercase(Locale.US))
        }
    }

    fun setSpeedMagnitude(mag: Int) {
        speedMagnitude.value = mag.coerceIn(SPEED_MIN, SPEED_MAX)
    }

    fun increaseSpeed() {
        if (direction.value == Direction.FORWARD) {
            speedMagnitude.value = (speedMagnitude.value + speedStep).coerceAtMost(SPEED_MAX)
        } else {
            val next = speedMagnitude.value - speedStep
            if (next < 0) {
                direction.value = Direction.FORWARD
                speedMagnitude.value = -next
                speak("forward")
            } else {
                speedMagnitude.value = next
            }
        }
    }

    fun decreaseSpeed() {
        if (direction.value == Direction.REVERSE) {
            speedMagnitude.value = (speedMagnitude.value + speedStep).coerceAtMost(SPEED_MAX)
        } else {
            val next = speedMagnitude.value - speedStep
            if (next < 0) {
                direction.value = Direction.REVERSE
                speedMagnitude.value = -next
                speak("reverse")
            } else {
                speedMagnitude.value = next
            }
        }
    }

    fun stopMotor() {
        stopAutoAdjustment()
        speedMagnitude.value = 0
        speak("Stop")
    }

    fun startAutoIncrease(multiplier: Int = 1) {
        autoAdjustmentJob?.cancel()
        autoAdjustmentJob = scope.launch {
            while (true) {
                increaseSpeed()
                delay(autoIncrementDelay / multiplier)
            }
        }
    }

    fun startAutoDecrease(multiplier: Int = 1) {
        autoAdjustmentJob?.cancel()
        autoAdjustmentJob = scope.launch {
            while (true) {
                decreaseSpeed()
                delay(autoIncrementDelay / multiplier)
            }
        }
    }

    fun stopAutoAdjustment() {
        autoAdjustmentJob?.cancel()
        autoAdjustmentJob = null
    }

    // Track the last physical direction: 1 = positive/right, -1 = negative/left, 0 = stopped
    private var lastMotorDirection = 0
    private fun takeUpBacklash(direction: Int) {
        //if (!useRudderSensor) return
        val startPos = rudderPosition.value
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < 400) {

            motorManager?.sendSteer(direction, 100)
            Thread.sleep(20)

            val movement = (rudderPosition.value - startPos) * direction

            if (movement > 1.0f) {
                break
            }
        }
        motorManager?.sendSteer(0, 0)
    }
    private fun executeSteerChange(targetValue: Int, customScale: Int) {
        val clampedTarget = targetValue.coerceIn(-STEER_MAX, STEER_MAX)
        val oldValue = steerValue.value
        var delta = clampedTarget - oldValue

        if (delta == 0) return

        // 1. Safety Look-Ahead Capping
        if (useRudderSensor&&(autoPilotJob?.isActive == true)) {
            val currentPos = rudderPosition.value
            val predictedPos = currentPos + delta

            if (predictedPos > 99f) {
                delta = (99f - currentPos).toInt().coerceAtLeast(0)
            } else if (predictedPos < -99f) {
                delta = (-99f - currentPos).toInt().coerceAtMost(0)
            }
        }

        // 2. Soft Stop Cushioning Zone
        if (abs(rudderPosition.value) > 70f) {
            delta = delta.coerceIn(-5, 5)
        }

        // 3. Final Verification
        if (delta == 0) {
            motorManager?.sendSteer(0, 0)
            lastMotorDirection = 0 // Reset direction tracking on stop
            return
        }

        val newValue = (oldValue + delta).coerceIn(-STEER_MAX, STEER_MAX)
        if (newValue != oldValue) {
            steerValue.value = newValue

            // --------------------------------------------------
            // BACKLASH COMPENSATION (THE KICK)
            // --------------------------------------------------
            val currentDirection = if (delta > 0) 1 else -1
            var runtimeMs = abs(delta) * customScale

            // If we switched directions, add a 250ms "kick" to take up the gear slack
            if (lastMotorDirection != 0 && currentDirection != lastMotorDirection) {
                //runtimeMs += 250 // Adjust this number (100 to 200) based on live testing
                if (useRudderSensor&&(autoPilotJob?.isActive == true)){
                    takeUpBacklash(currentDirection)
                }else{
                    //runtimeMs += 250
                }
            }

            // Update the direction history tracking
            lastMotorDirection = currentDirection

            // 4. Hardware Dispatch
            motorManager?.sendSteer(delta, runtimeMs)
        }
    }


    /*
    fun setSteerValue(value: Int) {
        val clamped = value.coerceIn(-STEER_MAX, STEER_MAX)
        val oldValue = steerValue.value
        if (oldValue != clamped) {
            val delta = clamped - oldValue

            // Safety: don't drive past physical limits if sensor is available
            if (useRudderSensor) {
                if (delta > 0 && rudderPosition.value >= 99f) return
                if (delta < 0 && rudderPosition.value <= -99f) return
            }

            steerValue.value = clamped
            val runtimeMs = abs(delta) * steerScale
            motorManager?.sendSteer(delta, runtimeMs)
        }
    }

    fun adjustSteer(delta: Int) {
        if (delta == 0) return

        var safeDelta = delta

        // Safety: Clamp the delta dynamically based on the current sensor position
        val currentPos = rudderPosition.value
        if (useRudderSensor) {
            // Predict future position if this delta is fully executed
            val predictedPos = currentPos + delta

            if (predictedPos > 99f) {
                // Cap the positive movement so it stops exactly at 99f
                safeDelta = (99f - currentPos).toInt().coerceAtLeast(0)
            } else if (predictedPos < -99f) {
                // Cap the negative movement so it stops exactly at -99f
                safeDelta = (-99f - currentPos).toInt().coerceAtMost(0)
            }
        }

        if (abs(currentPos) > 70f) {
            safeDelta = safeDelta.coerceIn(-5, 5)
        }

        if (safeDelta == 0) {
            // Force stop hardware if we are at or past the limit
            motorManager?.sendSteer(0, 0)
            return
        }

        val oldValue = steerValue.value
        val newValue = (oldValue + safeDelta).coerceIn(-STEER_MAX, STEER_MAX)
        val actualDelta = newValue - oldValue

        if (actualDelta != 0) {
            steerValue.value = newValue
            val runtimeMs = abs(actualDelta) * steerScale
            motorManager?.sendSteer(actualDelta, runtimeMs)
        }
    }
    */

    // Absolute Target Input (Used when your autopilot loop runs WITHOUT a rudder sensor)
    // Absolute Target Input (Accepts an optional custom scale)
    fun setSteerValue(value: Int, customScale: Int = steerScale) {
        executeSteerChange(targetValue = value, customScale = customScale)
    }

    // Relative Step Input (Accepts an optional custom scale, defaulting to remote scale)
    fun adjustSteer(delta: Int, customScale: Int = steerScale) {
        val targetValue = steerValue.value + delta
        executeSteerChange(targetValue = targetValue, customScale = customScale)
    }

    fun startSteerRepeat(delta: Int) {
        steerRepeatJob?.cancel()
        steerRepeatJob = scope.launch {
            while (true) {
                adjustSteer(delta)
                delay(STEER_REPEAT_DELAY)
            }
        }
    }

    fun stopSteerRepeat() {
        steerRepeatJob?.cancel()
        steerRepeatJob = null
    }

    fun setAutoPilotActive(active: Boolean) {
        if (active) {
            targetHeading.value = trueHeading.value
            autopilotLastError = 0f
            autopilotIntegral = 0f
            useRudderSensor = true
            speak("Auto pilot on, heading ${targetHeading.value.toInt()} degrees")
            startAutoPilotLoop()
        } else {
            if (autoPilotActive.value) {
                speak("Auto pilot off")
                stopAutoPilotLoop()
                //resetSteer()
                rudderPosition.value = 0.0f
            }
            useRudderSensor = false
        }
        autoPilotActive.value = active
    }

    /*
    fun resetSteer() {
        stopSteerRepeat()
        scope.launch {
            if (useRudderSensor) {
                syncRudderToTarget(0f)
            } else {
                setSteerValue(0)
                rudderPosition.value = 0.0f
            }
            speak("Straight")
        }
    }
    */

    fun adjustTargetHeading(delta: Float) {
        var newTarget = targetHeading.value + delta
        while (newTarget < 0) newTarget += 360f
        while (newTarget >= 360) newTarget -= 360f
        targetHeading.value = newTarget
        speak("${newTarget.toInt()} degrees")
    }

    // --- Background Loops ---

    private fun startThrottleLoop() {
        if (throttleJob?.isActive == true) return
        throttleJob = scope.launch {
            while (true) {
                motorManager?.sendDrive(currentSpeed.value)
                delay(throttleDelay)
            }
        }
    }
    
    private fun stopThrottleLoop() {
        throttleJob?.cancel()
        throttleJob = null
    }

    private fun startStatusQueryLoop() {
        if (statusQueryJob?.isActive == true) return
        statusQueryJob = scope.launch {
            while (true) {
                motorManager?.sendStatusQuery()
                delay(STATUS_QUERY_DELAY)
                if (showMotorStatus) {
                    motorManager?.sendSteerStatusQuery()
                    delay(STATUS_QUERY_DELAY)
                }
            }
        }
    }
    
    private fun stopStatusQueryLoop() {
        statusQueryJob?.cancel()
        statusQueryJob = null
    }

    private fun startAutoPilotLoop() {
        if (autoPilotJob?.isActive == true) return
        autoPilotJob = scope.launch {
            while (true) {
                updateAutoPilot()
                delay(autoPilotDelay)
            }
        }
    }

    private fun stopAutoPilotLoop() {
        autoPilotJob?.cancel()
        autoPilotJob = null
    }
    
    private fun startSlaveLoop() {
        if (slaveJob?.isActive == true) return
        slaveJob = scope.launch {
            // Listen to incoming drive commands and status queries, then respond ASAP (Reactive)
            motorManager?.statusFlow?.collect { status ->
                val dest = status.destAddr ?: return@collect
                
                // Watch for Drive command TO the motor: AC 30 82
                if (dest == TorqeedoProtocol.MOTOR_ADDR && status.msgId == TorqeedoProtocol.MSGID_DRIVE) {
                    // Update local state to follow master speed
                    status.targetSpeed?.let { speed ->
                        if (speed >= 0) {
                            direction.value = Direction.FORWARD
                            speedMagnitude.value = speed
                        } else {
                            direction.value = Direction.REVERSE
                            speedMagnitude.value = -speed
                        }
                    }
                    
                    // Reply ASAP (requirement is within 25ms) using Slave Response format
                    val replySpeed = currentSpeed.value
                    motorManager?.sendSlaveResponse(replySpeed)
                }
            }
        }
    }
    
    private fun stopSlaveLoop() {
        slaveJob?.cancel()
        slaveJob = null
    }

    private suspend fun syncRudderToTarget(target: Float) {
        val RUDDER_LIMIT = 80f

        // Allow exit if AP is turned off, unless we are resetting to zero
        if (!autoPilotActive.value && target != 0f) {
            motorManager?.sendSteer(0, 0)
            return // Use return instead of break since the loop is gone
        }

        val currentRudderPos = rudderPosition.value

        // Safety: stop if already at limit
        if (abs(currentRudderPos) >= RUDDER_LIMIT) {
            motorManager?.sendSteer(0, 0)
            return
        }

        val rudderError = target - currentRudderPos
        if (abs(rudderError) < 1.5f) {
            motorManager?.sendSteer(0, 0) // Target reached, explicitly cut power
            return
        }

        // FIX: Calculate the proportional step size directly
        val proportionalStep = (rudderError * 1.5f).toInt()
        val maxStepPerCycle = 25

        // Declare 'step' only ONCE here as a val
        val step = proportionalStep.coerceIn(-maxStepPerCycle, maxStepPerCycle)

        // Hardware scale optimized for 5Hz loops: Max 25 steps * 20ms = 500ms run time
        val autopilotSteerScale = 20
        adjustSteer(step, customScale = autopilotSteerScale)
    }

    private suspend fun updateAutoPilot() {
        if (isUpdatingAutoPilot) return
        if (!autoPilotActive.value) return
        
        isUpdatingAutoPilot = true
        try {
            val apKrate = 1.2f
            val maxStepPerUpdate = maxTurnRate * (autoPilotDelay / 1000f)

            val current = trueHeading.value
            val target = targetHeading.value
            val yawRate = gyroZDegS.value

            var error = target - current
            while (error > 180f) error -= 360f
            while (error < -180f) error += 360f

            //--------------------------------------------------
            // SOFT DEADBAND
            //--------------------------------------------------
            val effectiveError = if (abs(error) < apDeadband) {
                error * 0.2f
            } else {
                sign(error) * (abs(error) - apDeadband)
            }

            //--------------------------------------------------
            // INTEGRAL MANAGEMENT
            //--------------------------------------------------
            if (abs(error) > apDeadband) {
                autopilotIntegral += error * apKi
                autopilotIntegral = autopilotIntegral.coerceIn(-AUTOPILOT_MAX_I, AUTOPILOT_MAX_I)
            } else {
                // Slowly decay integral
                //autopilotIntegral *= 0.98f
                autopilotIntegral *= 0.85f
            }
            if (
                abs(error) < 0.3f &&
                abs(yawRate) < 0.2f
            ) {
                autopilotIntegral *= 0.8f
            }
            //--------------------------------------------------
            // GYRO YAW DAMPING
            //--------------------------------------------------
            val gyroAvailable = (System.currentTimeMillis() - lastGyroUpdateTime) < 500
            val yawDamping = if (gyroAvailable) yawRate * apKrate else 0f

            //--------------------------------------------------
            // MAIN CONTROL LAW
            //--------------------------------------------------
            val rawOutput = (effectiveError * apKp) + autopilotIntegral - yawDamping

            //--------------------------------------------------
            // OUTPUT LIMIT
            //--------------------------------------------------
            val targetOutput = rawOutput.coerceIn(-100f, 100f)

            //--------------------------------------------------
            // STEERING SLEW RATE LIMITER
            //--------------------------------------------------
            val currentOutput = if (useRudderSensor) rudderPosition.value else steerValue.value.toFloat()
            var delta = (targetOutput - currentOutput).coerceIn(-maxStepPerUpdate, maxStepPerUpdate)

            // Small deadband to avoid constant minor adjustments
            if (abs(delta) < 0.5f) delta = 0f

            //val output = (currentOutput + delta).coerceIn(-100f, 100f)
            val output = (currentOutput + delta).coerceIn(-100f, 100f)

            Log.d("AP","err=$error gyro=$yawRate damp=$yawDamping out=$rawOutput")
            //Log.d("AP","rudder=${rudderPosition.value}")
            //--------------------------------------------------
            // APPLY OUTPUT
            //--------------------------------------------------
            if (useRudderSensor) {
                syncRudderToTarget(output)
            } else {
                if (abs(delta) >= 1.0f) {
                    setSteerValue(output.toInt())
                }
            }
        } finally {
            isUpdatingAutoPilot = false
        }
    }

    private fun stopAllLoops() {
        throttleJob?.cancel()
        statusQueryJob?.cancel()
        autoAdjustmentJob?.cancel()
        steerRepeatJob?.cancel()
        autoPilotJob?.cancel()
        slaveJob?.cancel()
    }

    private fun setupRemoteCommands() {
        remote?.onCommand = { cmd ->
            when (cmd) {
                LookbonRemote.Command.SPEED_UP -> {
                    increaseSpeed()
                    speakThrottle()
                }
                LookbonRemote.Command.SPEED_DOWN -> {
                    decreaseSpeed()
                    speakThrottle()
                }
                LookbonRemote.Command.START_REPEAT_UP -> startAutoIncrease()
                LookbonRemote.Command.START_REPEAT_DOWN -> startAutoDecrease()
                LookbonRemote.Command.START_REPEAT_UP_FAST -> startAutoIncrease(multiplier = 2)
                LookbonRemote.Command.START_REPEAT_DOWN_FAST -> startAutoDecrease(multiplier = 2)
                LookbonRemote.Command.STOP_REPEAT -> {
                    stopAutoAdjustment()
                    speakThrottle()
                }
                LookbonRemote.Command.SPEED_UP_FAST -> {
                    repeat(5) { increaseSpeed() }
                    speakThrottle()
                }
                LookbonRemote.Command.SPEED_DOWN_FAST -> {
                    repeat(5) { decreaseSpeed() }
                    speakThrottle()
                }
                LookbonRemote.Command.STOP -> stopMotor()
                LookbonRemote.Command.TOGGLE_DIRECTION -> setDirection(if (direction.value == Direction.FORWARD) Direction.REVERSE else Direction.FORWARD)
                LookbonRemote.Command.STEER_LEFT -> {
                    speak("Left")
                    adjustSteer(-1)
                }
                LookbonRemote.Command.STEER_RIGHT -> {
                    speak("Right")
                    adjustSteer(1)
                }
                LookbonRemote.Command.START_STEER_LEFT -> {
                    speak("Left")
                    startSteerRepeat(-1)
                }
                LookbonRemote.Command.START_STEER_RIGHT -> {
                    speak("Right")
                    startSteerRepeat(1)
                }
                LookbonRemote.Command.STOP_STEER -> stopSteerRepeat()
                
                LookbonRemote.Command.DOUBLE_STEER_LEFT -> {
                    speak("Hard left")
                    // If a single click already happened (1), we add 4 more to make it 5.
                    // But if it was a "pure" double click from joystick, we might want 5.
                    // For simplicity, let's just do 5 and ignore the single click overlap for now.
                    adjustSteer(-5)
                }
                LookbonRemote.Command.DOUBLE_STEER_RIGHT -> {
                    speak("Hard right")
                    adjustSteer(5)
                }
                LookbonRemote.Command.DOUBLE_SPEED_UP -> {
                    repeat(5) { increaseSpeed() } // 5 * 20 = 100 (10%)
                    speakThrottle()
                }
                LookbonRemote.Command.DOUBLE_SPEED_DOWN -> {
                    repeat(5) { decreaseSpeed() } // 5 * 20 = 100 (10%)
                    speakThrottle()
                }
            }
        }
        remote?.onConnected = {
            remoteConnected.value = true
            speak("Remote connected")
        }
        remote?.onDisconnected = {
            remoteConnected.value = false
            speak("Remote disconnected")
        }
    }

    // --- Navigation Helpers ---

    fun setTarget(loc: GeoPoint?, name: String? = null) {
        _targetLocation.value = loc
        _targetName.value = name
    }

    fun setWaypoints(list: List<Waypoint>) {
        _waypoints.value = list
    }
    
    fun setCurrentLocation(loc: GeoPoint?) {
        _currentLocation.value = loc
    }
}
