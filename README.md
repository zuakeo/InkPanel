# InkPanel · 海信墨水屏「一键亮度面板」

把海信墨水屏设备下拉通知栏里那块占半屏的「亮度 / 色温 / 显示模式」面板，
收成一个快捷设置格子。点格子 → 通知栏收起 → 系统原生那块**五条滑杆**的悬浮面板浮到桌面上：

**亮度 · 色温 · 对比度 · 黑色 · 白色**

> 不刷机、不换 SystemUI、不改 ROM。一个 APK + 一个 Magisk 模块，全部可逆。

适配机型：**海信 Touch Lite（HITV205N）**，Android 11 / Vision UI。
同代 Vision 系统的其他海信墨水屏（A5 / A7 / A9）结构类似，但未验证。

---

## 一、效果

| 之前 | 现在 |
|---|---|
| 下拉里固定占一块：亮度滑杆 + 黑夜/手动/自动/关闭 + 色温滑杆 + 四种显示模式 | 下拉里只剩一个「亮度」格子，通知紧贴格子下方 |
| 想边看正文边调色温，做不到 | 点格子，面板浮在内容之上，**正文还看得见**，实时调 |

面板本身是系统原生的（`KEYGUARD_DIALOG` 类型窗口，`NOT_TOUCH_MODAL`，面板外仍可点击）。
本项目没有重画 UI，只是给了它一个新的入口。

---

## 二、架构

三层，各管一件事：

```
┌─────────────────────────────────────────────────────────┐
│  ① InkPanel.apk（普通应用）                               │
│     ├─ PanelTile : TileService   快捷设置里的「亮度」格子   │
│     └─ MainActivity              无图标、透明，仅用于预热    │
│     同时是 LSPosed 模块的载体                              │
└────────────────────────┬────────────────────────────────┘
                         │ sendBroadcast（setPackage 定向到 SystemUI）
┌────────────────────────▼────────────────────────────────┐
│  ② LSPosed 模块（注入 com.android.systemui 进程）          │
│     ├─ hook SystemUIApplication.onCreate → 注册接收器       │
│     ├─ 收到广播 → 反射调用海信私有单例                       │
│     └─ hook HmctQSSecondInkPanel 构造 → 隐藏内嵌面板         │
└────────────────────────┬────────────────────────────────┘
                         │ 反射调用
┌────────────────────────▼────────────────────────────────┐
│  ③ Magisk 模块（boot 层，root）                           │
│     开机 → 等解锁 → am start 预热 → 强制重建磁贴列表         │
└─────────────────────────────────────────────────────────┘
```

### 为什么必须三层

- **面板是系统私有的**：`SeekBarsDetailPopWindow` 在 SystemUI 的 APK 里，窗口类型是
  `KEYGUARD_DIALOG`，只有系统身份才能 `addView`。普通应用即使反编译拿到类名也画不出来。
  所以必须有 ②，在 SystemUI 进程里跑。
- **磁贴必须由独立应用提供**：`TileService` 只能声明在 APK 里，没法直接塞进 SystemUI。
  所以有 ①。
- **海信把第三方 TileService 的启动拦了**（详见坑 #6/#7/#8）：开机后必须先让应用
  "前台跑过一次"、且用户已解锁，SystemUI 才允许绑定。应用层做不到自动触发，只能靠 root。所以有 ③。

### 数据流

```
用户点「亮度」格子
  → PanelTile.onClick()
  → sendBroadcast(ACTION, package=com.android.systemui)
  → HookEntry 的 BroadcastReceiver
  → SeekBarsDetailPopWindow.getInstance(ctx)
  → isShowing() ? closeDetailWindow() : (collapsePanels(); showDetailWindow())
  → 面板出现在桌面下方，点第二次关闭
```

---

## 三、逆向出来的关键事实

这些是从 `Vision_SystemUI.apk` 的 `classes.dex` 里挖出来的（`dexdump`），也是整个项目的地基：

| 对象 | 值 |
|---|---|
| 悬浮面板类 | `com.hmct.systemui.qs.ink2.inkdetails.SeekBarsDetailPopWindow` |
| 获取实例 | `public static SeekBarsDetailPopWindow getInstance(Context)`（单例） |
| 显示 / 关闭 / 查询 | `public void showDetailWindow()` / `closeDetailWindow()` / `boolean isShowing()` |
| 悬浮窗口名 | `Ink2DetailWindow`，`ty=KEYGUARD_DIALOG`，720×1392，`NOT_TOUCH_MODAL` |
| 下拉里的内嵌面板 | `com.hmct.systemui.qs.ink2.HmctQSSecondInkPanel` |
| 内嵌面板容器 | `LinearLayout` id `0x7f0b0342`，固定高 704 |
| 外层头部 | `com.hmct.systemui.qs.HmctQuickStatusBarHeader`，固定高 828 |
| 快捷格子列表 | `Settings.Secure` 的 `sysui_qs_tiles`，`custom(pkg/cls)` 表示第三方磁贴 |
| 亮度 / 色温 / 显示模式 | `Settings.System` 的 `screen_brightness` / `screen_brightness_tctemp` / `current_epd_mode` |

> 注意：`HmctInkBrightnessTempContrast` 只是被布局文件引用，**实际从未被实例化**。
> 一开始 hook 它毫无反应，绕了很久（见坑 #3）。

---

## 四、怎么写的

**不使用 Gradle。** 全部用 SDK 自带工具直接拼，不联网、不拉依赖、可复现：

```
aapt2 compile → aapt2 link（顺便生成 R.java）
→ javac（--release 8，-encoding UTF-8）
→ d8 --min-api 30
→ python 注入 classes.dex 和 META-INF/xposed
→ zipalign
→ apksigner（debug keystore）
```

见 `build.sh`，一条命令出包。

**Xposed API 用自写桩编译。** `src/stubs/` 里手写了
`IXposedHookLoadPackage` / `XC_MethodHook` / `XposedBridge` / `XposedHelpers` 的签名，
编译时放在 classpath，**不打进 dex**（运行时由 LSPosed 提供真实实现）。
这样不用下载任何 xposed api jar。

**模块声明两套都带**，兼容新旧格式：
- `assets/xposed_init`（传统）
- `META-INF/xposed/java_init.list` + `module.prop`（LSPosed 新格式）

---

## 五、踩过的坑

按踩到的顺序，每条都花过时间：

### 1. Xposed 桩的返回类型必须精确
`findAndHookMethod` 的真实返回值是 `XC_MethodHook.Unhook`，不是 `void`。
写成 `void` 会抛 `NoSuchMethodError`——**JVM 按完整签名匹配，返回类型算在内**。
报错只说"没有这个方法"，而方法明明在，极具误导性。

### 2. `findAndHookMethod("<init>")` 在 Vector 上不可用
海信这个 LSPosed 分支（Vector 2.2 / API 102）会报
`NoSuchMethodError: ...#<init>(...)#exact`。改用 `XposedBridge.hookAllConstructors` 才行。

### 3. 内嵌面板的真身不是布局里那个类
`hmct_ink_brightness_temp_contrast.xml` 的根节点是 `HmctInkBrightnessTempContrast`，
但运行时它**从未被实例化**。真正在跑的是 `HmctQSSecondInkPanel`。
判断依据是后者的字段：`mQsPanel` / `mExpanded` / `mBrightnessButtons` / `mColorSliders`。

### 4. `qs_show_brightness=0` 是死路
SystemUI 的 dex 里确实读这个键，配套字符串也有"在快捷设置中显示亮度栏"，
但改成 0 后**重启 SystemUI 也不生效**，别浪费时间。

### 5. 藏了面板之后会留一块空白
内嵌面板的容器是**固定高 704**，藏掉里面的内容不会缩。
必须把容器改 `wrap_content`，同时把外层 `HmctQuickStatusBarHeader` 的高度减去差值（828 → 426）。
只改其中一个都没用。

### 6. 海信的自启动策略会拦后台服务
```
W MobileSafeService: checkStartServicePerm ... com.zuakeo.inkpanel false
W autorun_as: Unable to launch app ... is restricted 3
```
新装应用"从未被使用过"时，服务启动会被拒。**磁贴照样显示，但点了完全没反应、也不报错**，
这是最坑的地方——没有错误日志可看。
更狠的是：**连 root 的 `am startservice` 也一样被拒**。

### 7. 绑定还要求"用户已解锁"
锁屏状态下预热 Activity 无效。必须等 `sys.user.0.ce_available=true` 之后才行。
官方文档里没有这个条件，是试出来的。

### 8. 开机早期解析失败会把磁贴丢掉
SystemUI 在开机很早的阶段创建磁贴，那时应用还没跑过 → 解析失败 → **磁贴从面板上消失**，
而且之后不会自动重试。光重启 SystemUI 也不够，必须**改一次 `sysui_qs_tiles`**
（删掉再加回）才会触发 `QSTileHost` 重建。

### 9. `install -r` 会重置待机分组
重装后待机分组回到 `RESTRICTED(50)`，磁贴失效。等于每次装新版都要重来一遍预热。

### 10. javac 默认按 GBK 读源码
中文环境编译带中文的 Java 源码会直接报错，必须显式 `-encoding UTF-8`。

### 11. 墨水屏 `screencap` 经常是旧帧
调试时截到的可能是几秒前的画面，容易误判"没生效"。
更可靠的方式是 `uiautomator dump` 读 UI 树，或者先按一次主页键触发刷新再截图。

### 12. Vector 的兼容层是裁剪过的
不是完整 Xposed API。`XposedHelpers` 里只有部分重载，用之前先 `dexdump` 确认方法确实存在。

---

## 六、构建

需要：JDK 17、Android SDK build-tools（含 aapt2 / d8 / apksigner）、python3。

```bash
bash build.sh
# 产物：dist/InkPanel-0.1.apk
```

`build.sh` 开头的 SDK / JDK 路径按本机改一下即可。

---

## 七、安装

```bash
adb install -r dist/InkPanel-0.1.apk
```

1. 在 **LSPosed / Vector** 里启用「水墨面板」，作用域勾 **System UI**，重启设备
2. 下拉通知栏 → 长按格子进编辑 → 把「亮度」拖到第一排
3. 装 Magisk 预热模块（否则每次重启后磁贴会失效）：

```bash
adb push dist/inkpanel_warm /sdcard/
adb shell su -c "cp -r /sdcard/inkpanel_warm /data/adb/modules/inkpanel_warm"
adb shell su -c "chmod 755 /data/adb/modules/inkpanel_warm/service.sh"
```

重启 → 解锁 → 磁贴自动就绪。

> 应用本身**没有桌面图标**（故意去掉的，避免桌面多一个用不到的入口）。
> 它只以「快捷设置格子」的形式存在。

---

## 八、卸载

```bash
adb uninstall com.zuakeo.inkpanel
adb shell su -c "rm -rf /data/adb/modules/inkpanel_warm"
```

在 LSPosed 里关掉模块，重启。
唯一被改过的系统设置是 `sysui_qs_tiles`（把原来的 `float` 悬浮球换成了本模块），想恢复：

```bash
adb shell settings put secure sysui_qs_tiles \
  "wifi,bt,soundmode,collectarticle,rotation,dynamicrefresh,float,readmode,mutualtrans,pinscreen"
```

---

## 九、已知限制

- 只在这一个机型 / 一个 ROM 版本上验证过。海信改版后类名或布局一变就得重新逆向。
- 磁贴冷启动约 1~2 秒（墨水屏全刷），比原生格子慢。
- 重装 APK 后需要重启一次才能恢复磁贴。
- 面板位置和尺寸由系统决定，本项目没动。

---

## 十、开发说明

这个项目是**用 WorkBuddy 写的**，模型 **DeepSeek-V4.1-Flash**。

人负责：提需求、在真机上点、反馈"这个不对"。
AI 负责：反编译定位、写代码、搭构建脚本、跑 adb 验证、改到能用。

过程不是一次成型——上面 12 个坑基本都是"先写错、跑一遍、看日志、再改"逼出来的。
真机验证占了大部分时间：单是"为什么磁贴点了没反应"这一条，前后重启了五六次，
才定位到是 `MobileSafe` + 解锁状态 + 磁贴被丢弃三个原因叠加。

如果这段对你有用：**这类项目里，AI 的价值不在于一次写对，而在于能不烦不躁地把
"打日志 → 读日志 → 改假设 → 再试"这个循环跑完。**

---

## 十一、免责声明

- 涉及 Magisk / LSPosed / 系统私有接口，**存在变砖风险**，自行承担。
- 仅供个人学习研究，不保证适配其他机型。
- 本项目不包含任何海信私有代码或资源，只通过反射调用系统中已存在、可公开访问的方法。

## License

MIT
