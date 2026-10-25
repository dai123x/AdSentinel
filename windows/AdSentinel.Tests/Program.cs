using AdSentinel;

static void Check(bool condition, string message)
{
    if (!condition) throw new Exception(message);
}

var valid = RuleStore.LoadFromJson("""[{"process":"^ad-app$","title":"promotion"}]""")[0];
Check(valid.Matches("ad-app", "window", "Promotion"), "A valid rule must still match.");
Check(!valid.Matches("editor", "window", "Promotion"), "Every configured field must match.");

foreach (var json in new[]
{
    """[{"process":"[","title":"promotion"}]""",
    """[{"process":"^ad-app$","title":"["}]""",
    """[{"process":"^ad-app$","class":"["}]""",
})
{
    var rule = RuleStore.LoadFromJson(json)[0];
    Check(!rule.Matches("ad-app", "window", "promotion"), "An invalid pattern must reject the entire rule.");
}

valid.Process = "[";
valid.Compile();
Check(!valid.Matches("ad-app", "window", "promotion"), "Recompilation must not reuse a stale valid rule.");
Console.WriteLine("Rule matching regression checks passed.");
