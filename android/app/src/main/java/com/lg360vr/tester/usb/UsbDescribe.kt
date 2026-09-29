package com.lg360vr.tester.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface

/** Snapshot of the USB descriptor tree, used by the "USB Info" screen. */
data class DeviceDescriptor(
    val deviceName: String,
    val manufacturer: String?,
    val product: String?,
    val serial: String?,
    val vendorId: Int,
    val productId: Int,
    val usbClass: Int,
    val deviceClassName: String,
    val version: String,
    val interfaces: List<InterfaceInfo>,
) {
    val vidHex get() = "0x%04X".format(vendorId)
    val pidHex get() = "0x%04X".format(productId)
}

data class InterfaceInfo(
    val id: Int,
    val alternateSetting: Int,
    val ifaceClass: Int,
    val className: String,
    val endpoints: List<EndpointInfo>,
)

data class EndpointInfo(
    val address: Int,
    val direction: String,
    val type: String,
    val maxPacketSize: Int,
    val interval: Int,
) {
    val addrHex get() = "0x%02X".format(address)
}

object UsbDescribe {

    fun describe(device: UsbDevice, serial: String?): DeviceDescriptor {
        val interfaces = (0 until device.interfaceCount).map { i ->
            describeInterface(device.getInterface(i))
        }
        return DeviceDescriptor(
            deviceName = device.deviceName,
            manufacturer = device.manufacturerName,
            product = device.productName,
            serial = serial ?: device.serialNumber,
            vendorId = device.vendorId,
            productId = device.productId,
            usbClass = device.deviceClass,
            deviceClassName = className(device.deviceClass),
            version = device.version,
            interfaces = interfaces,
        )
    }

    private fun describeInterface(iface: UsbInterface): InterfaceInfo {
        val eps = (0 until iface.endpointCount).map { j ->
            describeEndpoint(iface.getEndpoint(j))
        }
        return InterfaceInfo(
            id = iface.id,
            alternateSetting = iface.alternateSetting,
            ifaceClass = iface.interfaceClass,
            className = className(iface.interfaceClass),
            endpoints = eps,
        )
    }

    private fun describeEndpoint(ep: UsbEndpoint): EndpointInfo {
        val dir = if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
        val type = when (ep.type) {
            UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "control"
            UsbConstants.USB_ENDPOINT_XFER_ISOC -> "isochronous"
            UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"
            UsbConstants.USB_ENDPOINT_XFER_INT -> "interrupt"
            else -> "unknown"
        }
        return EndpointInfo(
            address = ep.address,
            direction = dir,
            type = type,
            maxPacketSize = ep.maxPacketSize,
            interval = ep.interval,
        )
    }

    fun className(cls: Int): String = when (cls) {
        UsbConstants.USB_CLASS_PER_INTERFACE -> "Per-interface"
        UsbConstants.USB_CLASS_AUDIO -> "Audio"
        UsbConstants.USB_CLASS_COMM -> "Comm/CDC"
        UsbConstants.USB_CLASS_HID -> "HID"
        UsbConstants.USB_CLASS_PHYSICA -> "Physical"
        UsbConstants.USB_CLASS_STILL_IMAGE -> "Still Image"
        UsbConstants.USB_CLASS_PRINTER -> "Printer"
        UsbConstants.USB_CLASS_MASS_STORAGE -> "Mass Storage"
        UsbConstants.USB_CLASS_HUB -> "Hub"
        UsbConstants.USB_CLASS_CDC_DATA -> "CDC Data"
        UsbConstants.USB_CLASS_CSCID -> "Smart Card"
        UsbConstants.USB_CLASS_CONTENT_SEC -> "Content Security"
        UsbConstants.USB_CLASS_VIDEO -> "Video"
        UsbConstants.USB_CLASS_WIRELESS_CONTROLLER -> "Wireless"
        UsbConstants.USB_CLASS_MISC -> "Misc"
        UsbConstants.USB_CLASS_APP_SPEC -> "App-specific"
        UsbConstants.USB_CLASS_VENDOR_SPEC -> "Vendor-specific"
        else -> "Class 0x%02X".format(cls)
    }
}
