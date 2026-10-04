# AdSentinel · 广告哨兵

<p align="center">
  <a href="https://github.com/dai123x/AdSentinel/actions"><img alt="CI" src="https://github.com/dai123x/AdSentinel/actions/workflows/android.yml/badge.svg"></a>
  <a href="https://github.com/dai123x/AdSentinel/actions/workflows/windows.yml"><img alt="Windows CI" src="https://github.com/dai123x/AdSentinel/actions/workflows/windows.yml/badge.svg"></a>
  <a href="LICENSE"><img alt="License: GPL-3.0" src="https://img.shields.io/badge/License-GPL--3.0-blue.svg"></a>
  <img alt="Platform" src="https://img.shields.io/badge/platform-Windows%20%7C%20Android-green">
</p>

**AdSentinel(广告哨兵)** 是一个完全开源、不上传任何数据的跨平台广告拦截工具:

- 🪟 **Windows 端** —— 自动识别并关闭流氓软件的弹窗广告(热点资讯、红包福利、推广促销等)
- 📱 **Android 端** —— 自动点击开屏广告的「跳过」按钮,识别摇一摇/扭一扭广告页并自动返回

> 原则:**无 root、无网络权限、不收集任何数据**。所有规则匹配都在本地完成。

---

## 功能特性

### Windows 端(弹窗拦截)
- 开机后台静默运行,托盘图标常驻,一键暂停/恢复
- 内置常见流氓弹窗规则(今日热点、高清视频、红包福利、秒杀促销、垃圾清理诱导等)
- 可选「智能模式」:启发式识别突然弹出、无任务栏图标、标题含诱导关键词的陌生窗口
- 规则引擎支持按 **进程名 / 窗口类名 / 窗口标题** 正则匹配,动作支持 **关闭 / 隐藏 / 结束进程**
- 白名单保护,绝不误杀正常窗口;完整拦截日志可回溯

### Android 端(开屏 + 摇一摇)
- 无障碍服务自动点击「跳过」「以后再说」等按钮,覆盖主流开屏广告 SDK(穿山甲、优量汇/广点通、快手联盟、百度、Mintegral、Sigmob、Unity Ads、AppLovin、InMobi 等)
- **摇一摇防护**:识别广告 SDK 的广告页窗口,点击跳过失败时自动返回,避免手一抖就跳转电商/游戏页面
- 用户可导入自定义规则(JSON),规则格式见 [docs/rules.md](docs/rules.md)
- 应用白名单,重要 App 完全不受影响
- 拦截统计与提示,每一次拦截都看得见

### 隐私承诺
- Android 端 **不申请 INTERNET 权限**(可在安装信息中核实),物理上不可能联网上报
- Windows 端不发起任何网络请求
- 不包含任何统计 SDK、崩溃上报 SDK

---

## 下载安装

前往 [Releases](https://github.com/dai123x/AdSentinel/releases) 页面下载对应平台的最新版本。

### Windows
1. 下载 `AdSentinel-win-x64.zip` 并解压(自包含单文件,无需安装 .NET 运行时)
2. 运行 `AdSentinel.exe`,托盘区出现盾牌图标即开始工作
3. 双击托盘图标可打开设置;建议勾选「开机自启」
4. 若杀软误报,请将 `AdSentinel.exe` 加入信任列表(拦截弹窗的工具容易被误判,代码完全开源可审计)

### Android
1. 下载 `AdSentinel-android-debug.apk` 安装(debug 签名,便于直接安装)
2. 打开应用,按引导跳转 **系统设置 → 无障碍 → 已下载的应用 → AdSentinel(广告哨兵)** 并开启
3. 返回应用确认状态为「运行中」即可
4. 开启后第一次遇到开屏广告,会自动帮你点掉「跳过」

> **为什么要开启无障碍服务?** 无障碍服务是安卓上唯一能在不 root 的前提下读取屏幕内容并模拟点击的正规途径(李跳跳、GKD 等同类工具同理)。AdSentinel 只读取窗口文字用于匹配规则,不读取输入框密码内容,不联网,代码全部开源。

---

## 从源码构建

| 平台 | 要求 | 命令 |
|------|------|------|
| Android | JDK 17 + Android SDK | `cd android && ./gradlew assembleDebug` |
| Windows | .NET 8 SDK,Windows 系统 | `dotnet publish windows/AdSentinel -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true` |

两端都有 GitHub Actions 云构建:推送到 `main` 分支后自动编译,产物在 Actions 的 Artifacts 中;打 `v*` 标签自动发布 Release。

## 项目结构

```
AdSentinel/
├── android/               # Android 客户端(Kotlin,无障碍自动跳过引擎)
│   └── app/src/main/java/cloud/daixuan/adsentinel/
│       ├── service/       # SkipAdService —— 无障碍核心服务
│       ├── engine/        # 规则引擎(匹配、遍历、冷却、自动返回)
│       └── ui/            # 设置界面、白名单管理
├── windows/               # Windows 客户端(C# WinForms,Win32 弹窗拦截)
│   └── AdSentinel/
│       ├── PopupMonitor.cs   # 窗口枚举与拦截主循环
│       ├── NativeMethods.cs  # Win32 P/Invoke
│       └── Rules.cs          # 规则加载与匹配
├── docs/
│   └── rules.md           # 规则格式说明与编写指南
└── .github/workflows/     # 双端 CI
```

## 参与贡献

欢迎通过 Issue 反馈误杀/漏拦案例(附进程名、窗口标题或 App 包名),通过 PR 提交规则。规则文件与贡献流程见 [docs/rules.md](docs/rules.md) 与 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 常见问题

**Q: 摇一摇广告能 100% 拦住吗?**
A: 不 root 的情况下,任何 App 都无法直接禁用另一个 App 的加速度传感器——这是系统限制。AdSentinel 的策略是:识别到广告 SDK 的广告页后立即点击「跳过」,没有跳过按钮则自动返回,把摇一摇误触的窗口期压缩到几乎为零。root 用户可以使用传感器禁用类模块获得更强的防护。

**Q: Windows 端会不会误关正常窗口?**
A: 默认只启用保守的内置规则(标题精准命中已知流氓弹窗关键词);更激进的「智能模式」默认关闭。白名单支持进程名/标题正则,并可随时从拦截日志回查。

**Q: 会消耗很多电吗?**
A: Android 端仅监听窗口切换事件并做浅层匹配,无轮询、无网络;Windows 端定时器间隔 800ms,内存占用约 20~30MB。

## 免责声明

本项目仅用于拦截设备上出现的广告,提升个人使用体验,不修改任何第三方应用的数据与行为,不绕过任何付费服务。请遵守当地法律法规使用。

## 开源协议

[GPL-3.0](LICENSE) © [dai123x](https://github.com/dai123x)
