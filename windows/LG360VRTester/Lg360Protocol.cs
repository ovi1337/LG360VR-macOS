using System;
using System.Text;

namespace LG360VRTester;

/// <summary>
/// LG 360 VR (LGR100AT) HID protocol.
///
/// Reverse-engineered from bauermaximilian/LG-360-VR-for-PC and confirmed against
/// the device firmware (LGR100AT-...dfu). Each command is an HID output report:
///
///     [0x03 reportId][0x0C opcode][ASCII command bytes ...] zero-padded to 64 bytes.
///
/// The report is written to the HID output (interrupt OUT) endpoint. The device
/// streams ASCII debug/response lines back on the input endpoint (report ids 3/4/5).
/// </summary>
public static class Lg360Protocol
{
    public const int VendorId = 0x1004;   // 4100  – LG Electronics
    public const int ProductId = 0x6374;  // 25460 – LG 360 VR (custom HID)

    public const byte ReportId = 0x03;
    public const byte Opcode = 0x0C;

    /// <summary>Default on-the-wire report length (report id + 63-byte payload).</summary>
    public const int ReportLen = 64;

    /// <summary>Activation sequence: disable sleep, then start the VR/display app.</summary>
    public static readonly string[] Activation = { "Sleep Disable", "VR App Start" };

    /// <summary>
    /// Full firmware command vocabulary extracted from the .dfu, grouped for the UI.
    /// While the headset boot-loops (waiting for HDMI/DP video) only "VR App Start"
    /// is reliably acknowledged; the rest are best-effort.
    /// </summary>
    public static readonly (string Group, string[] Commands)[] CommandGroups =
    {
        ("Core", new[] { "Sleep Disable", "VR App Start", "Set LCD Pattern Test", "Go to Dload" }),
        ("Info", new[] { "Get Swversion", "Get ModelName", "Get SerialNumber", "Get Hwversion", "Get FID" }),
        ("Proximity", new[]
        {
            "Proximity On", "Proximity Off", "Proximity Cal", "Proximity NEAR", "Proximity FAR",
            "Proximity Get Data", "Proximity Get Crosstalk", "Proximity Get Regi"
        }),
        ("Gyro", new[] { "Gyro On", "Gyro Off", "Gyro Cal", "Gyro Selftest", "Gyro Get XYZ" }),
        ("Accel", new[] { "Accel On", "Accel Off", "Accel Cal", "Accel Selftest", "Accel Get XYZ" }),
        ("Compass", new[] { "Compass On", "Compass Off", "Compass Get XYZ" }),
    };

    /// <summary>Build the HID output report for an ASCII command, padded to <paramref name="length"/>.</summary>
    public static byte[] BuildReport(string command, int length = ReportLen)
    {
        if (length < 2) length = ReportLen;
        var report = new byte[length];
        report[0] = ReportId;
        report[1] = Opcode;
        var ascii = Encoding.ASCII.GetBytes(command);
        int n = Math.Min(ascii.Length, length - 2);
        Array.Copy(ascii, 0, report, 2, n);
        return report;
    }
}
