# 贡献指南

感谢关注 AdSentinel!欢迎以下几类贡献:

## 1. 反馈误杀 / 漏拦

提 [Issue](https://github.com/dai123x/AdSentinel/issues) 时请尽量带上:

- **Windows**:进程名(exe 文件名)、窗口标题、窗口类名(可用 [Inspect](https://learn.microsoft.com/windows/apps/winui/winappi/inspect-land) 或 Spy++ 查看)、截图
- **Android**:App 包名(`设置 → 应用` 中可见)、出现广告时的窗口/按钮文字、截图

## 2. 提交规则 PR

- 内置规则文件:
  - Windows:`windows/AdSentinel/assets/builtin_rules.json`
  - Android:`android/app/src/main/assets/rules/builtin.json`
- 格式说明见 [docs/rules.md](docs/rules.md)
- 原则:**宁可漏拦,不可误杀**。规则应尽量窄(进程名 + 标题双条件优先),新增规则请在 PR 描述中附截图或来源依据。

## 3. 代码 PR

- Android:Kotlin,minSdk 24,不引入任何带网络能力的依赖
- Windows:C# / .NET 8 WinForms,只用标准库
- 提交信息遵循 [Conventional Commits](https://www.conventionalcommits.org/zh-hans/):`feat(android): ...`、`fix(windows): ...`

## 4. 硬性红线

以下改动一律不接受:

- 任何形式的联网、统计、上报、远程配置拉取
- 修改规则行为以外的用户数据
- 引入闭源二进制依赖
