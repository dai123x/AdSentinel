using System.Text.Json;
using System.Text.Json.Serialization;

namespace AdSentinel;

public sealed class AppConfig
{
    public bool Enabled { get; set; } = true;
    public bool SmartMode { get; set; } = false;
    public bool ShowBalloon { get; set; } = true;
    public bool StartWithWindows { get; set; } = false;
    public bool BuiltinRulesEnabled { get; set; } = true;

    /// <summary>白名单条目:进程名或标题的正则(忽略大小写),命中任一即放行。</summary>
    public List<string> Whitelist { get; set; } = new()
    {
        "explorer",
        "applicationframehost",
        "shellexperiencehost",
        "searchhost",
        "startmenuexperiencehost",
        "textinputhost",
        "msedgewebview2",
        "systemsettings",
        "taskmgr",
    };

    /// <summary>用户自定义规则,与内置规则格式一致。</summary>
    [JsonPropertyName("customRules")]
    public List<BlockRule> CustomRules { get; set; } = new();
}

public static class ConfigManager
{
    private static readonly JsonSerializerOptions WriteOptions = new()
    {
        WriteIndented = true,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
    };

    public static string Dir => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "AdSentinel");

    public static string ConfigPath => Path.Combine(Dir, "config.json");

    public static string LogPath => Path.Combine(Dir, "block.log");

    public static AppConfig Load()
    {
        try
        {
            if (File.Exists(ConfigPath))
            {
                var config = JsonSerializer.Deserialize<AppConfig>(File.ReadAllText(ConfigPath));
                if (config is not null) return config;
            }
        }
        catch
        {
            // 配置损坏时退回默认值,保证应用仍能启动
        }
        return new AppConfig();
    }

    public static void Save(AppConfig config)
    {
        Directory.CreateDirectory(Dir);
        File.WriteAllText(ConfigPath, JsonSerializer.Serialize(config, WriteOptions));
    }
}

public static class Logger
{
    private const long MaxLogBytes = 512 * 1024;

    public static void Append(string line)
    {
        try
        {
            Directory.CreateDirectory(ConfigManager.Dir);
            if (File.Exists(ConfigManager.LogPath) && new FileInfo(ConfigManager.LogPath).Length > MaxLogBytes)
            {
                File.Delete(ConfigManager.LogPath);
            }
            File.AppendAllText(ConfigManager.LogPath,
                $"{DateTime.Now:yyyy-MM-dd HH:mm:ss}  {line}{Environment.NewLine}");
        }
        catch
        {
            // 日志失败不影响主流程
        }
    }
}
