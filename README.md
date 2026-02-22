# ServiceConnector : Android IPC (using AIDL)

## Installation

Add the following to your module-level `build.gradle`:

```groovy
implementation("io.github.sridhar-sp:aidl-service-connector:0.1.0")
```

* For the latest version, visit
  the [Maven Central repository](https://central.sonatype.com/artifact/io.github.sridhar-sp/aidl-service-connector/0.1.0).

## Quick guide to AIDL.

AIDL (Android Interface Definition Language) is Android's mechanism for inter-process communication (IPC) — it lets two
apps talk to each other across process boundaries as if they were calling regular Kotlin/Java methods.

The typical setup involves two sides:
**Server** — defines the `.aidl` interface file, implements it inside a `Service`, and exposes it via `onBind`. Any app
with the right intent and permissions can connect to it.

**Client** — binds to the server's service using an explicit intent, receives an `IBinder` in `onServiceConnected`, and
casts it to the AIDL-generated interface using `Stub.asInterface(binder)`. From there, calling remote methods looks
identical to calling local ones, though under the hood Android is marshalling data across processes.

The tricky part isn't the initial connection, it's handling everything that can go wrong afterwards. The server app can
crash, be killed by the system, or be updated, leaving your client holding a dead binder. This is exactly the problem
ServiceConnector is designed to solve.

For a deeper dive into AIDL with a working example,
see [this guide](https://github.com/sridhar-sp/android-playground/tree/main/AIDL).

## Setting up the AIDL interface and Service

### Server setup

* Take the following AIDL interface as an example:

```aidl
 package com.gandiva.aidl.remoteservices;
    
 interface SensorDataLoggerService {
     String getSpeedInKm();
     int getRPM();
 }
```

* The corresponding Service implementation looks like this:

```kotlin
class SensorDataLoggerServiceImpl : Service() {

    override fun onBind(intent: Intent): IBinder? {
        if (intent.action != SENSOR_DATA_LOGGER_BIND_ACTION) return null

        val binder = object : SensorDataLoggerService.Stub() {
            override fun getSpeedInKm(): String {
                TODO("Not yet implemented")
            }

            override fun getRPM(): Int {
                TODO("Not yet implemented")
            }
        }
        binder.linkToDeath(DeathRecipient { Log.d("SensorDataLoggerService", "**** Service died") }, 0)
        return binder
    }

    companion object {
        const val SENSOR_DATA_LOGGER_BIND_ACTION = "com.gandiva.aidl.server.action.BIND_SENSOR_DATA_LOGGER"
    }
}
```

### Client setup

* The client can try to bind the exposed service from the server app using explicit intent.
* Once the service is connected, we can typecast the IBinder instance to the AIDL interface type.

```kotlin
@HiltViewModel
class SensorDataLoggerViewModel @Inject constructor(val appContext: Application) : AndroidViewModel(appContext) {

    private var sensorDataLoggerService: SensorDataLoggerService? = null

    var isServiceConnected by mutableStateOf(false)
        private set

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            isServiceConnected = true
            sensorDataLoggerService = SensorDataLoggerService.Stub.asInterface(service)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isServiceConnected = false
        }
    }

    fun disconnectService() {
        appContext.applicationContext.unbindService(serviceConnection)
        isServiceConnected = false
    }

    // Should call this method before accessing [sensorDataLoggerService]
    fun connectToService(appContext: Context = this.getApplication()) {
        val bindIntent = Intent().apply {
            component = ComponentName(SENSOR_DATA_LOGGER_PKG_NAME, SENSOR_DATA_LOGGER_SERVICE_NAME)
            action = SENSOR_DATA_LOGGER_BIND_ACTION
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appContext.bindService(bindIntent, Context.BIND_AUTO_CREATE, appContext.mainExecutor, serviceConnection)
        } else {
            appContext.applicationContext.bindService(bindIntent, serviceConnection, Context.BIND_NOT_FOREGROUND)
        }
    }

    fun showSpeed() {
        // Assume connectToService is already called and onServiceConnected executed
        val speedInKm = sensorDataLoggerService?.getSpeedInKm() // Assume service is connected
        Toast.makeText(appContext, "Speed $speedInKm", Toast.LENGTH_SHORT).show()
    }

    fun showRpm() {
        // Assume connectToService is already called and onServiceConnected executed
        val rpm = sensorDataLoggerService?.getRPM() // Assume service is connected
        Toast.makeText(appContext, "RPM $rpm", Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val SENSOR_DATA_LOGGER_PKG_NAME = "com.gandiva.aidl.server"
        const val SENSOR_DATA_LOGGER_SERVICE_NAME = "com.gandiva.aidl.server.sensor.SensorDataLoggerServiceImpl"
        const val SENSOR_DATA_LOGGER_BIND_ACTION = "com.gandiva.aidl.server.action.BIND_SENSOR_DATA_LOGGER"
    }
}
```

### Problem in naive approach

* The code above requires explicitly calling connectToService before making any AIDL calls, and assumes the connection
  stays alive for the lifetime of the app.
* In practice this assumption breaks easily, the server can crash or be killed, leaving the cached binder instance
  obsolete and any subsequent calls silently failing or throwing.
* So every AIDL call needs a connection guard, check if connected, and if not, reconnect and wait before proceeding.
  The `showSpeed` method below shows what that looks like in practice.

```kotlin
 fun showSpeed() {
    if (isServiceConnected) {
        val speedInKm = sensorDataLoggerService?.getSpeedInKm()
        Toast.makeText(appContext, "Speed $speedInKm", Toast.LENGTH_SHORT).show()
    } else {
        connectToService() // Async operation
        // Wait for service to connect then call API
        // Some wait logic; like delay using handler etc
        Handler().postDelayed({
            // This code may not work; if the service was not connected within 2500 milliseconds.
            // Also if the service connected early, then still we need to wait till 2500 milliseconds to execute this.
            val speedInKm = sensorDataLoggerService?.getSpeedInKm()
            Toast.makeText(appContext, "Speed $speedInKm", Toast.LENGTH_SHORT).show()
        }, 2500)

    }
}
```

* Guarding every AIDL call this way works, but replicating it across the entire codebase introduces significant
  boilerplate. The better solution is to centralise that complexity, which is exactly what `ServiceConnector` does.

## Service connector

* ServiceConnector wraps the complexity of Android service binding into a single suspending `getService()` call. It
  re-establishes the connection automatically the next time `getService()` is called after a crash or disconnect, so
  your client code never has to think about connection state.
* If `getService(timeoutMs)` returns `null`, the service did not connect within the given time. You can call
  `getService()` again to retry.

### How to use Service connector

* Extend the ServiceConnector and provide necessary information about the service to which we want to connect and the
  type of the binder interface.

* `@param transformBinderToService` is the important callback function that bridges the raw IBinder Android gives you
  and the typed AIDL interface your code actually wants to work with. Typically this is just a one-liner wrapping
  `YourAidlInterface.Stub.asInterface(binder)`

```kotlin
class SensorDataLoggerServiceCoordinator(context: Context) : ServiceConnector<SensorDataLoggerService>(
    context = context, // Context used to bind the service.
    intent = bindIntent(), // Explicit intent describing the service to connect.
    transformBinderToService = { binder: IBinder? -> binder?.let { SensorDataLoggerService.Stub.asInterface(it) } },
    allowNullBinding = false // If true bind with service even if it returns a null IBinder object
) {

    companion object {
        private const val SENSOR_DATA_LOGGER_PKG_NAME = "com.gandiva.aidl.server"
        private const val SENSOR_DATA_LOGGER_SERVICE_NAME = "com.gandiva.aidl.server.sensor.SensorDataLoggerServiceImpl"
        private const val SENSOR_DATA_LOGGER_BIND_ACTION = "com.gandiva.aidl.server.action.BIND_SENSOR_DATA_LOGGER"

        fun bindIntent(): Intent {
            return Intent().apply {
                component = ComponentName(SENSOR_DATA_LOGGER_PKG_NAME, SENSOR_DATA_LOGGER_SERVICE_NAME)
                action = SENSOR_DATA_LOGGER_BIND_ACTION
            }
        }
    }
}
```

* Create an instance of SensorDataLoggerServiceCoordinator and use the `getService` method to obtain the binder
  instance and start calling method without worrying about connection or reconnection logic;

```kotlin
@HiltViewModel
class SensorDataLoggerViewModel @Inject constructor(val appContext: Application) : AndroidViewModel(appContext) {

    private val sensorDataLoggerServiceCoordinator: SensorDataLoggerServiceCoordinator by lazy {
        SensorDataLoggerServiceCoordinator(context = appContext)
    }

    fun showSpeed() {
        viewModelScope.launch {
            // Suspend till service gets connected.
            val speedInKm = sensorDataLoggerServiceCoordinator.getService()?.getSpeedInKm()
            Toast.makeText(appContext, "Speed $speedInKm", Toast.LENGTH_SHORT).show()
        }
    }

    fun showRPM() {
        viewModelScope.launch {
            // Suspend till service gets connected. or at max 1500 ms. which ever comes first.
            val rpm = sensorDataLoggerServiceCoordinator.getService(1500L)?.getRPM()
            Toast.makeText(appContext, "RPM $rpm", Toast.LENGTH_SHORT).show()
        }
    }

    fun observeServiceConnectionStatus() {
        viewModelScope.launch {
            // Observe for any status change in service connection independently
            sensorDataLoggerServiceCoordinator.serviceConnectionStatus().collect { status: ServiceConnectionStatus ->
                when (status) {
                    is ServiceConnectionStatus.Connected -> showConnectedUI()
                    is ServiceConnectionStatus.Disconnected -> showDisconnectedUI()
                    is ServiceConnectionStatus.BindingDied -> showReconnectingUI()
                    is ServiceConnectionStatus.NullBinding -> showErrorUI()
                    is ServiceConnectionStatus.BinderDied -> handleBinderDied(status.linkedBinder, status.diedBinder)
                    is ServiceConnectionStatus.None -> Unit // Initial state, no action needed
                }
            }
        }
    }

    fun disconnectService() {
        viewModelScope.launch { sensorDataLoggerServiceCoordinator.unbindService() }
    }
}
```