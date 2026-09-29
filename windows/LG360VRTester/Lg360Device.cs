using System;
using System.Collections.Generic;
using System.Text;
using System.Threading;
using HidSharp;

namespace LG360VRTester;

public enum LogSource { Sent, Recv, Info, Error }

public readonly record struct LogLine(DateTime Time, LogSource Source, string Text);

/// <summary>
/// Owns the HID session with the LG 360 VR: open the device, send framed commands
/// and continuously read the ASCII debug stream from the input endpoint.
///
/// Uses the standard Windows HID stack via HidSharp — no WinUSB/Zadig driver swap
/// required (matching the macOS hidapi approach).
/// </summary>
public sealed class Lg360Device : IDisposable
{
    private HidDevice? _device;
    private HidStream? _stream;
    private Thread? _readThread;
    private volatile bool _reading;
    private readonly StringBuilder _recv = new();

    public bool IsConnected => _stream != null;
    public HidDevice? Device => _device;

    public event Action<LogLine>? Log;
    public event Action<bool>? ConnectionChanged;

    private void Emit(LogSource source, string text) =>
        Log?.Invoke(new LogLine(DateTime.Now, source, text));

    /// <summary>Enumerate every LG 360 VR currently attached.</summary>
    public static IReadOnlyList<HidDevice> Enumerate()
    {
        var list = new List<HidDevice>();
        foreach (var d in DeviceList.Local.GetHidDevices(Lg360Protocol.VendorId, Lg360Protocol.ProductId))
            list.Add(d);
        return list;
    }

    /// <summary>Open the first (or given) LG 360 VR and start reading its debug stream.</summary>
    public bool Connect(HidDevice? explicitDevice = null)
    {
        Close();
        var dev = explicitDevice;
        if (dev == null)
        {
            foreach (var d in DeviceList.Local.GetHidDevices(Lg360Protocol.VendorId, Lg360Protocol.ProductId))
            {
                dev = d;
                break;
            }
        }
        if (dev == null)
        {
            Emit(LogSource.Error, "Keine LG 360 VR gefunden (VID 0x1004 / PID 0x6374).");
            return false;
        }

        var opts = new OpenConfiguration();
        opts.SetOption(OpenOption.Exclusive, false);
        if (!dev.TryOpen(opts, out var stream))
        {
            Emit(LogSource.Error, "Gerät konnte nicht geöffnet werden (belegt ein anderer Prozess das HID?).");
            return false;
        }

        _device = dev;
        _stream = stream;
        _stream.ReadTimeout = 200;
        _stream.WriteTimeout = 500;

        Emit(LogSource.Info, $"Verbunden – {SafeProduct(dev)}  " +
                             $"(out={SafeLen(() => dev.GetMaxOutputReportLength())}B, " +
                             $"in={SafeLen(() => dev.GetMaxInputReportLength())}B)");
        ConnectionChanged?.Invoke(true);
        StartReading();
        return true;
    }

    public void Close()
    {
        StopReading();
        try { _stream?.Dispose(); } catch { /* ignore */ }
        bool was = _stream != null;
        _stream = null;
        _device = null;
        if (was) ConnectionChanged?.Invoke(false);
    }

    // ---- Sending ---------------------------------------------------------

    /// <summary>Send one framed command. Returns true on success.</summary>
    public bool Send(string command)
    {
        var stream = _stream;
        var dev = _device;
        if (stream == null || dev == null) return false;

        int len = Lg360Protocol.ReportLen;
        try { int m = dev.GetMaxOutputReportLength(); if (m > 1) len = m; } catch { /* keep default */ }

        var report = Lg360Protocol.BuildReport(command, len);
        try
        {
            stream.Write(report, 0, report.Length);
            Emit(LogSource.Sent, $"\"{command}\"  ({report.Length}B)");
            return true;
        }
        catch (Exception ex)
        {
            Emit(LogSource.Error, $"Senden fehlgeschlagen: \"{command}\" – {ex.Message}");
            return false;
        }
    }

    /// <summary>Send the activation sequence (Sleep Disable, VR App Start), repeated.</summary>
    public void Activate(int repeat = 6, int gapMs = 120)
    {
        var t = new Thread(() =>
        {
            Emit(LogSource.Info, $"Aktivierung startet ({repeat}×)…");
            for (int i = 0; i < repeat && _stream != null; i++)
            {
                foreach (var cmd in Lg360Protocol.Activation)
                {
                    Send(cmd);
                    Thread.Sleep(gapMs);
                }
            }
            Emit(LogSource.Info, "Aktivierungssequenz beendet.");
        })
        { IsBackground = true, Name = "vr-activate" };
        t.Start();
    }

    // ---- Reading the debug stream ---------------------------------------

    private void StartReading()
    {
        var stream = _stream;
        var dev = _device;
        if (stream == null || dev == null) return;

        int size = Lg360Protocol.ReportLen;
        try { int m = dev.GetMaxInputReportLength(); if (m > 0) size = m; } catch { /* keep default */ }

        _reading = true;
        _readThread = new Thread(() =>
        {
            var buffer = new byte[size];
            while (_reading)
            {
                int read;
                try { read = stream.Read(buffer, 0, buffer.Length); }
                catch (TimeoutException) { continue; }
                catch (Exception) { break; }
                if (read > 0) HandleIncoming(buffer, read);
            }
            FlushRecv();
        })
        { IsBackground = true, Name = "vr-read" };
        _readThread.Start();
    }

    private void StopReading()
    {
        _reading = false;
        try { _readThread?.Join(400); } catch { /* ignore */ }
        _readThread = null;
    }

    /// <summary>Device streams ASCII with a report-id first byte; strip it and split on newlines.</summary>
    private void HandleIncoming(byte[] raw, int len)
    {
        int start = 0;
        if (len > 0 && raw[0] >= 3 && raw[0] <= 5) start = 1; // report id 3/4/5
        for (int i = start; i < len; i++)
        {
            int b = raw[i];
            if (b == 0) continue;                 // padding
            if (b == 0x0A || b == 0x0D) { FlushRecv(); continue; }
            if (b >= 0x20 && b <= 0x7E) _recv.Append((char)b);
        }
        if (_recv.Length > 120) FlushRecv();       // some firmware lines omit newline
    }

    private void FlushRecv()
    {
        if (_recv.Length == 0) return;
        var line = _recv.ToString().Trim();
        _recv.Clear();
        if (line.Length > 0) Emit(LogSource.Recv, line);
    }

    // ---- helpers ---------------------------------------------------------

    public static string SafeProduct(HidDevice d)
    {
        try { return d.GetProductName(); } catch { return "LG 360 VR"; }
    }

    public static string? SafeManufacturer(HidDevice d)
    {
        try { return d.GetManufacturer(); } catch { return null; }
    }

    public static string? SafeSerial(HidDevice d)
    {
        try { return d.GetSerialNumber(); } catch { return null; }
    }

    private static string SafeLen(Func<int> f)
    {
        try { return f().ToString(); } catch { return "?"; }
    }

    public void Dispose() => Close();
}
