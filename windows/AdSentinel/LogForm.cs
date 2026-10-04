namespace AdSentinel;

/// <summary>拦截日志查看窗口。</summary>
public sealed class LogForm : Form
{
    private readonly TextBox _txt = new()
    {
        Multiline = true,
        ReadOnly = true,
        ScrollBars = ScrollBars.Both,
        WordWrap = false,
        Dock = DockStyle.Fill,
        Font = new Font("Consolas", 9f),
        BackColor = Color.White,
    };

    public LogForm()
    {
        Text = "AdSentinel 拦截日志";
        Font = new Font("Microsoft YaHei UI", 9f);
        StartPosition = FormStartPosition.CenterScreen;
        ClientSize = new Size(720, 480);

        var buttons = new FlowLayoutPanel
        {
            Dock = DockStyle.Top,
            FlowDirection = FlowDirection.LeftToRight,
            AutoSize = true,
            Padding = new Padding(12, 8, 12, 8),
        };
        var btnRefresh = new Button { Text = "刷新", AutoSize = true };
        var btnClear = new Button { Text = "清空日志", AutoSize = true };
        buttons.Controls.Add(btnRefresh);
        buttons.Controls.Add(btnClear);
        btnRefresh.Click += (_, _) => LoadLog();
        btnClear.Click += (_, _) =>
        {
            try
            {
                Directory.CreateDirectory(ConfigManager.Dir);
                File.WriteAllText(ConfigManager.LogPath, "");
                LoadLog();
            }
            catch { /* ignore */ }
        };

        Controls.Add(_txt);
        Controls.Add(buttons);
        LoadLog();
    }

    private void LoadLog()
    {
        try
        {
            _txt.Text = File.Exists(ConfigManager.LogPath)
                ? File.ReadAllText(ConfigManager.LogPath)
                : "(暂无记录)";
            _txt.SelectionStart = _txt.TextLength;
            _txt.ScrollToCaret();
        }
        catch (Exception ex)
        {
            _txt.Text = $"读取日志失败:{ex.Message}";
        }
    }
}
