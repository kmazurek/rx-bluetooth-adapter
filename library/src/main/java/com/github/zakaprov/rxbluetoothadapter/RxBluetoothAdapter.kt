package com.github.zakaprov.rxbluetoothadapter

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.support.v4.content.ContextCompat
import com.cantrowitz.rxbroadcast.RxBroadcast
import com.github.zakaprov.rxbluetoothadapter.error.BluetoothDisabledError
import com.github.zakaprov.rxbluetoothadapter.error.BluetoothPairingError
import com.github.zakaprov.rxbluetoothadapter.error.BluetoothPermissionError
import com.github.zakaprov.rxbluetoothadapter.error.BluetoothUnsupportedError
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import io.reactivex.rxkotlin.toObservable
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.ReplaySubject
import java.io.IOException
import java.util.UUID

enum class ConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED
}

class RxBluetoothAdapter(private val appContext: Context) {

    val pairedDevices: Observable<BluetoothDevice>
        get() = if (adapter == null) Observable.empty() else adapter.bondedDevices.toObservable()

    /**
     * Returns a stream of changes to the connection state of remote Bluetooth devices. Each emitted item is a [Pair]
     * containing a [BluetoothDevice] object and its latest [ConnectionState] value.
     * Once subscribed to, the stream will replay its last emitted value, allowing for checking the last reported state
     * of a device.
     *
     * @return an [Observable] emitting changes to the connection state to a given Bluetooth device.
     */
    val connectionEventStream: Observable<Pair<ConnectionState, BluetoothDevice>>
        get() {
            if (connectionStateDisposable.size() == 0) {
                initConnectionEventStream()
            }

            return connectionStateSubject
        }

    /**
     * Gets the stream of values representing the current state of Bluetooth device discovery.
     *
     * @return an [Observable] emitting Boolean values indicating whether there's an ongoing Bluetooth device scan.
     */
    val scanStateStream: Observable<Boolean>
        get() = discoveryStateSubject

    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    private val sppUuid = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    private val requiredPermissions = listOf(
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.BLUETOOTH,
        Manifest.permission.BLUETOOTH_ADMIN
    )
    private val requiredScanPermissions = listOf(
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    private var deviceScanSubject: PublishSubject<BluetoothDevice> = PublishSubject.create()
    private var discoveryStateSubject: ReplaySubject<Boolean> = ReplaySubject.createWithSize(1)
    private val scanCompositeDisposable = CompositeDisposable()

    private var connectionStateSubject: ReplaySubject<Pair<ConnectionState, BluetoothDevice>> = ReplaySubject.createWithSize(1)
    private val connectionStateDisposable = CompositeDisposable()

    /**
     * Attempts to establish an SPP (serial port) connection to a remote Bluetooth server represented by the argument device.
     * If the local and remote devices are not paired, this will start the pairing process before attempting a connection.
     *
     * @param device the Bluetooth device to connect to.
     *
     * @return a [Single] which returns the [BluetoothSocket] in case of a successful connection.
     *
     * @throws BluetoothPairingError when the pairing process fails.
     * @throws IOException when the connection to the remote device fails.
     *
     * @throws BluetoothDisabledError if Bluetooth is disabled on the device.
     * @throws BluetoothPermissionError if the app is lacking required permissions.
     * @throws BluetoothUnsupportedError if Bluetooth is unsupported on the device.
     */
    fun connectToDevice(device: BluetoothDevice): Single<BluetoothSocket> {
        return assertConditions()
            .andThen(pairDevice(device))
            .flatMap {
                if (it) {
                    Single.fromCallable {
                        connectionStateSubject.onNext(Pair(ConnectionState.CONNECTING, device))
                        val socket = device.createRfcommSocketToServiceRecord(sppUuid)
                        socket.connect()
                        socket
                    }
                } else {
                    connectionStateSubject.onNext(Pair(ConnectionState.DISCONNECTED, device))
                    Single.error<BluetoothSocket>(BluetoothPairingError())
                }
            }
            .doOnSuccess { connectionStateSubject.onNext(Pair(ConnectionState.CONNECTED, device)) }
            .doOnError { connectionStateSubject.onNext(Pair(ConnectionState.DISCONNECTED, device)) }
    }

    /**
     * Starts the Bluetooth pairing (bonding) process with the Bluetooth device given as the argument.
     *
     * @param device the Bluetooth device to pair with.
     *
     * @return a [Single] which returns true if the pairing process was successful or false if it failed or was cancelled.
     *
     * @throws BluetoothDisabledError if Bluetooth is disabled on the device.
     * @throws BluetoothPermissionError if the app is lacking required permissions.
     * @throws BluetoothUnsupportedError if Bluetooth is unsupported on the device.
     */
    fun pairDevice(device: BluetoothDevice): Single<Boolean> {
        if (device.isPaired() == true) {
            return Single.just(true)
        }

        return assertConditions()
            .andThen { device.createBond() }
            .andThen(
                RxBroadcast
                    .fromBroadcast(appContext, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
                    .map { it.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDING) }
                    .filter { it != BluetoothDevice.BOND_BONDING }
                    .map { it == BluetoothDevice.BOND_BONDED }
                    .take(1)
                    .first(false)
            )
    }

    /**
     * Initialises a Bluetooth scan (discovery) for nearby available devices. Calling this method during
     * an already ongoing discovery scan is safe and is backed by one data source.
     *
     * @return an [Observable] emitting devices discovered during the scan.
     *
     * @throws BluetoothDisabledError if Bluetooth is disabled on the device.
     * @throws BluetoothPermissionError if the app is lacking required permissions.
     * @throws BluetoothUnsupportedError if Bluetooth is unsupported on the device.
     */
    fun startDeviceScan(): Observable<BluetoothDevice> {
        startDiscovery()
        return assertConditions(true)
            .andThen(deviceScanSubject)
    }

    private fun assertConditions(checkScanPermissions: Boolean = false): Completable {
        return Completable.fromAction {
            if (adapter == null) {
                throw BluetoothUnsupportedError()
            } else if (!adapter.isEnabled) {
                throw BluetoothDisabledError()
            }

            val permissions = requiredPermissions.toMutableList()
            if (checkScanPermissions) {
                permissions.addAll(requiredScanPermissions)
            }

            val missingPermissions = permissions.filter {
                permission -> ContextCompat.checkSelfPermission(appContext, permission) ==
                PackageManager.PERMISSION_DENIED
            }.toMutableList()

            if (!missingPermissions.isEmpty()) {
                throw BluetoothPermissionError(missingPermissions)
            }
        }
    }

    private fun initConnectionEventStream() {
        RxBroadcast
            .fromBroadcast(appContext, IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED))
            .subscribe {
                val device = it.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                connectionStateSubject.onNext(Pair(ConnectionState.CONNECTED, device))
            }
            .addTo(connectionStateDisposable)

        RxBroadcast
            .fromBroadcast(appContext, IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED))
            .subscribe {
                val device = it.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                connectionStateSubject.onNext(Pair(ConnectionState.DISCONNECTED, device))
            }
            .addTo(connectionStateDisposable)
    }

    private fun startDiscovery() {
        if (adapter?.isDiscovering == true) {
            return
        }

        if (scanCompositeDisposable.size() == 0) {
            RxBroadcast
                .fromBroadcast(appContext, IntentFilter(BluetoothDevice.ACTION_FOUND))
                .map {
                    val device: BluetoothDevice = it.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    device
                }.subscribe { deviceScanSubject.onNext(it) }
                .addTo(scanCompositeDisposable)

            RxBroadcast
                .fromBroadcast(appContext, IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_STARTED))
                .subscribe {
                    discoveryStateSubject.onNext(true)
                }
                .addTo(scanCompositeDisposable)

            RxBroadcast
                .fromBroadcast(appContext, IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_FINISHED))
                .subscribe { stopDiscovery() }
                .addTo(scanCompositeDisposable)
        }

        adapter?.startDiscovery()
    }

    private fun stopDiscovery() {
        deviceScanSubject.onComplete()
        deviceScanSubject = PublishSubject.create()
        discoveryStateSubject.onNext(false)
    }

    private fun BluetoothDevice.isPaired() = adapter?.bondedDevices?.contains(this)
}
