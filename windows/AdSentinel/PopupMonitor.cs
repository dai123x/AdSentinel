using System.Diagnostics;
using System.Drawing.Drawing2D;
using System.Text;
using System.Text.RegularExpressions;

namespace AdSentinel;

/// <summary>
/// 弹窗拦截主循环:定时枚举顶层窗口,对新出现的可见窗口依次做
/// 系统窗口排除 → 白名单 → 内置规则 → 自定义规则 → 智能启发式 判定。
/// </summary>
public sealed class PopupMonitor : IDisposable
{
    public sealed class BlockEventArgs : EventArgs
    {
        public string RuleName { get; }
        public string Process { get; }
        public string Title { get; }
        public string Action { get; }
        public bool Smart { get; }

        public BlockEventArgs(string ruleName, string process, string title, string action, bool smart)
        {
            RuleName = ruleName;
            Process = process;
            Title = title;
            Action = action;
            Smart = smart;
        }
    }

    public event Action<BlockEventArgs>? Blocked;

    private const int ScanIntervalMs = 800;
    private const int IdleScanIntervalMs = 1600;
    private const int QuietScansBeforeIdle = 20;

    private readonly AppConfig _config;
    private readonly System.Windows.Forms.Timer _timer;
    private List<BlockRule> _builtin;
    private readonly HashSet<long> _knownWindows = new();
    private readonly Dictionary<uint, string> _processNameCache = new();
    private readonly int _ownPid;
    private int _quietScans;

    private static readonly Regex AdKeywords = new(
        "今日热点|热点资讯|新闻资讯|热点新闻|高清视频|免费电影|追剧|领红包|抢红包|红包雨|现金红包|" +
        "摇钱树|大转盘|中奖|恭喜.{0,6}(中奖|获得)|限时.{0,4}特惠|秒杀|内部.{0,4}优惠券|0元购|" +
        "垃圾清理|一键加速|开机.{0,4}加速|深度清理|美女直播|传奇.{0,4}私服|页游|博彩|荷官|" +
        "轻松.{0,4}赚钱|日结|返利|抽奖|弹窗广告",
        RegexOptions.IgnoreCase | RegexOptions.Compiled | RegexOptions.CultureInvariant);

    /// <summary>浏览器进程:标签页弹窗属于正常网页行为,智能模式不碰。</summary>
    private static readonly string[] BrowserProcesses =
        { "chrome", "msedge", "firefox", "opera", "brave", "vivaldi", "360se", "360chrome", "qqbrowser", "baidubrowser" };

    private static readonly string[] SystemClassPrefixes =
        {
            "Progman", "WorkerW", "Shell_TrayWnd", "Shell_SecondaryTrayWnd", "Shell_CharmWindow",
            "SysListView32", "SysHeader32", "Windows.UI.Core.", "Windows.UI.Composition.",
            "XamlExplorerHost", "ApplicationFrameWindow", "ConsoleWindowClass",
            "CabinetWClass", "ExploreWClass", "FirefoxWindow",
        };

    public bool IsRunning { get; private set; }

    public PopupMonitor(AppConfig config)
    {
        _config = config;
        _ownPid = Environment.ProcessId;
        _builtin = RuleStore.LoadBuiltin();
        _timer = new System.Windows.Forms.Timer { Interval = ScanIntervalMs };
        _timer.Tick += (_, _) => Scan();
    }

    public void Start()
    {
        if (IsRunning) return;
        IsRunning = true;
        _timer.Start();
    }

    public void Stop()
    {
        IsRunning = false;
        _timer.Stop();
    }

    public IReadOnlyList<BlockRule> BuiltinRules => _builtin;

    public void ReloadBuiltin()
    {
        try
        {
            _builtin = RuleStore.LoadBuiltin();
        }
        catch
        {
            // 保持旧规则
        }
    }

    public void Dispose()
    {
        Stop();
        _timer.Dispose();
    }

    // ------------------------------------------------------------------ 扫描

    private void Scan()
    {
        if (!_config.Enabled) return;
        var knownBefore = _knownWindows.Count;
        var alive = new HashSet<long>();
        try
        {
            NativeMethods.EnumWindows((hWnd, _) =>
            {
                alive.Add(hWnd.ToInt64());
                if (!_knownWindows.Contains(hWnd.ToInt64())) InspectNewWindow(hWnd);
                return true;
            }, IntPtr.Zero);
        }
        catch
        {
            // 枚举失败跳过本轮
        }
        // 只保留仍存在的窗口;窗口销毁后句柄值可能被系统复用,必须及时清掉
        _knownWindows.IntersectWith(alive);

        // 自适应频率:连续多轮没有新窗口时降频扫描,发现新窗口立即恢复
        if (_knownWindows.Count > knownBefore)
        {
            _quietScans = 0;
            if (_timer.Interval != ScanIntervalMs) _timer.Interval = ScanIntervalMs;
        }
        else if (++_quietScans >= QuietScansBeforeIdle && _timer.Interval != IdleScanIntervalMs)
        {
            _timer.Interval = IdleScanIntervalMs;
        }
    }

    private void InspectNewWindow(IntPtr hWnd)
    {
        try
        {
            InspectNewWindowCore(hWnd);
        }
        catch
        {
            // 单个窗口的句柄失效、权限不足等异常不应中断整轮扫描
        }
    }

    private void InspectNewWindowCore(IntPtr hWnd)
    {
        if (!NativeMethods.IsWindowVisible(hWnd) || NativeMethods.IsIconic(hWnd)) return;
        if (IsCloaked(hWnd)) return;

        _knownWindows.Add(hWnd.ToInt64());

        var className = GetClassName(hWnd);
        var title = GetTitle(hWnd);
        NativeMethods.GetWindowThreadProcessId(hWnd, out var pid);
        if (pid == 0 || pid == (uint)_ownPid) return;

        var process = GetProcessName(pid);
        if (IsSystemWindow(process, className)) return;

        NativeMethods.GetWindowRect(hWnd, out var rect);
        var info = new WindowInfo(hWnd, pid, process, className, title,
            rect.Right - rect.Left, rect.Bottom - rect.Top,
            NativeMethods.GetWindowLong(hWnd, NativeMethods.GWL_STYLE),
            NativeMethods.GetWindowLong(hWnd, NativeMethods.GWL_EXSTYLE));

        if (IsFullscreenCover(info)) return;
        if (IsWhitelisted(info)) return;

        if (_config.BuiltinRulesEnabled)
        {
            foreach (var rule in _builtin)
            {
                if (rule.Matches(info.Process, info.Class, info.Title))
                {
                    Apply(rule.Name, rule.Action, info, smart: false);
                    return;
                }
            }
        }

        foreach (var rule in _config.CustomRules)
        {
            if (rule.Matches(info.Process, info.Class, info.Title))
            {
                Apply(rule.Name, rule.Action, info, smart: false);
                return;
            }
        }

        if (_config.SmartMode && LooksLikeAdPopup(info))
        {
            Apply("智能识别", "close", info, smart: true);
        }
    }

    // ------------------------------------------------------------------ 判定

    private bool IsWhitelisted(WindowInfo w)
    {
        foreach (var entry in _config.Whitelist)
        {
            if (string.IsNullOrWhiteSpace(entry)) continue;
            if (Contains(w.Process, entry) || Contains(w.Title, entry)) return true;
        }
        return false;
    }

    private static bool Contains(string text, string pattern)
    {
        try
        {
            return Regex.IsMatch(text, pattern.Trim(), RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
        }
        catch (ArgumentException)
        {
            return text.Contains(pattern.Trim(), StringComparison.OrdinalIgnoreCase);
        }
    }

    private static bool LooksLikeAdPopup(WindowInfo w)
    {
        if (BrowserProcesses.Any(b => w.Process.StartsWith(b, StringComparison.OrdinalIgnoreCase))) return false;
        if (string.IsNullOrWhiteSpace(w.Title) && string.IsNullOrWhiteSpace(w.Class)) return false;

        bool popupStyle = (w.ExStyle & NativeMethods.WS_EX_TOOLWINDOW) != 0 ||
                          (w.Style & NativeMethods.WS_POPUP) != 0;
        if (!popupStyle) return false;
        if ((w.ExStyle & NativeMethods.WS_EX_APPWINDOW) != 0) return false;

        // 尺寸约束:典型广告弹窗,不是全屏或细长条
        if (w.Width < 200 || w.Width > 1200) return false;
        if (w.Height < 140 || w.Height > 900) return false;

        return AdKeywords.IsMatch(w.Title) || AdKeywords.IsMatch(w.Class);
    }

    private static bool IsSystemWindow(string process, string className)
    {
        if (process.Length == 0) return true;
        foreach (var prefix in SystemClassPrefixes)
        {
            if (className.StartsWith(prefix, StringComparison.OrdinalIgnoreCase)) return true;
        }
        return false;
    }

    private static bool IsFullscreenCover(WindowInfo w)
    {
        try
        {
            var bounds = System.Windows.Forms.Screen.FromHandle(w.Hwnd).Bounds;
            long screenArea = (long)bounds.Width * bounds.Height;
            long windowArea = (long)w.Width * w.Height;
            return screenArea > 0 && windowArea >= screenArea * 0.85;
        }
        catch
        {
            // 句柄失效或跨屏异常时按“非全屏”放行,宁可漏拦不可误杀
            return false;
        }
    }

    private static bool IsCloaked(IntPtr hWnd)
    {
        int cloaked;
        try
        {
            return NativeMethods.DwmGetWindowAttribute(hWnd, NativeMethods.DWMWA_CLOAKED,
                out cloaked, sizeof(int)) == 0 && cloaked != 0;
        }
        catch (DllNotFoundException)
        {
            return false;
        }
    }

    // ------------------------------------------------------------------ 处置

    private void Apply(string ruleName, string action, WindowInfo w, bool smart)
    {
        // 句柄可能在判定与处置之间被销毁,动作前先确认窗口仍然存在
        if (!NativeMethods.IsWindow(w.Hwnd)) return;

        switch (action)
        {
            case "hide":
                NativeMethods.ShowWindow(w.Hwnd, NativeMethods.SW_HIDE);
                break;

            case "kill":
                // 仅结束经身份复核的原进程,不再补发 WM_CLOSE,避免 PID 复用时误伤
                KillProcessTreeForWindow(w);
                break;

            default:
                NativeMethods.PostMessage(w.Hwnd, NativeMethods.WM_CLOSE, IntPtr.Zero, IntPtr.Zero);
                break;
        }

        var args = new BlockEventArgs(ruleName, w.Process, w.Title, action, smart);
        Blocked?.Invoke(args);
    }

    private static void KillProcessTreeForWindow(WindowInfo w)
    {
        try
        {
            // 复核窗口当前归属的 PID 与判定时一致、进程名一致,防止句柄复用导致误杀
            NativeMethods.GetWindowThreadProcessId(w.Hwnd, out var pidNow);
            if (pidNow == 0 || pidNow != w.Pid) return;
            using var proc = Process.GetProcessById((int)pidNow);
            if (proc.HasExited) return;
            if (!proc.ProcessName.Equals(w.Process, StringComparison.OrdinalIgnoreCase)) return;
            proc.Kill(entireProcessTree: true);
        }
        catch
        {
            // 进程可能刚好自行退出
        }
    }

    // ------------------------------------------------------------------ 工具

    private readonly record struct WindowInfo(
        IntPtr Hwnd, uint Pid, string Process, string Class, string Title,
        int Width, int Height, int Style, int ExStyle);

    private string GetProcessName(uint pid)
    {
        if (_processNameCache.TryGetValue(pid, out var cached)) return cached;
        string name = "";
        try
        {
            name = Process.GetProcessById((int)pid).ProcessName.ToLowerInvariant();
        }
        catch
        {
            // 进程可能已退出
        }
        if (_processNameCache.Count > 4096) _processNameCache.Clear();
        _processNameCache[pid] = name;
        return name;
    }

    private static string GetTitle(IntPtr hWnd)
    {
        // 先查询实际长度再分配,避免复杂标题被固定缓冲区截断导致规则漏命中
        int len;
        try
        {
            len = NativeMethods.GetWindowTextLength(hWnd);
        }
        catch
        {
            return string.Empty;
        }
        var capacity = Math.Max(len + 1, 64);
        var sb = new StringBuilder(capacity);
        NativeMethods.GetWindowText(hWnd, sb, capacity);
        return sb.ToString();
    }

    private static string GetClassName(IntPtr hWnd)
    {
        var sb = new StringBuilder(1024);
        NativeMethods.GetClassName(hWnd, sb, 1024);
        return sb.ToString();
    }

    /// <summary>
    /// 运行时绘制托盘图标,避免在仓库中存放二进制资源。
    /// Icon.FromHandle 不接管 HICON 所有权,调用方须在图标用完后调用 DestroyIcon 释放。
    /// </summary>
    public static Icon CreateTrayIcon(out IntPtr hIcon)
    {
        using var bmp = new Bitmap(32, 32);
        using (var g = Graphics.FromImage(bmp))
        {
            g.SmoothingMode = SmoothingMode.AntiAlias;
            using var circle = new SolidBrush(Color.FromArgb(27, 122, 67));
            g.FillEllipse(circle, 1, 1, 30, 30);
            using var pen = new Pen(Color.White, 4f)
            {
                StartCap = LineCap.Round,
                EndCap = LineCap.Round,
            };
            g.DrawLine(pen, 9, 17, 14, 22);
            g.DrawLine(pen, 14, 22, 23, 10);
        }
        hIcon = bmp.GetHicon();
        return Icon.FromHandle(hIcon);
    }
}
