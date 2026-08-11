package com.gyromapper.core.controller

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log

private const val TAG = "UsbHidProbe"
private const val ACTION_USB_PERMISSION = "com.gyromapper.USB_PERMISSION"
private const val EIGHTBITDO_VENDOR_ID = 0x2DC8
private const val NINTENDO_VENDOR_ID = 0x057E

/** Throwaway diagnostic tool. list -> requestPermissionAndProbe -> watch logcat. */
class UsbHidProbe(private val context: Context) {

    private var connection: UsbDeviceConnection? = null
    private var iface: UsbInterface? = null
    private var inEndpoint: UsbEndpoint? = null
    private var outEndpoint: UsbEndpoint? = null
    @Volatile private var reading = false

    /**
     * Invoked on the background read thread for every raw report read off
     * the interrupt IN endpoint (not just the throttled ones this class
     * logs itself). [EightBitDoHidReader] hangs its report parsing off
     * this instead of duplicating the device-find/permission/claim/read
     * logic below - see the doc comment on that class for why.
     *
     * Each call gets a fresh copy of the report bytes, safe to hold onto
     * past the callback.
     */
    var onReport: ((bytes: ByteArray, length: Int) -> Unit)? = null

    fun listDevices(): List<UsbDevice> {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val devices = manager.deviceList.values.toList()
        devices.forEach {
            Log.i(TAG, "Found device: ${it.deviceName} vendorId=0x${it.vendorId.toString(16)} " +
                    "productId=0x${it.productId.toString(16)} ifaces=${it.interfaceCount}")
        }
        return devices
    }

    fun findEightBitDo(): UsbDevice? =
        listDevices().firstOrNull { it.vendorId == EIGHTBITDO_VENDOR_ID || it.vendorId == NINTENDO_VENDOR_ID }

    fun requestPermissionAndProbe(device: UsbDevice) {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (manager.hasPermission(device)) {
            startProbe(device)
            return
        }

        // --- FIX: use FLAG_IMMUTABLE (Android 14+ requires this for implicit intents) ---
        val permissionIntent = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == ACTION_USB_PERMISSION) {
                    context.unregisterReceiver(this)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    Log.i(TAG, "USB permission granted=$granted")
                    if (granted) startProbe(device)
                }
            }
        }
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        manager.requestPermission(device, permissionIntent)
    }

    private fun startProbe(device: UsbDevice) {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val conn = manager.openDevice(device)
        if (conn == null) {
            Log.e(TAG, "openDevice failed")
            return
        }
        connection = conn

        for (i in 0 until device.interfaceCount) {
            val ifc = device.getInterface(i)
            Log.i(TAG, "Interface $i: class=${ifc.interfaceClass} subclass=${ifc.interfaceSubclass} " +
                    "protocol=${ifc.interfaceProtocol} endpointCount=${ifc.endpointCount}")
            for (e in 0 until ifc.endpointCount) {
                val ep = ifc.getEndpoint(e)
                Log.i(TAG, "  Endpoint $e: addr=0x${ep.address.toString(16)} " +
                        "dir=${if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"} " +
                        "type=${ep.type} maxPacketSize=${ep.maxPacketSize}")
            }
        }

        for (i in 0 until device.interfaceCount) {
            val ifc = device.getInterface(i)
            val candidateIn = (0 until ifc.endpointCount)
                .map { ifc.getEndpoint(it) }
                .firstOrNull { it.direction == UsbConstants.USB_DIR_IN && it.type == UsbConstants.USB_ENDPOINT_XFER_INT }
            if (candidateIn != null) {
                if (!conn.claimInterface(ifc, true)) {
                    Log.e(TAG, "claimInterface FAILED on interface $i - something else may be holding it")
                    continue
                }
                iface = ifc
                inEndpoint = candidateIn
                outEndpoint = (0 until ifc.endpointCount)
                    .map { ifc.getEndpoint(it) }
                    .firstOrNull { it.direction == UsbConstants.USB_DIR_OUT && it.type == UsbConstants.USB_ENDPOINT_XFER_INT }
                Log.i(TAG, "Claimed interface $i, IN endpoint 0x${candidateIn.address.toString(16)}, " +
                        "outEndpoint=${outEndpoint?.address?.toString(16) ?: "none (will use control transfer)"}")
                break
            }
        }

        if (inEndpoint == null) {
            Log.e(TAG, "No claimable interrupt IN endpoint - claimInterface() may be blocked, " +
                    "or Android's stock joystick driver already has this device")
            return
        }

        iface?.let { dumpReportDescriptor(conn, it.id) }
        tryEnableFullReportAndImu(conn, device)
        startReadLoop(conn)
    }

    private fun dumpReportDescriptor(conn: UsbDeviceConnection, interfaceNumber: Int) {
        val buffer = ByteArray(512)
        val len = conn.controlTransfer(0x81, 0x06, (0x22 shl 8), interfaceNumber, buffer, buffer.size, 500)
        if (len > 0) {
            val hex = buffer.take(len).joinToString(" ") { "%02X".format(it) }
            Log.i(TAG, "HID Report Descriptor ($len bytes): $hex")
        } else {
            Log.w(TAG, "Failed to read report descriptor, result=$len")
        }
    }

    private fun tryEnableFullReportAndImu(conn: UsbDeviceConnection, device: UsbDevice) {
        // Report ID 0x01 + subcommands 0x03/0x30 (set full input report mode)
        // and 0x40/0x01 (enable the 6-axis IMU) are Nintendo's Joy-Con/Switch
        // Pro Controller protocol, not a generic HID gyro-enable sequence.
        // They only mean anything to a device that is actually speaking that
        // protocol - i.e. the 8BitDo while it's emulating a Switch Pro
        // Controller (vendorId == NINTENDO_VENDOR_ID). Sending them to the
        // 8BitDo's own D-Input/X-Input HID mode (vendorId ==
        // EIGHTBITDO_VENDOR_ID) has no reason to do anything useful - that
        // mode has no reason to interpret output report 0x01 as a Switch
        // subcommand frame. This was previously sent unconditionally to
        // whichever vendor matched, which is a real bug: it means IMU-enable
        // silently did nothing whenever the controller was found via its own
        // 8BitDo vendor ID rather than in Switch-emulation mode.
        if (device.vendorId != NINTENDO_VENDOR_ID) {
            Log.i(TAG, "Vendor 0x${device.vendorId.toString(16)} is not the Switch-protocol " +
                    "vendor - skipping Switch subcommands, reading reports as-is")
            return
        }

        val rumbleNeutral = byteArrayOf(0x00, 0x01, 0x40, 0x40, 0x00, 0x01, 0x40, 0x40)

        fun sendSubcommand(subcommand: Int, arg: Int) {
            val report = ByteArray(12)
            report[0] = 0x01
            report[1] = 0x00
            rumbleNeutral.copyInto(report, 2)
            report[10] = subcommand.toByte()
            report[11] = arg.toByte()

            val ep = outEndpoint
            val result = if (ep != null) {
                conn.bulkTransfer(ep, report, report.size, 200)
            } else {
                conn.controlTransfer(
                    0x21, 0x09, 0x0200 or (report[0].toInt() and 0xFF), iface?.id ?: 0,
                    report, report.size, 200
                )
            }
            Log.i(TAG, "Sent subcommand 0x${subcommand.toString(16)} arg 0x${arg.toString(16)}, result=$result")
        }

        sendSubcommand(0x03, 0x30)
        sendSubcommand(0x40, 0x01)
    }

    private fun startReadLoop(conn: UsbDeviceConnection) {
        val ep = inEndpoint ?: return
        reading = true
        Thread {
            val buffer = ByteArray(ep.maxPacketSize)
            var count = 0
            while (reading) {
                val read = conn.bulkTransfer(ep, buffer, buffer.size, 500)
                if (read > 0) {
                    count++
                    if (count % 10 == 0) {
                        val hex = buffer.take(read).joinToString(" ") { "%02X".format(it) }
                        Log.i(TAG, "report[$read bytes]: $hex")
                    }
                    onReport?.invoke(buffer.copyOf(read), read)
                } else if (read < 0) {
                    Log.w(TAG, "bulkTransfer returned $read")
                }
            }
        }.start()
    }

    fun stop() {
        reading = false
        iface?.let { connection?.releaseInterface(it) }
        connection?.close()
    }

    fun isReading(): Boolean = reading
}