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
    // 读写共用同一套选项:写入为 camelCase,读取时忽略大小写,
    // 保证旧配置文件(小写字段)与新配置都能正确还原
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        WriteIndented = true,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        PropertyNameCaseInsensitive = true,
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
                var config = JsonSerializer.Deserialize<AppConfig>(File.ReadAllText(ConfigPath), JsonOptions);
                if (config is not null)
                {
                    // 自定义规则来自配置文件,未经 Compile() 无法匹配,此处统一编译
                    foreach (var rule in config.CustomRules) rule.Compile();
                    if (config.CustomRules.Count > 0)
                    {
                        Logger.Append($"已加载并编译 {config.CustomRules.Count} 条自定义规则");
                    }
                    return config;
                }
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
        File.WriteAllText(ConfigPath, JsonSerializer.Serialize(config, JsonOptions));
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
