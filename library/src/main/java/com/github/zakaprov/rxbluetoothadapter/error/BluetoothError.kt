package com.github.zakaprov.rxbluetoothadapter.error

sealed class BluetoothError(message: String?) : Throwable(message, null)

class BluetoothDisabledError : BluetoothError("Bluetooth adapter disabled.")
class BluetoothPairingError : BluetoothError("Bluetooth pairing process failed.")
class BluetoothSocketConnectionError : BluetoothError("Bluetooth socket connection failed.")
class BluetoothUnsupportedError : BluetoothError("Bluetooth not supported by the device.")

data class BluetoothPermissionError(private val missingPermissions: List<String>) :
    BluetoothError("Bluetooth permissions missing: $missingPermissions.")
