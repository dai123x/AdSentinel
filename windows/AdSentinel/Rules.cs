using System.Text.Json;
using System.Text.RegularExpressions;

namespace AdSentinel;

/// <summary>单条弹窗拦截规则。字段之间是「且」关系,至少要给一个匹配字段。</summary>
public sealed class BlockRule
{
    public string Id { get; set; } = "";
    public string Name { get; set; } = "";
    public string? Process { get; set; }
    public string? Class { get; set; }
    public string? Title { get; set; }
    public string Action { get; set; } = "close";
    public bool Enabled { get; set; } = true;

    private Regex? _process;
    private Regex? _class;
    private Regex? _title;

    public void Compile()
    {
        _process = CompilePattern(Process);
        _class = CompilePattern(Class);
        _title = CompilePattern(Title);
    }

    public bool Matches(string process, string className, string title)
    {
        if (!Enabled) return false;
        if (_process is null && _class is null && _title is null) return false;
        if (_process is not null && !_process.IsMatch(process)) return false;
        if (_class is not null && !_class.IsMatch(className)) return false;
        if (_title is not null && !_title.IsMatch(title)) return false;
        return true;
    }

    private static Regex? CompilePattern(string? pattern)
    {
        if (string.IsNullOrWhiteSpace(pattern)) return null;
        try
        {
            return new Regex(pattern, RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
        }
        catch (ArgumentException)
        {
            return null;
        }
    }
}

public static class RuleStore
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNameCaseInsensitive = true,
        ReadCommentHandling = JsonCommentHandling.Skip,
        AllowTrailingCommas = true,
    };

    public static List<BlockRule> LoadFromJson(string json)
    {
        var rules = JsonSerializer.Deserialize<List<BlockRule>>(json, JsonOptions) ?? new List<BlockRule>();
        foreach (var rule in rules) rule.Compile();
        return rules;
    }

    public static List<BlockRule> LoadBuiltin()
    {
        var assembly = typeof(RuleStore).Assembly;
        using var stream = assembly.GetManifestResourceStream("AdSentinel.assets.builtin_rules.json")
            ?? throw new InvalidOperationException("内置规则资源缺失");
        using var reader = new StreamReader(stream);
        return LoadFromJson(reader.ReadToEnd());
    }
}
