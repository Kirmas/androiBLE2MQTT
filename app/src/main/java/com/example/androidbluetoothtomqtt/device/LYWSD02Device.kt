package com.example.androidbluetoothtomqtt.device

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.os.Build
import android.util.Log
import com.example.androidbluetoothtomqtt.ServiceBluetoothToMQTT
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

class LYWSD02Device(connectedBTDevice: BluetoothDevice, serviceContext: ServiceBluetoothToMQTT) : XiaomiDevice(connectedBTDevice, serviceContext)  {
    private val TAG = "LYWSD02Device"
    private val SERVICE: UUID =  UUID.fromString("ebe0ccb0-7a0a-4b0c-8a1a-6ff2997da3a6")
    private val UUID_DATA: UUID =  UUID.fromString("ebe0ccc1-7a0a-4b0c-8a1a-6ff2997da3a6")
    private val UUID_BATTERY: UUID = UUID.fromString("ebe0ccc4-7a0a-4b0c-8a1a-6ff2997da3a6")

    private var humidity: Int = 0
    private var temperature: Float = 0.0f
    private var battery: Int = 100

    override fun created() {
        availableCharacteristics.add(AvailableCharacteristicInformation(SERVICE, UUID_DATA, CharacteristicAccessType.Notification))
        availableCharacteristics.add(AvailableCharacteristicInformation(SERVICE, UUID_BATTERY, CharacteristicAccessType.Read))
        super.created()
    }
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    override fun enableCharacteristicNotification(
        gatt: BluetoothGatt,
        service: BluetoothGattService,
        characteristicUUID: UUID
    ) {
        val characteristic = service.getCharacteristic(characteristicUUID)
        characteristic?.let {
            val descriptor =
                it.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
            if (descriptor != null) {
                gatt.setCharacteristicNotification(it, true)
                if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(
                        descriptor,
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    )
                }
                else
                {
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(descriptor)

                }
            }
        }
    }
    @SuppressLint("MissingPermission")
    override fun createPayload(): ByteArray {
        val payload = "{\"name\": \"${connectedBTDevice.name}\", \"temperature\": $temperature, \"humidity\": $humidity, \"battery\": $battery }"
        return payload.toByteArray()
    }

    override fun onCharacteristicChanged(
        characteristicUUID: UUID,
        value: ByteArray
    ) {
        if (characteristicUUID == UUID_DATA) {
            humidity = value[2].toInt()
            val temperatureBytes = value.copyOfRange(0, 2)
            temperature =
                ByteBuffer.wrap(temperatureBytes).order(ByteOrder.LITTLE_ENDIAN).short / 100.0f

            Log.d(TAG, "Temperature: $temperature, Humidity: $humidity")
        }
    }

    override fun onCharacteristicRead(
        characteristicUUID: UUID,
        value: ByteArray
    ) {
        if (characteristicUUID == UUID_BATTERY) {
            battery = value[0].toInt()
            Log.d(TAG, "Battery: $battery")
        }
    }
}