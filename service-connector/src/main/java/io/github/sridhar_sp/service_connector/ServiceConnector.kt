package io.github.sridhar_sp.service_connector

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

interface IServiceConnector<T> {

    /**
     *
     * Connect to the service and return the service binder instance or null.
     *
     * @param timeOutInMillis Maximum time to wait for the service to get connected,
     * returns null if service is not connected within the time.
     *
     * If null is returned, which means the service did not connect within the given time. you can call `getService()` again to retry
     *
     * If the [timeOutInMillis] is negative then [getService] will suspend until the service gets connected.
     */
    suspend fun getService(timeOutInMillis: Long = -1): T?

    fun unbindService()

    /**
     * @return a Flow<ServiceConnectionStatus> that emits whenever the connection state between your client
     * and the remote service changes. This is useful when your UI or business logic needs to react to the service
     * lifecycle.
     *
     * For example, disabling a button when the service disconnects, or showing a reconnecting indicator when the
     * binding dies.
     */
    fun serviceConnectionStatus(): Flow<ServiceConnectionStatus>

    sealed class ServiceConnectionStatus {

        /**
         * The service has successfully bound and the binder is available.
         */
        object Connected : ServiceConnectionStatus()

        /**
         * The connection to the service was lost, typically because the remote process crashed or was killed.
         */
        object Disconnected : ServiceConnectionStatus()

        /**
         * The service returned a `null` binder from `onBind`.
         * If allowNullBinding is false (the default), this is treated as a failed connection.
         */
        object NullBinding : ServiceConnectionStatus()

        /**
         * The binding itself has died and will not reconnect automatically. You should call `getService()` again to
         * re-establish the connection.
         *
         * @see [ServiceConnection.onBindingDied]
         */
        object BindingDied : ServiceConnectionStatus()

        /**
         * The underlying `IBinder` object died, reported via the `DeathRecipient` callback. Carries both the
         * `linkedBinder` (the binder you originally received) and `diedBinder` (the one that died, if reported by the system).
         */
        data class BinderDied(val linkedBinder: IBinder, val diedBinder: IBinder?) : ServiceConnectionStatus()
    }
}

/**
 *
 * Example
 *
 * ```
 * // AIDLInterface is the generated interface from AIDLInterface.aidl, which is returned as Binder from onBind method
 *
 * class FooServiceCoordinator(context: Context) : ServiceConnector<AIDLInterface>(
 *     context = context,
 *     intent = Intent().apply { TODO("Add component and action") },
 *     transformBinderToService = { binder: IBinder? -> binder?.let { AIDLInterface.Stub.asInterface(it) } },
 *     allowNullBinding = false
 * )
 * ```
 *
 * @param context Context used to bind the service.
 * @param intent Explicit intent describing the service to connect.
 * @param transformBinderToService a callback that bridges the raw IBinder Android gives you and the typed AIDL
 * interface your code actually wants to work with. Typically this is just a one-liner wrapping `YourAidlInterface.Stub.asInterface(binder)`
 * @param allowNullBinding Pass true to indicate to keep the server connected even if the server returns a null IBinder instance from the onBind method.
 */
open class ServiceConnector<T>(
    private val context: Context,
    private val intent: Intent,
    val transformBinderToService: (service: IBinder?) -> T?,
    private val allowNullBinding: Boolean = false,
) : IServiceConnector<T> {

    private var serviceConnected = false

    private var service: T? = null

    private val mutex = Mutex()

    private var lastServiceConnection: ServiceConnection? = null

    private var _serviceConnectionStatusFlow: MutableSharedFlow<IServiceConnector.ServiceConnectionStatus> =
        MutableSharedFlow(replay = 0, extraBufferCapacity = 64)

    private val serviceConnectionStatusFlow = _serviceConnectionStatusFlow.asSharedFlow()

    private val logTag = "SC:${this.javaClass.simpleName}"

    override fun serviceConnectionStatus(): Flow<IServiceConnector.ServiceConnectionStatus> =
        serviceConnectionStatusFlow

    private val serialScope = CoroutineScope(Executors.newSingleThreadExecutor().asCoroutineDispatcher())

    private var currentBinder: IBinder? = null
    private var currentDeathRecipient: IBinder.DeathRecipient? = null

    override suspend fun getService(timeOutInMillis: Long): T? {
        // If allowNullBinding is true don't care what service object is
        if (serviceConnected && (allowNullBinding || service != null)) {
            return service
        }

        if (timeOutInMillis < 0) return mutex.withLock { bindAndGetService() }
        return mutex.withLock { withTimeoutOrNull(timeOutInMillis.milliseconds) { bindAndGetService() } }
    }

    private suspend fun bindAndGetService() = suspendCancellableCoroutine { continuation ->
        if (lastServiceConnection != null) unbindService()

        val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                logD("service connected binder $binder. continuation.isActive ${continuation.isActive}")

                binder?.let { iBinder ->
                    if (continuation.isActive || (currentBinder == null && currentDeathRecipient == null)) {
                        currentBinder = iBinder
                        val deathRecipient = DeathRecipientImpl(binder)
                        currentDeathRecipient = deathRecipient
                        iBinder.linkToDeath(deathRecipient, 0)
                    } else
                        logD("Skip linkToDeath: continuation inactive or death recipient already registered")
                }

                resumeWithServiceInstance(binder)
                emitServiceConnectionStatus(IServiceConnector.ServiceConnectionStatus.Connected)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                cleanUpAndResumeIfRequired()
                logD("service disconnected. name : $name")

                emitServiceConnectionStatus(IServiceConnector.ServiceConnectionStatus.Disconnected)
            }

            override fun onBindingDied(name: ComponentName?) {
                cleanUpAndResumeIfRequired()
                logD("service onBindingDied. name $name")
                tryToUnlinkDeathRecipient()
                emitServiceConnectionStatus(IServiceConnector.ServiceConnectionStatus.BindingDied)
            }

            override fun onNullBinding(name: ComponentName?) {
                if (allowNullBinding) resumeWithServiceInstance(null)
                else cleanUpAndResumeIfRequired()

                logD("service onNullBinding. name $name")
                emitServiceConnectionStatus(IServiceConnector.ServiceConnectionStatus.NullBinding)
            }

            private fun resumeWithServiceInstance(binder: IBinder?) {
                service = transformBinderToService(binder)
                serviceConnected = true
                if (continuation.isActive) continuation.resume(service)
            }

            private fun cleanUpAndResumeIfRequired() {
                service = null
                serviceConnected = false
                if (continuation.isActive) continuation.resume(null)
            }

            private fun emitServiceConnectionStatus(status: IServiceConnector.ServiceConnectionStatus) {
                serialScope.launch {
                    serialScope.launch { _serviceConnectionStatusFlow.emit(status) }
                }
            }
        }

        logD("Initiating bind service connection")
        val status = context.bindService(
            intent, serviceConnection, Context.BIND_AUTO_CREATE
        )

        if (!status) {
            Log.e(logTag, "bindService failed, please check the intent provided.")
            if (continuation.isActive) continuation.resume(null)
        }

        lastServiceConnection = serviceConnection
    }

    internal fun tryToUnlinkDeathRecipient(whoDied: IBinder? = null) {
        try {
            logD("Trying to unlink death recipient binder $currentBinder prevDR $currentDeathRecipient")
            if (currentDeathRecipient != null && currentBinder != null) {
                val result = currentBinder?.unlinkToDeath(currentDeathRecipient!!, 0)
                logD("Unlink status $result")
            }
            if (whoDied != null && whoDied != currentBinder) {
                val result = whoDied.unlinkToDeath(currentDeathRecipient!!, 0)
                logD("Binder mismatch: died=$whoDied current=$currentBinder, unlink status=$result")
            }
            currentBinder = null
            currentDeathRecipient = null
        } catch (e: Exception) {
            Log.e(logTag, "unlink failed, Check logs", e)
        }
    }

    override fun unbindService() {
        try {
            lastServiceConnection?.let(context::unbindService)
            serviceConnected = false
            service = null
            logD("unbindService service connection is $lastServiceConnection")
        } catch (e: Exception) {
            Log.e(logTag, "unbindService failed, Check logs", e)
        }
    }

    private inner class DeathRecipientImpl(private val linkedBinder: IBinder) : IBinder.DeathRecipient {
        override fun binderDied() {
            onBinderDied()
        }

        override fun binderDied(who: IBinder) {
            onBinderDied(who)
        }

        private fun onBinderDied(who: IBinder? = null) {
            logD("binderDied who $who :: called from ${Thread.currentThread()}")
            tryToUnlinkDeathRecipient(who)
            serialScope.launch {
                _serviceConnectionStatusFlow.emit(
                    IServiceConnector.ServiceConnectionStatus.BinderDied(
                        linkedBinder = linkedBinder, diedBinder = who
                    )
                )
            }
        }
    }

    internal fun logD(log: String) {
        Log.d(logTag, log)
    }
}