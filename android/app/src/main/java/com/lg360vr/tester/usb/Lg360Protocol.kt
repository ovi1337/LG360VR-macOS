package com.lg360vr.tester.usb

/**
 * LG 360 VR (LGR100AT) HID protocol.
 *
 * Reverse-engineered from bauermaximilian/LG-360-VR-for-PC and confirmed on the
 * device firmware (LGR100AT-...dfu). Each command is an HID output report:
 *
 *     [0x03 reportId][0x0C opcode][ASCII command bytes ...] zero-padded
 *
 * The report is written to the interrupt OUT endpoint. The device streams ASCII
 * debug/response lines back on the interrupt IN endpoint (report ids 3/4/5).
 */
object Lg360Protocol {
    const val VENDOR_ID = 0x1004   // 4100  – LG Electronics
    const val PRODUCT_ID = 0x6374  // 25460 – LG 360 VR (custom HID)

    const val REPORT_ID: Byte = 0x03
    const val OPCODE: Byte = 0x0C

    /** Total on-the-wire report length (reportId + payload). */
    const val REPORT_LEN = 64

    /** Activation sequence: disable sleep, then start the VR/display app. */
    val ACTIVATION = listOf("Sleep Disable", "VR App Start")

    /**
     * Full firmware command vocabulary extracted from the .dfu, grouped for the UI.
     * Sending is best-effort: while the headset is boot-looping (waiting for HDMI)
     * only "VR App Start" is reliably acknowledged.
     */
    val COMMAND_GROUPS: Map<String, List<String>> = linkedMapOf(
        "Core" to listOf(
            "Sleep Disable",
            "VR App Start",
            "Set LCD Pattern Test",
            "Go to Dload",
        ),
        "Info" to listOf(
            "Get Swversion",
            "Get ModelName",
            "Get SerialNumber",
            "Get Hwversion",
            "Get FID",
        ),
        "Proximity" to listOf(
            "Proximity On",
            "Proximity Off",
            "Proximity Cal",
            "Proximity NEAR",
            "Proximity FAR",
            "Proximity Get Data",
            "Proximity Get Crosstalk",
            "Proximity Get Regi",
        ),
        "Gyro" to listOf(
            "Gyro On",
            "Gyro Off",
            "Gyro Cal",
            "Gyro Selftest",
            "Gyro Get XYZ",
        ),
        "Accel" to listOf(
            "Accel On",
            "Accel Off",
            "Accel Cal",
            "Accel Selftest",
            "Accel Get XYZ",
        ),
        "Compass" to listOf(
            "Compass On",
            "Compass Off",
            "Compass Get XYZ",
        ),
    )

    /** Build the 64-byte HID output report for an ASCII command. */
    fun buildReport(command: String): ByteArray {
        val report = ByteArray(REPORT_LEN)
        report[0] = REPORT_ID
        report[1] = OPCODE
        val ascii = command.toByteArray(Charsets.US_ASCII)
        val n = minOf(ascii.size, REPORT_LEN - 2)
        System.arraycopy(ascii, 0, report, 2, n)
        return report
    }
}
