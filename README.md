# 日程清单 · Daily Planner

基于 [Tasks.org](https://github.com/tasks/tasks) 的安卓待办应用，新增中文日程文字批量导入，适合把每天的时间安排和条目清单直接带到手机桌面。

[下载安装包](https://github.com/zzzmmm115/daily-planner-android/releases/latest) · [中文使用说明](docs/daily-planner/USER-GUIDE.zh-CN.md) · [导入示例](docs/daily-planner/import-example.txt) · [上游项目说明](README.upstream.md)

## 主要功能

- 中文界面，可在非中文系统中单独选择简体中文。
- 按“具体日期／时间＋安排”或“逐条待办”导入，也可混合粘贴。
- 支持普通列表、编号、Markdown复选框、时间表、缩进备注和完成标准。
- 保存前逐项预览和编辑，可选择目标清单、日期、开始及结束时间。
- 检查重复项（包含已完成任务）、错误日期和时间，原文已完成项默认不选中。
- 从其他App分享多行纯文字进入导入页；连续分享会确认是否替换草稿。
- 桌面小部件显示待办和时间段，可直接勾选完成；关闭显示已完成项后自动隐藏。

导入解析完全离线，一次最多200项。本版使用原App的任务开始和截止字段保存时间安排。

## 安装与使用

1. 从 [Releases](https://github.com/zzzmmm115/daily-planner-android/releases/latest) 下载 `daily-planner-text-import-v1.apk` 并安装，最低要求Android 8.0。
2. 应用名为“日程清单”或“Daily Planner”，包名为 `org.tasks.daily`。可以与原版Tasks同时安装，两者数据各自保存。
3. 初次打开可以选择“继续但不同步”，使用本地清单。需要中文时，在App settings → Look and feel → Language选择简体中文。
4. 在任务清单右上角菜单选择 **文字导入日程**，粘贴、预览、修改，再导入。
5. 添加本应用的桌面小部件。关闭“显示已完成项”；若要提前看未来安排，开启“显示未开始任务”。

```text
日期：2026-10-29
08:00-09:00 阅读教材
09:15-10:00 整理笔记
- [ ] 英语单词复习
- [ ] 准备明天的资料
    完成标准：列出两个疑问
```

没有具体时间的条目只保存日期，不自动分配时段。跨日请明确写 `23:00-次日00:30 阅读` 或 `23:00-24:00 阅读`。

## 从源码构建

使用JDK 25、Android SDK Platform 37和Build Tools 37.0.0；Gradle使用仓库自带Wrapper。SDK路径写入本地 `local.properties`，例如 `sdk.dir=D:/Android/Sdk`，不要提交该文件。

```powershell
.\gradlew.bat :app:assembleGenericDebug -PdailyBuild=true --no-daemon --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8' '-Pkotlin.daemon.jvmargs=-Xmx2g'
```

也可以在Windows运行 `scripts/build-daily-planner.ps1`，自动复制APK到 `dist` 目录。在Linux/macOS用 `./gradlew` 执行同样的Gradle任务。

构建输出：`app/build/outputs/apk/generic/debug/app-generic-debug.apk`。`-PdailyBuild=true`生成独立包名及名称；不带该参数沿用上游包名。Windows拉取源码时须保留语言资源的符号链接，可启用开发者模式后使用 `git -c core.symlinks=true clone ...`。

本次发布为开发签名版。源码重新构建使用本机开发签名，可能无法直接覆盖本次发布包；切换签名前请导出备份。后续更新发布需保持签名一致。

## 验证与范围

解析器20项单元测试通过；Android 15模拟器已验证混合导入、预览编辑、查重、备注保存、连续分享、错误日期拦截、小部件时间段显示及勾选隐藏。详情见 [验证说明](docs/daily-planner/VALIDATION.zh-CN.md)。

小米HyperOS真机的桌面刷新、后台和提醒表现仍待使用验证。目前没有新增日历时间轴、在线AI解析或Obsidian双向同步。

## 来源与许可证

本项目基于 `tasks/tasks` 的提交 `4ea72a1d26c5c4701bce02feb6c21f1d2b15fd1e`，保留上游版权及 [GNU GPL v3许可证](LICENSE)，修改部分按同一许可证提供。首次修改发布版本为 `15.13-daily.1`；变更记录见 [CHANGELOG](docs/daily-planner/CHANGELOG.md)。

这是个人二次开发版本。Tasks.org上游项目说明、历史和致谢见 [README.upstream.md](README.upstream.md)。
