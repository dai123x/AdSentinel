using Microsoft.Win32;

namespace AdSentinel;

/// <summary>托盘常驻上下文:管理开关状态、菜单与拦截提示。</summary>
public sealed class TrayContext : ApplicationContext, IDisposable
{
    private readonly AppConfig _config;
    private readonly PopupMonitor _monitor;
    private readonly NotifyIcon _tray;
    private readonly Icon _icon;
    private readonly IntPtr _iconHandle;
    private readonly ToolStripMenuItem _menuEnabled;
    private readonly ToolStripMenuItem _menuSmart;
    private readonly ToolStripMenuItem _menuBalloon;
    private readonly ToolStripMenuItem _menuAutoStart;
    private readonly ContextMenuStrip _menu;
    private SettingsForm? _settingsForm;
    private LogForm? _logForm;
    private DateTime _lastBalloonAt = DateTime.MinValue;

    private static string VersionText =>
        typeof(TrayContext).Assembly.GetName().Version?.ToString(3) ?? "?";

    public TrayContext()
    {
        _config = ConfigManager.Load();
        _monitor = new PopupMonitor(_config);
        _monitor.Blocked += OnBlocked;
        if (_config.Enabled) _monitor.Start();

        _icon = PopupMonitor.CreateTrayIcon(out _iconHandle);
        _tray = new NotifyIcon
        {
            Icon = _icon,
            Text = "AdSentinel 广告哨兵",
            Visible = true,
        };
        _tray.MouseClick += (_, e) =>
        {
            if (e.Button == MouseButtons.Left) ShowSettings();
        };

        _menuEnabled = new ToolStripMenuItem("启用拦截", null, (_, _) =>
        {
            _config.Enabled = !_config.Enabled;
            SyncConfig();
        });
        _menuSmart = new ToolStripMenuItem("智能模式(启发式识别)", null, (_, _) =>
        {
            _config.SmartMode = !_config.SmartMode;
            SyncConfig();
        });
        _menuBalloon = new ToolStripMenuItem("拦截气泡提示", null, (_, _) =>
        {
            _config.ShowBalloon = !_config.ShowBalloon;
            SyncConfig();
        });
        _menuAutoStart = new ToolStripMenuItem("开机自启", null, (_, _) =>
        {
            _config.StartWithWindows = !_config.StartWithWindows;
            ApplyAutoStart();
            SyncConfig();
        });

        _menu = new ContextMenuStrip();
        _menu.Items.Add(_menuEnabled);
        _menu.Items.Add(_menuSmart);
        _menu.Items.Add(_menuBalloon);
        _menu.Items.Add(_menuAutoStart);
        _menu.Items.Add(new ToolStripSeparator());
        _menu.Items.Add("设置…", null, (_, _) => ShowSettings());
        _menu.Items.Add("拦截日志…", null, (_, _) => ShowLog());
        _menu.Items.Add(new ToolStripSeparator());
        _menu.Items.Add("关于 AdSentinel", null, (_, _) => MessageBox.Show(
            $"AdSentinel 广告哨兵 v{VersionText}\n\n" +
            "开源 Windows / Android 广告拦截工具\n" +
            "完全本地运行,不上传任何数据\n\n" +
            "https://github.com/dai123x/AdSentinel\n" +
            "基于 GPL-3.0 开源",
            "关于 AdSentinel", MessageBoxButtons.OK, MessageBoxIcon.Information));
        _menu.Items.Add("退出", null, (_, _) =>
        {
            _tray.Visible = false;
            Application.Exit();
        });

        _tray.ContextMenuStrip = _menu;
        SyncMenuChecks();
    }

    private void SyncConfig()
    {
        if (_config.Enabled) _monitor.Start(); else _monitor.Stop();
        ApplyAutoStart();
        ConfigManager.Save(_config);
        SyncMenuChecks();
    }

    private void SyncMenuChecks()
    {
        _menuEnabled.Checked = _config.Enabled;
        _menuSmart.Checked = _config.SmartMode;
        _menuBalloon.Checked = _config.ShowBalloon;
        _menuAutoStart.Checked = _config.StartWithWindows;
    }

    private void ApplyAutoStart()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(
                @"Software\Microsoft\Windows\CurrentVersion\Run", writable: true);
            if (key is null) return;
            if (_config.StartWithWindows && Environment.ProcessPath is not null)
            {
                key.SetValue("AdSentinel", $"\"{Environment.ProcessPath}\"", RegistryValueKind.String);
            }
            else
            {
                key.DeleteValue("AdSentinel", throwOnMissingValue: false);
            }
        }
        catch
        {
            // 注册表操作失败不致命
        }
    }

    private void OnBlocked(PopupMonitor.BlockEventArgs e)
    {
        Logger.Append($"[{e.Action}] {e.RuleName} | {e.Process} | \"{e.Title}\"{(e.Smart ? " | 智能模式" : "")}");
        if (!_config.ShowBalloon) return;
        if ((DateTime.Now - _lastBalloonAt).TotalSeconds < 5) return;
        _lastBalloonAt = DateTime.Now;
        _tray.BalloonTipTitle = "已拦截弹窗";
        _tray.BalloonTipText = $"{e.Process}: {e.Title}\n规则:{e.RuleName}";
        _tray.ShowBalloonTip(2000);
    }

    private void ShowSettings()
    {
        if (_settingsForm is null || _settingsForm.IsDisposed)
        {
            _settingsForm = new SettingsForm(_config, _monitor);
            _settingsForm.Show();
        }
        else
        {
            _settingsForm.Activate();
        }
    }

    private void ShowLog()
    {
        if (_logForm is null || _logForm.IsDisposed)
        {
            _logForm = new LogForm();
            _logForm.Show();
        }
        else
        {
            _logForm.Activate();
        }
    }

    void IDisposable.Dispose()
    {
        _monitor.Dispose();
        _tray.Visible = false;
        _tray.Dispose();
        _menu.Dispose();
        _icon.Dispose();
        // Icon.FromHandle 不接管 HICON 所有权,须显式销毁防止 GDI 句柄泄漏
        if (_iconHandle != IntPtr.Zero) NativeMethods.DestroyIcon(_iconHandle);
        _settingsForm?.Dispose();
        _logForm?.Dispose();
        base.Dispose();
    }
}
