# 规则编写指南

AdSentinel 的两端都使用 **JSON 规则 + 正则匹配**。规则在本地执行,任何修改都不会上传。

## Android 规则

文件路径:`android/app/src/main/assets/rules/builtin.json`(内置)。
用户自定义规则在 App 内「导入规则」后保存在应用私有目录,与内置规则合并生效。

### 顶层结构

```json
{
  "version": 1,
  "name": "AdSentinel 内置规则",
  "updatedAt": "2026-10-04",
  "rules": [ { ...单条规则... } ]
}
```

### 单条规则字段

所有正则为**部分匹配**(`containsMatchIn`),需要全匹配请自行加 `^` `$`。

| 字段 | 类型 | 必填 | 说明 |
|------|------|:---:|------|
| `id` | string | ✅ | 唯一 ID,也用于冷却去重 |
| `name` | string | ✅ | 显示名称,拦截提示中出现 |
| `apps` | string[] | ✅ | 目标应用包名,每项为正则;`["*"]` 表示任意应用 |
| `activity` | string[]? | — | 目标窗口 Activity 类名正则(用于把规则收窄到广告页) |
| `text` | string[]? | — | 节点文字正则列表(命中任意一条即可) |
| `contentDesc` | string[]? | — | 无障碍 contentDescription 正则列表 |
| `viewId` | string? | — | 控件 id 资源名正则(如 `^skip$`) |
| `zone` | string? | — | `topRight`:节点必须是屏幕右上角的小尺寸控件(跳过按钮惯例位置)。**识别图片型跳过按钮、防止误点正常界面的关键约束** |
| `matchAny` | bool? | — | `true`:text / contentDesc / viewId 完全并列,任一命中即可(配合 zone 用);默认 `false`:viewId 是额外的且条件 |
| `action` | string | ✅ | `click`(点击第一个命中)/ `clickAll`(点掉全部)/ `back`(按返回) |
| `cooldownMs` | int? | — | 同规则同应用两次触发之间的最小间隔,默认 `800` |
| `enabled` | bool? | — | 默认 `true` |

> `text` / `contentDesc` / `viewId` 至少写一个,否则规则永远不会生效。
> 同一规则内多个条件默认是 **或** 关系(任一命中即点击);`viewId` 若存在且 `matchAny` 不为 true 则是额外 **且** 条件。
> 想匹配「无文字的图片型跳过按钮」或「只有倒计时数字的圆形按钮」时,用 `zone: "topRight"` + `matchAny: true` + `viewId` 正则,区域与尺寸约束能把误点率压到最低。

### 示例

```json
{
  "id": "pdd-splash-skip",
  "name": "拼多多开屏跳过",
  "apps": ["com\\.xingin\\.xhs"],
  "activity": [".*SplashAd.*", ".*AdActivity"],
  "text": ["^跳过(\\s*\\d+)?\\s*$", "以后再说"],
  "action": "click"
}
```

### 编写建议

1. 能带 `apps` 就不要用 `["*"]`,能带 `activity` 就再加上,把误伤降到最低。
2. 「跳过」类通用规则已经被内置,只需为特殊广告页补充规则。
3. 用 `adb shell dumpsys window | grep mCurrentFocus` 可查看当前 Activity 全名。

## Windows 规则

内置规则:`windows/AdSentinel/assets/builtin_rules.json`(以嵌入资源编译)。
用户自定义规则写入 `%APPDATA%\AdSentinel\config.json` 的 `customRules` 数组,格式相同。

### 单条规则字段

| 字段 | 类型 | 必填 | 说明 |
|------|------|:---:|------|
| `id` | string | ✅ | 唯一 ID |
| `name` | string | ✅ | 显示名称 |
| `process` | string? | — | 进程名(不含 `.exe`)正则,忽略大小写 |
| `class` | string? | — | Win32 窗口类名正则 |
| `title` | string? | — | 窗口标题正则 |
| `action` | string | ✅ | `close`(发 WM_CLOSE)/ `kill`(结束进程树)/ `hide` |

> `process` / `class` / `title` 至少写一个;多个字段之间是 **且** 关系。所有正则忽略大小写。

### 示例

```json
{
  "id": "win-myapp-popup",
  "name": "某软件弹窗",
  "process": "^someadware$",
  "title": "热点|福利",
  "action": "close"
}
```

### 编写建议

1. 优先 `process` + `title` 双条件;只写 `title` 宽泛关键词容易误杀正常窗口。
2. 动作默认用 `close`(相当于点 ×),只有确认该弹窗关不掉时才用 `kill`。
3. 查看窗口类名:用微软官方 [Inspect](https://learn.microsoft.com/windows/apps/winui/winappi/inspect-land) 工具,或 `Get-Process | Where-Object MainWindowTitle` 粗查。
4. 正则使用 .NET 语法,注意 `|` 与转义;中文直接写即可。

## 提交规则

把新规则追加进对应 `builtin_rules.json` 后发 PR,要求:

- 附上截图或广告来源说明
- 保持 `id` 全局唯一,建议前缀:`win-xxx` / `android-xxx`
- 不收录针对正常软件正常功能的规则(例如关闭系统更新提示)
