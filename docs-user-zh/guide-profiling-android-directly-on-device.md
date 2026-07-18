# 直接在设备上分析 Firefox for Android

Firefox 分析器可以在不使用远程调试选项的情况下使用。它的灵活性稍低（无法编辑选项，且配置文件会自动上传），但它允许你在没有 PC 的情况下捕获配置文件。

## 设置

### 选择要分析的构建版本

我们建议分析来自任何发布渠道（即非调试版）的 Firefox 构建版本，无论是从 Google Play 下载、Taskcluster 获取还是本地构建。

### 在移动设备上启用隐藏设置

要启用隐藏设置，请按照以下步骤操作：

- 点击 [URL 栏旁边的三点图标](./images/about-url.png)
- 选择 ["Settings"（设置）选项](./images/settings-menu.png)。
- 滚动到设置页面底部并选择 "About Firefox"（关于 Firefox）
- 点击 "Firefox" 徽标 5 次。[屏幕底部应出现一个提示，显示解锁隐藏菜单前剩余的点击次数](./images/secret-menu-toast.png)。
- 返回 "Settings"（设置）屏幕并滚动到底部，你应该能看到 ["Start Profiler"（启动分析器）选项](./images/start-profiler.png)。

## 使用说明

### 启动分析器

- 点击 "Start Profiler"（启动分析器），你应该会看到一个对话框出现。
- 选择与你要分析的用途最匹配的四个选项之一。
- 点击 "Start Profiler"（启动分析器），应会出现一个显示 "Profiler started"（分析器已启动）消息的提示。

### 停止分析器

- 返回设置屏幕
- 滚动到底部，你应该会看到一个 "Stop profiler"（停止分析器）选项替换了原来的 "Start Profiler"（启动分析器）选项。
- 点击后，你会看到一个包含有关配置文件中包含信息的警告的对话框。
- 停止后，已完成记录的配置文件 URL 将被复制到你的剪贴板，你可以随后使用它进行分享。
