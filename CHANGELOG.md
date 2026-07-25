# Changelog

## 3.0.0

Release date: 2026-07-25

### English

- Migrated the module from the legacy Xposed API to modern libxposed API 102.
- Restored compatibility with Microsoft Edge 150 (`150.0.4078.81`).
- Restored “Replace New Tab with Home” by supporting Edge 150's
  `EdgeBottomNavBarLayout` while retaining the Edge 149 fallback.
- Made external-download metadata extraction resilient to Chromium field
  obfuscation and nullable request headers.
- Fixed the Edge settings entry for the AppCompat menu implementation used by
  Edge 150.
- Updated the new-tab and overflow-button DexKit matching rules for Edge 150.
- Added support for Custom Tab activity subclasses.
- Isolated hook installation failures so one incompatible feature does not
  prevent the remaining features from loading, and added diagnostic logging.
- Added unit coverage for legacy, Edge 150, and structurally renamed download
  metadata layouts.

### 中文

- 从旧版 Xposed API 迁移至现代 libxposed API 102。
- 恢复对 Microsoft Edge 150（`150.0.4078.81`）的兼容。
- 适配 Edge 150 的 `EdgeBottomNavBarLayout`，恢复“将新标签页替换为主页按钮”，
  同时保留 Edge 149 及更早版本的回退。
- 下载信息改为按对象结构解析，兼容 Chromium 混淆字段变化以及可能为空的请求头。
- 修复 Edge 150 使用 AppCompat 菜单实现后，EdgeX 设置入口无法显示的问题。
- 更新新标签页按钮和更多按钮长按功能的 DexKit 匹配规则。
- 支持 Custom Tab 的派生 Activity。
- 各功能独立安装 Hook，单个功能失配不会阻止其他功能加载，并增加诊断日志。
- 增加旧版、Edge 150 和字段重命名下载模型的单元测试。

### Requirements and validation

- Requires an Xposed framework implementing libxposed API 102.
- Statically verified against Edge `149.0.4022.105` and `150.0.4078.81`.
- `testDebugUnitTest`, `assembleDebug`, and `assembleRelease` pass.
