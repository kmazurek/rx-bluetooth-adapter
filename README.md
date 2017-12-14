# RxBluetoothAdapter

![bitrise_badge](https://www.bitrise.io/app/cb0a46f06c6a70d0/status.svg?token=j5genU1qthlnmppc-pNgsQ) [![](https://jitpack.io/v/zakaprov/rx-bluetooth-adapter.svg)](https://jitpack.io/#zakaprov/rx-bluetooth-adapter)

Reactive wrapper for Android's `BluetoothAdapter` class.

## Introduction

The idea of this library is to provide reactive bindings and extensions for [BluetoothAdapter](https://developer.android.com/reference/android/bluetooth/BluetoothAdapter.html).

The library was built with the following requirements in mind:

1. The entire public API should be based on RxJava stream types (i.e. `Observable`, `Single` and `Completable`).
2. Bluetooth preconditions (e.g. adapter enabled, permissions granted, device paired) should be checked eagerly on each qualifying call.
3. Any exceptions raised inside the library should be propagated downstream via Rx.

`RxBluetoothAdapter` is aimed specifically at the classic Bluetooth API (< 4.0), it does not cover Bluetooth Low Energy. For BLE, I recommend using the excellent [RxAndroidBle](https://github.com/Polidea/RxAndroidBle) library.

## Usage

### Initialisation
`RxBluetoothAdapter` is meant to be used as a single instance. You can use a dependency injection framework (e.g. [Dagger](http://google.github.io/dagger/) for Java or [Kodein](https://salomonbrys.github.io/Kodein/) for Kotlin) or keep a singleton somewhere in your application.

```kotlin
val adapter = RxBluetoothAdapter(context)
```

### Scanning for devices
The call to 'startDeviceScan' checks the required Bluetooth preconditions and either throws an error or continues with the scan, emitting discovered devices through the `Observable`.
Calling this method during an already ongoing scan is safe, the obtained `Observable` will not reemit devices which were discovered earlier during the scan.

```kotlin
val disposable = adapter.startDeviceScan()
    .subscribe(
        { device ->
            // Process next discovered device here
        },
        { error ->
            // Handle error here
        },
        {
            // Scan complete
        }
    )
```
