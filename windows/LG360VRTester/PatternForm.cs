using System;
using System.Drawing;
using System.Windows.Forms;

namespace LG360VRTester;

public enum TestPattern { Bars, Grid, Crosshair, SolidWhite }

/// <summary>
/// Borderless full-screen test pattern shown on a chosen <see cref="Screen"/>.
/// If the headset ever enumerates as a second monitor (DP-alt-mode), this proves
/// whether it actually receives and renders video. Press Esc to close.
/// </summary>
public sealed class PatternForm : Form
{
    private readonly TestPattern _pattern;

    private static readonly Color[] Bars =
    {
        Color.White, Color.Yellow, Color.Cyan, Color.Lime,
        Color.Magenta, Color.Red, Color.Blue, Color.Black
    };

    public PatternForm(Screen screen, TestPattern pattern)
    {
        _pattern = pattern;
        FormBorderStyle = FormBorderStyle.None;
        StartPosition = FormStartPosition.Manual;
        Bounds = screen.Bounds;
        BackColor = Color.Black;
        DoubleBuffered = true;
        ShowInTaskbar = false;
        TopMost = true;
        KeyPreview = true;
        Text = "LG 360 VR Testbild";
        KeyDown += (_, e) => { if (e.KeyCode == Keys.Escape) Close(); };
        Load += (_, _) => WindowState = FormWindowState.Maximized;
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        int w = ClientSize.Width, h = ClientSize.Height;
        switch (_pattern)
        {
            case TestPattern.SolidWhite:
                g.Clear(Color.White);
                break;
            case TestPattern.Bars:
                DrawBars(g, w, h);
                break;
            case TestPattern.Grid:
                g.Clear(Color.Black);
                DrawGrid(g, w, h);
                break;
            case TestPattern.Crosshair:
                g.Clear(Color.Black);
                DrawCrosshair(g, w, h);
                break;
        }
        DrawInfo(g, w, h);
    }

    private static void DrawBars(Graphics g, int w, int h)
    {
        float bw = (float)w / Bars.Length;
        for (int i = 0; i < Bars.Length; i++)
        {
            using var b = new SolidBrush(Bars[i]);
            g.FillRectangle(b, i * bw, 0, bw + 1, h * 0.85f);
        }
        for (int x = 0; x < w; x++)
        {
            int v = 255 * x / Math.Max(1, w);
            using var b = new SolidBrush(Color.FromArgb(v, v, v));
            g.FillRectangle(b, x, h * 0.85f, 1, h * 0.15f);
        }
    }

    private static void DrawGrid(Graphics g, int w, int h)
    {
        using var p = new Pen(Color.FromArgb(0, 200, 120), 2);
        float step = w / 24f;
        for (float x = 0; x <= w; x += step) g.DrawLine(p, x, 0, x, h);
        for (float y = 0; y <= h; y += step) g.DrawLine(p, 0, y, w, y);
    }

    private static void DrawCrosshair(Graphics g, int w, int h)
    {
        using var p = new Pen(Color.FromArgb(90, 200, 250), 3);
        g.DrawLine(p, 0, h / 2f, w, h / 2f);
        g.DrawLine(p, w / 2f, 0, w / 2f, h);
        float ring = Math.Min(w, h) / 12f;
        for (float r = ring; r < Math.Max(w, h); r += ring)
            g.DrawEllipse(p, w / 2f - r, h / 2f - r, 2 * r, 2 * r);
    }

    private void DrawInfo(Graphics g, int w, int h)
    {
        using var font = new Font("Segoe UI", 16, FontStyle.Bold);
        using var shadow = new SolidBrush(Color.Black);
        using var fg = new SolidBrush(Color.White);
        string top = $"LG 360 VR   {w}×{h}   •   {_pattern}";
        string bottom = "Wenn du das siehst, empfängt die Brille Video ✓   (Esc = schließen)";
        g.DrawString(top, font, shadow, 22, 22);
        g.DrawString(top, font, fg, 20, 20);
        var size = g.MeasureString(bottom, font);
        g.DrawString(bottom, font, shadow, 22, h - size.Height - 18);
        g.DrawString(bottom, font, fg, 20, h - size.Height - 20);
    }
}
