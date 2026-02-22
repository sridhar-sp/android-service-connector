package io.github.sridhar_sp.service_connector

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import io.github.sridhar_sp.service_connector.IServiceConnector.ServiceConnectionStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

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

    suspend fun unbindService()

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
         * The initial state before any connection attempt has been made.
         */
        object None : ServiceConnectionStatus()

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
 * @param eventDispatcher CoroutineDispatcher used to dispatch the [ServiceConnectionStatus] on.
 */
open class ServiceConnector<T>(
    private val context: Context,
    private val intent: Intent,
    val transformBinderToService: (service: IBinder?) -> T?,
    private val allowNullBinding: Boolean = false,
    private val eventDispatcher: CoroutineDispatcher = Dispatchers.IO
) : IServiceConnector<T> {

    private var serviceConnected = false

    private var service: T? = null

    private val mutex = Mutex()

    private var lastServiceConnection: ServiceConnection? = null

    private var _serviceConnectionStatusFlow: MutableStateFlow<IServiceConnector.ServiceConnectionStatus> =
        MutableStateFlow(IServiceConnector.ServiceConnectionStatus.None)

    private val serviceConnectionStatusFlow = _serviceConnectionStatusFlow.asStateFlow()

    private val logTag = "SC:${this.javaClass.simpleName}"

    override fun serviceConnectionStatus(): Flow<IServiceConnector.ServiceConnectionStatus> =
        serviceConnectionStatusFlow

    private val eventDispatcherScope = CoroutineScope(eventDispatcher)

    override suspend fun getService(timeOutInMillis: Long): T? {
        // If allowNullBinding is true don't care what service object is
        if (serviceConnected && (allowNullBinding || service != null)) {
            return service
        }

        if (timeOutInMillis < 0) return mutex.withLock { bindAndGetService() }
        return mutex.withLock { withTimeoutOrNull(timeOutInMillis) { bindAndGetService() } }
    }

    private suspend fun bindAndGetService() = suspendCancellableCoroutine { continuation ->
        val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                resumeWithServiceInstance(binder)
                logD("service connected binder $binder")

                binder?.linkToDeath(DeathRecipientImpl(binder), 0)

                eventDispatcherScope.launch { _serviceConnectionStatusFlow.emit(IServiceConnector.ServiceConnectionStatus.Connected) }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                cleanUpAndResumeIfRequired()
                logD("service disconnected. name : $name")
                eventDispatcherScope.launch { _serviceConnectionStatusFlow.emit(IServiceConnector.ServiceConnectionStatus.Disconnected) }
            }

            override fun onBindingDied(name: ComponentName?) {
                cleanUpAndResumeIfRequired()
                logD("service onBindingDied. name $name")
                eventDispatcherScope.launch { _serviceConnectionStatusFlow.emit(IServiceConnector.ServiceConnectionStatus.BindingDied) }
            }

            override fun onNullBinding(name: ComponentName?) {
                if (allowNullBinding) resumeWithServiceInstance(null)
                else cleanUpAndResumeIfRequired()

                logD("service onNullBinding. name $name")
                eventDispatcherScope.launch { _serviceConnectionStatusFlow.emit(IServiceConnector.ServiceConnectionStatus.NullBinding) }
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

    @Throws(Exception::class)
    override suspend fun unbindService() {
        lastServiceConnection?.let(context::unbindService)
        serviceConnected = false
        service = null
        logD("unbindService service connection is $lastServiceConnection")
    }

    private inner class DeathRecipientImpl(private val linkedBinder: IBinder) : IBinder.DeathRecipient {
        override fun binderDied() {
            onBinderDied()
        }

        override fun binderDied(who: IBinder) {
            onBinderDied(who)
        }

        private fun onBinderDied(who: IBinder? = null) {
            logD("binderDied who $who")
            eventDispatcherScope.launch {
                _serviceConnectionStatusFlow.emit(
                    IServiceConnector.ServiceConnectionStatus.BinderDied(
                        linkedBinder = linkedBinder,
                        diedBinder = who
                    )
                )
            }
        }
    }

    private fun logD(log: String) {
        Log.d(logTag, log)
    }
}