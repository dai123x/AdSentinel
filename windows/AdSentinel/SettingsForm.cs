namespace AdSentinel;

/// <summary>设置窗口(手写控件,不依赖 Designer)。</summary>
public sealed class SettingsForm : Form
{
    private readonly AppConfig _config;
    private readonly PopupMonitor _monitor;

    private readonly CheckBox _chkEnabled = new() { Text = "启用弹窗拦截" };
    private readonly CheckBox _chkBuiltin = new() { Text = "使用内置规则(推荐)" };
    private readonly CheckBox _chkSmart = new() { Text = "智能模式:启发式识别陌生广告弹窗(更激进,可能误杀)" };
    private readonly CheckBox _chkBalloon = new() { Text = "拦截时显示气泡提示" };
    private readonly CheckBox _chkAutoStart = new() { Text = "开机自启" };
    private readonly TextBox _txtWhitelist = new()
    {
        Multiline = true,
        ScrollBars = ScrollBars.Vertical,
        Dock = DockStyle.Fill,
        Font = new Font("Consolas", 9f),
    };
    private readonly ListView _lvRules = new()
    {
        View = View.Details,
        FullRowSelect = true,
        Dock = DockStyle.Fill,
    };

    public SettingsForm(AppConfig config, PopupMonitor monitor)
    {
        _config = config;
        _monitor = monitor;

        Text = "AdSentinel 设置";
        Font = new Font("Microsoft YaHei UI", 9f);
        FormBorderStyle = FormBorderStyle.FixedSingle;
        MaximizeBox = false;
        MinimizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        ClientSize = new Size(600, 640);

        var top = new FlowLayoutPanel
        {
            Dock = DockStyle.Top,
            FlowDirection = FlowDirection.TopDown,
            AutoSize = true,
            WrapContents = false,
            Padding = new Padding(12, 12, 12, 4),
        };
        foreach (var chk in new[] { _chkEnabled, _chkBuiltin, _chkSmart, _chkBalloon, _chkAutoStart })
        {
            chk.AutoSize = true;
            top.Controls.Add(chk);
        }

        _lvRules.Columns.Add("内置规则", 200);
        _lvRules.Columns.Add("标题匹配", 260);
        _lvRules.Columns.Add("动作", 80);
        foreach (var rule in monitor.BuiltinRules.Where(r => r.Enabled))
        {
            _lvRules.Items.Add(new ListViewItem(new[]
            {
                rule.Name, rule.Title ?? (rule.Class != null ? $"类名:{rule.Class}" : rule.Process ?? ""), rule.Action,
            }));
        }

        var rulesGroup = new GroupBox
        {
            Text = "内置规则(只读,欢迎到 GitHub 提交新规则)",
            Dock = DockStyle.Fill,
            Padding = new Padding(8),
        };
        rulesGroup.Controls.Add(_lvRules);

        var whitelistGroup = new GroupBox
        {
            Text = "白名单(每行一条:进程名或窗口标题正则,忽略大小写)",
            Dock = DockStyle.Fill,
            Padding = new Padding(8),
        };
        whitelistGroup.Controls.Add(_txtWhitelist);

        var buttons = new FlowLayoutPanel
        {
            Dock = DockStyle.Bottom,
            FlowDirection = FlowDirection.RightToLeft,
            AutoSize = true,
            Padding = new Padding(12),
        };
        var btnSave = new Button { Text = "保存并应用", AutoSize = true };
        var btnEditConfig = new Button { Text = "编辑自定义规则(config.json)", AutoSize = true };
        buttons.Controls.Add(btnSave);
        buttons.Controls.Add(btnEditConfig);
        btnSave.Click += (_, _) => SaveAndApply();
        btnEditConfig.Click += (_, _) => OpenConfigFile();

        var split = new SplitContainer
        {
            Dock = DockStyle.Fill,
            Orientation = Orientation.Horizontal,
            SplitterDistance = 280,
        };
        split.Panel1.Controls.Add(rulesGroup);
        split.Panel2.Controls.Add(whitelistGroup);

        Controls.Add(split);
        Controls.Add(top);
        Controls.Add(buttons);

        LoadValues();
    }

    private void LoadValues()
    {
        _chkEnabled.Checked = _config.Enabled;
        _chkBuiltin.Checked = _config.BuiltinRulesEnabled;
        _chkSmart.Checked = _config.SmartMode;
        _chkBalloon.Checked = _config.ShowBalloon;
        _chkAutoStart.Checked = _config.StartWithWindows;
        _txtWhitelist.Text = string.Join(Environment.NewLine, _config.Whitelist);
    }

    private void SaveAndApply()
    {
        _config.Enabled = _chkEnabled.Checked;
        _config.BuiltinRulesEnabled = _chkBuiltin.Checked;
        _config.SmartMode = _chkSmart.Checked;
        _config.ShowBalloon = _chkBalloon.Checked;
        _config.StartWithWindows = _chkAutoStart.Checked;
        _config.Whitelist = _txtWhitelist.Lines
            .Select(l => l.Trim())
            .Where(l => l.Length > 0)
            .ToList();
        _monitor.ReloadBuiltin();
        if (_config.Enabled) _monitor.Start(); else _monitor.Stop();
        ConfigManager.Save(_config);
        MessageBox.Show("设置已保存并生效。", "AdSentinel", MessageBoxButtons.OK, MessageBoxIcon.Information);
    }

    private static void OpenConfigFile()
    {
        try
        {
            Directory.CreateDirectory(ConfigManager.Dir);
            if (!File.Exists(ConfigManager.ConfigPath)) ConfigManager.Save(new AppConfig());
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo
            {
                FileName = "notepad.exe",
                Arguments = $"\"{ConfigManager.ConfigPath}\"",
                UseShellExecute = true,
            });
        }
        catch (Exception ex)
        {
            MessageBox.Show($"打开配置失败:{ex.Message}", "AdSentinel",
                MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }
    }
}
