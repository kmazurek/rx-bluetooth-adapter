# RxBluetoothAdapter

![bitrise_badge](https://www.bitrise.io/app/cb0a46f06c6a70d0/status.svg?token=j5genU1qthlnmppc-pNgsQ) [![](https://jitpack.io/v/zakaprov/rx-bluetooth-adapter.svg)](https://jitpack.io/#zakaprov/rx-bluetooth-adapter)

Reactive wrapper for Android's `BluetoothAdapter` class.

## Introduction

The idea of this library is to provide reactive bindings and extensions for [BluetoothAdapter](https://developer.android.com/reference/android/bluetooth/BluetoothAdapter.html).

The library was built with the following requirements in mind:

1. The entire public API should be based on RxJava stream types (i.e. [Observable](http://reactivex.io/RxJava/2.x/javadoc/io/reactivex/Observable.html), [Single](http://reactivex.io/RxJava/javadoc/io/reactivex/Single.html) and [Completable](http://reactivex.io/RxJava/2.x/javadoc/io/reactivex/Completable.html)).
2. Bluetooth preconditions (e.g. adapter enabled, permissions granted, device paired) should be checked eagerly on every call which requires them.
3. Any exceptions raised inside the library should be propagated downstream via Rx.

`RxBluetoothAdapter` is aimed specifically at the classic Bluetooth API (< 4.0), it does not cover Bluetooth Low Energy. For BLE, I recommend using the excellent [RxAndroidBle](https://github.com/Polidea/RxAndroidBle) library.

## Usage

### Initialisation
`RxBluetoothAdapter` is meant to be used as a single instance. You can use a dependency injection framework (e.g. [Dagger](http://google.github.io/dagger/) for Java or [Kodein](https://salomonbrys.github.io/Kodein/) for Kotlin) or keep a singleton somewhere in your application.

```kotlin
val adapter = RxBluetoothAdapter(context)
```

### Scanning for devices
We can initiate a scan for nearby Bluetooth devices by calling `startDeviceScan` on the adapter object. This call checks the required Bluetooth preconditions and either throws an error or continues with the scan, emitting discovered devices through the returned `Observable`.
Calling this method during an already ongoing scan is safe, the obtained `Observable` will not reemit devices which were discovered earlier during the scan.

```kotlin
adapter.startDeviceScan()
    .subscribe({ device ->
            // Process next discovered device
        }, { error ->
            // Handle error
        }, {
            // Scan complete
        }
    )
```

### Device pairing
Having a `BluetoothDevice` object (e.g. obtained from a device scan), we can use the adapter's method `pairDevice` to start the pairing (bonding) process with that device.
The resulting `Single` returns `true` when pairing is successful (or the device is already paired with) and `false` when the process gets cancelled.

```kotlin
adapter.pairDevice(bluetoothDevice)
    .subscribe({ result ->
            // Process pairing result
        }, { error ->
            // Handle error
        }
    )
```

We can also query the adapter for already paired devices by accessing the field `pairedDevices`:

```kotlin
adapter.pairedDevices()
    .subscribe({ device ->
            // Do something with the paired device
        }, { error ->
            // Handle error
        }
    )
```

### Connecting to a device
One of the main goals for this library was to provide a simple way of establishing serial port (SPP) connections to Bluetooth devices. Here's how we can connect to a remote `BluetoothDevice` using `RxBluetoothAdapter`:

```kotlin
adapter.connectToDevice(bluetoothDevice)
    .subscribe({ socket ->
        // Connection successful, save and/or use the obtained socket object
    }, { error ->
        // Connection failed, handle the error
    })
```

After obtaining a [BluetoothSocket](https://developer.android.com/reference/android/bluetooth/BluetoothSocket.html) we can use it for two-way communication with the remote device. The communication itself is not part of the library, since the actual implementation will vary depending on the use case.

### Observing events
`RxBluetoothAdapter` includes a reactive stream for monitoring the connection status of remote devices:

```kotlin
adapter.connectionEventStream
    .subscribe({ (state, device) ->
        when (state) {
            ConnectionState.CONNECTED -> Log.d("tag", "${device.address} - connected")
            ConnectionState.CONNECTING -> Log.d("tag", "${device.address} - connecting")
            ConnectionState.DISCONNECTED -> Log.d("tag", "${device.address} - disconnected")
        }
    })
```

This stream is based on broadcasts from the operating system (thanks to [RxBroadcast](https://github.com/cantrowitz/RxBroadcast)). Certain events can also be emitted by `RxBluetoothAdapter`, e.g. `ConnectionState.CONNECTED` will be sent whenever a call to `connectToDevice` succeeds.

There is also a separate stream which can be used to watch the status of device scanning (i.e. if there is currently a device discovery in progress):

```kotlin
adapter.scanStateStream
    .subscribe({ isScanning ->
        // Do something depending on the state
    })
```

Both these streams are implemented using [ReplaySubject](http://reactivex.io/RxJava/javadoc/io/reactivex/subjects/ReplaySubject.html)s and will replay the last reported value to any new subscribers.

## Examples


## Installation
This library is available through [JitPack](https://jitpack.io/). To use it, first add the JitPack Maven repository to your **top level** `build.gradle` like in the below example:

```
allprojects {
    repositories {
        . . .
        maven { url 'https://jitpack.io' }
    }
}
```

Once the repository is added, just add the following line to your application's `build.gradle` under `dependencies`:

`implementation 'com.github.zakaprov:rx-bluetooth-adapter:1.1.0'`
