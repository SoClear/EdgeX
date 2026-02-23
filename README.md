# EdgeX

[![Stars](https://img.shields.io/github/stars/SoClear/EdgeX)](https://github.com/SoClear/EdgeX)

[English](#english) | [中文](#中文)

---

## English

An Xposed module for Microsoft Edge on Android, designed to enhance your browsing experience with UI tweaks and functional improvements.

### Features

- **Hide Status Bar**: Allows the webpage to display in the status bar area for a more immersive experience.
- **Remove Address Bar Bottom Padding**: Optimizes the bottom spacing of the address bar.
- **Long press Overflow to scroll to top**: Long press the "More" (overflow) button to quickly scroll to the top of the page.
- **Long press New Tab to load in-place**: Long press the "New Tab" button to load the target URL in the current tab instead of creating a new one.
- **Set New Tab Page URL**: Customize the URL for your new tab page.

### Installation

1. Install the **EdgeX** APK.
2. Enable the module in **LSPosed** (or your preferred Xposed manager).
3. Ensure **Microsoft Edge** (`com.microsoft.emmx`) is selected in the scope.
4. Open **EdgeX** and enable the desired features.
5. Restart Microsoft Edge.

### Tips

The Set New Tab Page URL feature allows you to use "New Tab" extensions on your mobile device:

1. Install any New Tab extension, such as Rainbow Tab.
2. Visit the Edge Add-ons Store.
3. Search for and navigate to the desired extension's page.
4. Extract the Extension ID from the address bar (the unique string of characters in the URL).
5. Open EdgeX and set the New Tab Page URL to `extension://[Extension ID]/[Entry File]` . For example: `extension://gjkmaoeddnpkgljohocjeejoofijgoib/index.html`

> Pretty please, give [EdgeX](https://github.com/SoClear/EdgeX) a Star!

---

## 中文

一个为安卓端 Microsoft Edge 浏览器设计的 Xposed 模块，旨在通过 UI 调整和功能改进来增强您的浏览体验。

### 功能特性

- **隐藏状态栏**：允许网页显示在状态栏区域，提供更沉浸的浏览体验。
- **移除地址栏底边距**：优化地址栏底部的间距。
- **长按更多按钮回顶部**：长按“更多”按钮即可快速滚动到页面顶部。
- **长按新标签页按钮原地加载**：长按“新标签页”按钮在当前标签页中加载，而不是新建标签页。
- **设置新标签页 URL**：自定义新标签页的起始地址。

### 安装步骤

1. 安装 **EdgeX** 应用。
2. 在 **LSPosed**（或您使用的 Xposed 管理器）中启用本模块。
3. 确保作用域已勾选 **Microsoft Edge** (`com.microsoft.emmx`)。
4. 打开 **EdgeX** ，启用相关功能。
5. 强制停止并重新打开 Microsoft Edge。

### 小技巧

**设置新标签页 URL** 功能可以实现在手机端使用新标签页类的扩展哦：

1. 安装任意新标签页扩展，例如 Rainbow Tab - 彩虹标签页。
2. 访问 [Edge-Extensions](https://microsoftedge.microsoft.com/addons/Microsoft-Edge-Extensions-Home)
3. 搜索并点击进入该新标签页扩展。
4. 在地址栏提取扩展ID。
5. 打开 **EdgeX** ，**设置新标签页 URL** 为 `extension://[扩展ID]/[扩展入口文件]` ，例如 `extension://gjkmaoeddnpkgljohocjeejoofijgoib/index.html`

> 求求你了，给个 Star 吧 [EdgeX](https://github.com/SoClear/EdgeX)
