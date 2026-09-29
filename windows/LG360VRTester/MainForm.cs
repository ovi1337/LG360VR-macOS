using System;
using System.Drawing;
using System.Linq;
using System.Text;
using System.Windows.Forms;
using HidSharp;

namespace LG360VRTester;

public sealed class MainForm : Form
{
    private static readonly Color Bg = Color.FromArgb(11, 16, 33);
    private static readonly Color Surface = Color.FromArgb(22, 28, 51);
    private static readonly Color Accent = Color.FromArgb(90, 200, 250);
    private static readonly Color TextColor = Color.FromArgb(230, 236, 255);
    private static readonly Color Muted = Color.FromArgb(136, 146, 176);

    private readonly Lg360Device _dev = new();

    // Status tab
    private Label _stateLabel = null!;
    private Label _statusLabel = null!;
    private Label _infoLabel = null!;
    private Button _activateBtn = null!;

    // Log tab
    private RichTextBox _logBox = null!;

    // Display tab
    private ListBox _screenList = null!;
    private RadioButton _rbBars = null!, _rbGrid = null!, _rbCross = null!, _rbWhite = null!;
    private PatternForm? _pattern;

    // USB tab
    private TextBox _usbBox = null!;

    private readonly System.Windows.Forms.Timer _poll = new() { Interval = 1500 };

    public MainForm()
    {
        Text = "LG 360 VR Tester";
        BackColor = Bg;
        ForeColor = TextColor;
        Font = new Font("Segoe UI", 9.5f);
        MinimumSize = new Size(760, 620);
        Size = new Size(820, 720);
        StartPosition = FormStartPosition.CenterScreen;

        BuildUi();

        _dev.Log += OnLog;
        _dev.ConnectionChanged += OnConnectionChanged;

        _poll.Tick += (_, _) => Poll();
        _poll.Start();

        Load += (_, _) => { TryConnect(); RefreshScreens(); };
        FormClosing += (_, _) => { _dev.Dispose(); _pattern?.Close(); };
    }

    // ---- UI construction -------------------------------------------------

    private void BuildUi()
    {
        var tabs = new TabControl { Dock = DockStyle.Fill };

        tabs.TabPages.Add(BuildStatusTab());
        tabs.TabPages.Add(BuildCommandsTab());
        tabs.TabPages.Add(BuildLogTab());
        tabs.TabPages.Add(BuildDisplayTab());
        tabs.TabPages.Add(BuildUsbTab());

        Controls.Add(tabs);
    }

    private TabPage BuildStatusTab()
    {
        var page = NewPage("Status");
        var root = new FlowLayoutPanel
        {
            Dock = DockStyle.Fill, FlowDirection = FlowDirection.TopDown,
            WrapContents = false, AutoScroll = true, Padding = new Padding(14)
        };

        _stateLabel = new Label { AutoSize = true, Font = Bold(15), Text = "Getrennt", ForeColor = Muted };
        _statusLabel = new Label { AutoSize = true, ForeColor = Muted, Text = "Bereit", Margin = new Padding(3, 2, 3, 12) };
        _infoLabel = new Label { AutoSize = true, ForeColor = TextColor, Text = "", Margin = new Padding(3, 2, 3, 12) };

        var connectBtn = MakeButton("Verbinden", (_, _) => TryConnect());
        var disconnectBtn = MakeButton("Trennen", (_, _) => _dev.Close());
        _activateBtn = MakeButton("Aktivieren  (Sleep Disable + VR App Start)", (_, _) => _dev.Activate());
        _activateBtn.BackColor = Accent;
        _activateBtn.ForeColor = Color.Black;
        _activateBtn.Enabled = false;
        _activateBtn.Width = 420;

        var row = new FlowLayoutPanel { AutoSize = true, FlowDirection = FlowDirection.LeftToRight, Margin = new Padding(0, 4, 0, 10) };
        row.Controls.Add(connectBtn);
        row.Controls.Add(disconnectBtn);

        var hint = new Label
        {
            AutoSize = false, Width = 600, Height = 70, ForeColor = Muted,
            Margin = new Padding(3, 12, 3, 3),
            Text = "Hinweis: Nach dem Aktivieren wartet die Brille auf ein DP-Alt-Mode-Videosignal. " +
                   "Erscheint sie im Tab \"Display\" als zweiter Monitor, kann ein Testbild ausgegeben werden. " +
                   "HidSharp nutzt den Standard-HID-Stack – kein Zadig/WinUSB nötig."
        };

        root.Controls.Add(_stateLabel);
        root.Controls.Add(_statusLabel);
        root.Controls.Add(_infoLabel);
        root.Controls.Add(row);
        root.Controls.Add(_activateBtn);
        root.Controls.Add(hint);
        page.Controls.Add(root);
        return page;
    }

    private TabPage BuildCommandsTab()
    {
        var page = NewPage("Befehle");
        var root = new FlowLayoutPanel
        {
            Dock = DockStyle.Fill, FlowDirection = FlowDirection.TopDown,
            WrapContents = false, AutoScroll = true, Padding = new Padding(14)
        };

        var customBox = new TextBox { Width = 340, ForeColor = TextColor, BackColor = Surface, Text = "" };
        var sendBtn = MakeButton("Senden", (_, _) =>
        {
            var c = customBox.Text.Trim();
            if (c.Length > 0) _dev.Send(c);
        });
        var customRow = new FlowLayoutPanel { AutoSize = true, FlowDirection = FlowDirection.LeftToRight };
        customRow.Controls.Add(new Label { Text = "Eigenes Kommando:", AutoSize = true, ForeColor = Muted, Margin = new Padding(3, 8, 3, 3) });
        customRow.Controls.Add(customBox);
        customRow.Controls.Add(sendBtn);
        root.Controls.Add(customRow);

        foreach (var (group, cmds) in Lg360Protocol.CommandGroups)
        {
            root.Controls.Add(new Label { Text = group, AutoSize = true, Font = Bold(11), ForeColor = Accent, Margin = new Padding(3, 14, 3, 4) });
            var flow = new FlowLayoutPanel { AutoSize = true, Width = 700, FlowDirection = FlowDirection.LeftToRight, WrapContents = true };
            foreach (var cmd in cmds)
            {
                var b = MakeButton(cmd, (_, _) => _dev.Send(cmd));
                b.AutoSize = true;
                flow.Controls.Add(b);
            }
            root.Controls.Add(flow);
        }

        page.Controls.Add(root);
        return page;
    }

    private TabPage BuildLogTab()
    {
        var page = NewPage("Log");
        _logBox = new RichTextBox
        {
            Dock = DockStyle.Fill, ReadOnly = true, BackColor = Color.FromArgb(9, 13, 28),
            ForeColor = TextColor, Font = new Font("Consolas", 9.5f), BorderStyle = BorderStyle.None,
            WordWrap = false
        };
        var clearBtn = MakeButton("Leeren", (_, _) => _logBox.Clear());
        clearBtn.Dock = DockStyle.Top;
        page.Controls.Add(_logBox);
        page.Controls.Add(clearBtn);
        return page;
    }

    private TabPage BuildDisplayTab()
    {
        var page = NewPage("Display");
        var root = new FlowLayoutPanel
        {
            Dock = DockStyle.Fill, FlowDirection = FlowDirection.TopDown,
            WrapContents = false, AutoScroll = true, Padding = new Padding(14)
        };

        root.Controls.Add(new Label { Text = "Erkannte Bildschirme", AutoSize = true, Font = Bold(12), Margin = new Padding(3, 0, 3, 6) });
        _screenList = new ListBox { Width = 620, Height = 120, BackColor = Surface, ForeColor = TextColor, BorderStyle = BorderStyle.FixedSingle };
        root.Controls.Add(_screenList);
        root.Controls.Add(MakeButton("Aktualisieren", (_, _) => RefreshScreens()));

        root.Controls.Add(new Label { Text = "Testbild", AutoSize = true, Font = Bold(12), Margin = new Padding(3, 16, 3, 6) });
        _rbBars = NewRadio("Farbbalken (SMPTE)", true);
        _rbGrid = NewRadio("Gitter", false);
        _rbCross = NewRadio("Fadenkreuz", false);
        _rbWhite = NewRadio("Weiß (Backlight-Test)", false);
        root.Controls.Add(_rbBars);
        root.Controls.Add(_rbGrid);
        root.Controls.Add(_rbCross);
        root.Controls.Add(_rbWhite);

        var btnRow = new FlowLayoutPanel { AutoSize = true, FlowDirection = FlowDirection.LeftToRight, Margin = new Padding(0, 8, 0, 0) };
        btnRow.Controls.Add(MakeButton("Auf externem Display anzeigen", (_, _) => ShowPattern()));
        btnRow.Controls.Add(MakeButton("Ausblenden", (_, _) => { _pattern?.Close(); _pattern = null; }));
        root.Controls.Add(btnRow);

        root.Controls.Add(new Label
        {
            AutoSize = false, Width = 620, Height = 50, ForeColor = Muted, Margin = new Padding(3, 12, 3, 3),
            Text = "Nur ein Bildschirm = die Brille hat keinen Videopfad (kein DP-Alt-Mode). " +
                   "Erscheint ein zweiter Bildschirm, wird das Testbild dorthin ausgegeben."
        });

        page.Controls.Add(root);
        return page;
    }

    private TabPage BuildUsbTab()
    {
        var page = NewPage("USB");
        _usbBox = new TextBox
        {
            Dock = DockStyle.Fill, Multiline = true, ReadOnly = true, ScrollBars = ScrollBars.Vertical,
            BackColor = Color.FromArgb(9, 13, 28), ForeColor = TextColor, Font = new Font("Consolas", 9.5f),
            BorderStyle = BorderStyle.None, Text = "Nicht verbunden."
        };
        var refreshBtn = MakeButton("Aktualisieren", (_, _) => RefreshUsbInfo());
        refreshBtn.Dock = DockStyle.Top;
        page.Controls.Add(_usbBox);
        page.Controls.Add(refreshBtn);
        return page;
    }

    // ---- actions ---------------------------------------------------------

    private void TryConnect()
    {
        if (_dev.IsConnected) { _dev.Close(); }
        _dev.Connect();
        RefreshUsbInfo();
    }

    private void Poll()
    {
        if (!_dev.IsConnected)
        {
            if (Lg360Device.Enumerate().Count > 0)
                _dev.Connect();
        }
        RefreshScreens();
    }

    private void ShowPattern()
    {
        var screens = Screen.AllScreens;
        var target = screens.FirstOrDefault(s => !s.Primary) ?? screens.FirstOrDefault();
        if (target == null) return;
        _pattern?.Close();
        _pattern = new PatternForm(target, SelectedPattern());
        _pattern.FormClosed += (_, _) => _pattern = null;
        _pattern.Show();
    }

    private TestPattern SelectedPattern()
    {
        if (_rbGrid.Checked) return TestPattern.Grid;
        if (_rbCross.Checked) return TestPattern.Crosshair;
        if (_rbWhite.Checked) return TestPattern.SolidWhite;
        return TestPattern.Bars;
    }

    private void RefreshScreens()
    {
        if (_screenList.IsDisposed) return;
        var items = Screen.AllScreens
            .Select((s, i) => $"{(s.Primary ? "● intern " : "○ EXTERN")}  #{i}  {s.Bounds.Width}×{s.Bounds.Height}  @ {s.Bounds.X},{s.Bounds.Y}  [{s.DeviceName}]")
            .ToArray();
        // Avoid needless flicker: only update when changed.
        if (_screenList.Items.Count == items.Length &&
            _screenList.Items.Cast<string>().SequenceEqual(items)) return;
        _screenList.BeginUpdate();
        _screenList.Items.Clear();
        _screenList.Items.AddRange(items);
        _screenList.EndUpdate();
    }

    private void RefreshUsbInfo()
    {
        var dev = _dev.Device;
        var sb = new StringBuilder();
        if (dev == null)
        {
            _usbBox.Text = "Nicht verbunden.";
            return;
        }
        sb.AppendLine("=== LG 360 VR (HID) ===");
        sb.AppendLine($"Produkt      : {Lg360Device.SafeProduct(dev)}");
        sb.AppendLine($"Hersteller   : {Lg360Device.SafeManufacturer(dev) ?? "–"}");
        sb.AppendLine($"Seriennr.    : {Lg360Device.SafeSerial(dev) ?? "–"}");
        sb.AppendLine($"VID / PID    : 0x{dev.VendorID:X4} / 0x{dev.ProductID:X4}");
        try { sb.AppendLine($"Release BCD  : 0x{dev.ReleaseNumberBcd:X4}"); } catch { }
        try { sb.AppendLine($"Pfad         : {dev.DevicePath}"); } catch { }
        try { sb.AppendLine($"Max Input    : {dev.GetMaxInputReportLength()} B"); } catch { }
        try { sb.AppendLine($"Max Output   : {dev.GetMaxOutputReportLength()} B"); } catch { }
        try { sb.AppendLine($"Max Feature  : {dev.GetMaxFeatureReportLength()} B"); } catch { }

        try
        {
            var rd = dev.GetReportDescriptor();
            sb.AppendLine();
            sb.AppendLine("=== Report-Deskriptor ===");
            foreach (var r in rd.InputReports) sb.AppendLine($"{"Input",-8} id={r.ReportID}  length={r.Length} B");
            foreach (var r in rd.OutputReports) sb.AppendLine($"{"Output",-8} id={r.ReportID}  length={r.Length} B");
            foreach (var r in rd.FeatureReports) sb.AppendLine($"{"Feature",-8} id={r.ReportID}  length={r.Length} B");
        }
        catch (Exception ex)
        {
            sb.AppendLine();
            sb.AppendLine($"(Report-Deskriptor nicht lesbar: {ex.Message})");
        }

        _usbBox.Text = sb.ToString();
    }

    // ---- device events (marshalled to UI thread) -------------------------

    private void OnLog(LogLine line)
    {
        UI(() =>
        {
            if (_logBox.IsDisposed) return;
            Color c = line.Source switch
            {
                LogSource.Sent => Color.FromArgb(90, 200, 250),
                LogSource.Recv => Color.FromArgb(155, 232, 107),
                LogSource.Error => Color.FromArgb(255, 107, 107),
                _ => Color.FromArgb(176, 184, 216)
            };
            string tag = line.Source switch
            {
                LogSource.Sent => "TX", LogSource.Recv => "RX",
                LogSource.Error => "!!", _ => ".."
            };
            _logBox.SelectionStart = _logBox.TextLength;
            _logBox.SelectionColor = c;
            _logBox.AppendText($"{line.Time:HH:mm:ss.fff}  {tag}  {line.Text}\n");
            if (_logBox.Lines.Length > 1000)
            {
                _logBox.SelectionStart = 0;
                _logBox.SelectionLength = _logBox.GetFirstCharIndexFromLine(200);
                _logBox.SelectedText = "";
            }
            _logBox.SelectionStart = _logBox.TextLength;
            _logBox.ScrollToCaret();
        });
    }

    private void OnConnectionChanged(bool connected)
    {
        UI(() =>
        {
            if (IsDisposed) return;
            _activateBtn.Enabled = connected;
            var dev = _dev.Device;
            if (connected && dev != null)
            {
                _stateLabel.Text = "Verbunden";
                _stateLabel.ForeColor = Color.FromArgb(53, 208, 127);
                _statusLabel.Text = $"Verbunden – {Lg360Device.SafeProduct(dev)}";
                _infoLabel.Text =
                    $"VID / PID : 0x{dev.VendorID:X4} / 0x{dev.ProductID:X4}\n" +
                    $"Hersteller: {Lg360Device.SafeManufacturer(dev) ?? "–"}\n" +
                    $"Seriennr. : {Lg360Device.SafeSerial(dev) ?? "–"}";
                RefreshUsbInfo();
            }
            else
            {
                _stateLabel.Text = "Getrennt";
                _stateLabel.ForeColor = Muted;
                _statusLabel.Text = "Kein Gerät verbunden.";
                _infoLabel.Text = "";
            }
        });
    }

    // ---- small helpers ---------------------------------------------------

    private void UI(Action a)
    {
        if (!IsHandleCreated || IsDisposed) return;
        try { BeginInvoke(a); } catch { /* form closing */ }
    }

    private static Font Bold(float size) => new("Segoe UI", size, FontStyle.Bold);

    private TabPage NewPage(string title) => new(title) { BackColor = Bg, ForeColor = TextColor, UseVisualStyleBackColor = false };

    private RadioButton NewRadio(string text, bool chk) =>
        new() { Text = text, AutoSize = true, Checked = chk, ForeColor = TextColor, Margin = new Padding(3, 2, 3, 2) };

    private Button MakeButton(string text, EventHandler onClick)
    {
        var b = new Button
        {
            Text = text, AutoSize = true, BackColor = Surface, ForeColor = TextColor,
            FlatStyle = FlatStyle.Flat, Margin = new Padding(4), Padding = new Padding(8, 4, 8, 4)
        };
        b.FlatAppearance.BorderColor = Accent;
        b.Click += onClick;
        return b;
    }
}
