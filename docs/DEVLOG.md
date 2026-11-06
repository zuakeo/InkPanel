# InkPanel v0.1 —— 水墨面板快捷入口（已真机验证）

海信 Touch Lite（HITV205N）：用快捷设置里的一个格子，弹出 SystemUI 那个五条滑杆的水墨面板。

## 已做的三件事（2026-11-06 真机验证）

**v1：磁贴唤出面板**
点「亮度」格子 → 通知栏收起 → 五条滑杆面板浮在桌面下方。再点关闭。

**v2：隐藏下拉里内嵌的亮度/色温/显示模式块**
hook `com.hmct.systemui.qs.ink2.HmctQSSecondInkPanel` 的构造函数，setVisibility(GONE)。
这个类才是内嵌块的真身（不是 `HmctInkBrightnessTempContrast`，那个只被布局引用、实际没实例化）。

**v3：消除隐藏后留下的空白**
内嵌块藏掉后，它的容器（LinearLayout id 0x7f0b0342）仍是固定高 704，只剩 288 的格子，多出 402。
做法：把容器改为 wrap_content，同时把 `HmctQuickStatusBarHeader` 的高度减去这个差值（828 → 426）。
现在通知紧贴格子下方。

⚠️ `qs_show_brightness=0` **无效**，重启 SystemUI 也不变，别浪费时间。

## 原理

`com.hmct.systemui.qs.ink2.inkdetails.SeekBarsDetailPopWindow` 是 SystemUI 里的单例，
方法全 public：

```
public static SeekBarsDetailPopWindow getInstance(Context)
public void    showDetailWindow()
public void    closeDetailWindow()
public boolean isShowing()
```

面板窗口 `Ink2DetailWindow` 是 `KEYGUARD_DIALOG` 类型，只有系统身份能 addView，
所以必须通过 LSPosed 模块在 SystemUI 进程里调用，普通 App 做不到。

## 安装（按顺序，别跳步）

1. `adb install -r dist\InkPanel-0.1.apk`
2. **打开一次「水墨面板」这个 App**（有图标，在应用列表里）
   —— 这步不能省，见下面的坑
3. Vector → 模块 → 启用「水墨面板」→ 作用域勾 **System UI** → 重启设备
4. 下拉通知栏 → 长按格子进编辑 → 把「水墨面板」放到第一排

## ⚠️ 重启后磁贴失效：已用 Magisk 模块解决

**现象**：重启后磁贴（亮度）还在，但点了没反应、无日志无报错；有时甚至从下拉编辑列表里消失。

**三个原因叠在一起**（都实测过）：
1. 海信 `MobileSafeService.checkStartServicePerm` 只放行「本次开机后被前台启动过」的应用，绑 TileService 会被无声拒绝；**连 root 的 `am startservice` 也一样被拒**。
2. 绑定还要求**用户已解锁**（CE 存储可用）。锁屏状态下预热无效。
3. 开机早期 SystemUI 解析不到这个 Service，会把磁贴从面板上丢掉，之后不会自动重试。

**解法：`dist/inkpanel_warm/`（Magisk 模块）**
开机后等 `sys.boot_completed` → 等 `sys.user.0.ce_available=true`（首次解锁）→
`am start --ez warm_only true` 拉起透明 Activity → 删掉再加回 `sysui_qs_tiles` 里的本模块项，强制 `QSTileHost` 重建。

实测：重启 → 解锁 → 什么都不用点，磁贴自动可用。

**安装**
```bash
adb push dist/inkpanel_warm /sdcard/
adb shell su -c "cp -r /sdcard/inkpanel_warm /data/adb/modules/inkpanel_warm"
adb shell su -c "chmod 755 /data/adb/modules/inkpanel_warm/service.sh"
```
**卸载**：`adb shell su -c "rm -rf /data/adb/modules/inkpanel_warm"`，重启即失效。
**日志**：`adb shell su -c "cat /data/local/tmp/inkpanel_boot.log"`

## 调试命令

```bash
# 手动触发（绕过磁贴，验证 hook）
adb shell am broadcast -a com.zuakeo.inkpanel.action.TOGGLE_PANEL -p com.android.systemui

# 看日志
adb logcat -s LSPosed-Bridge | grep InkPanel

# 看磁贴是否被创建
adb logcat -d | grep QSTileHost

# 看服务是否被拦
adb logcat -d | grep -E "MobileSafe|autorun_as"
```

## 卸载 / 回滚

```bash
adb uninstall com.zuakeo.inkpanel
```

Vector 里关掉模块。只改了一个设置项 `sysui_qs_tiles`（把 `float` 换成了本模块），
想恢复悬浮球磁贴就把它改回去：

```bash
adb shell settings put secure sysui_qs_tiles "wifi,bt,soundmode,collectarticle,rotation,dynamicrefresh,float,readmode,mutualtrans,pinscreen"
```

## 构建

```bash
bash build.sh
```

aapt2 → javac(-encoding UTF-8) → d8 → 注入 dex/META-INF → zipalign → apksigner。
不联网、不用 Gradle。Xposed API 用 `src/stubs/` 自写桩，不打进 dex。

**注意 stub 的返回类型必须是 `XC_MethodHook$Unhook`，写成 `void` 会 NoSuchMethodError**
（JVM 按完整签名匹配，返回类型算在内）。

## 还没做（v2）

- 藏掉下拉里那块内嵌的亮度/色温/显示模式面板（`qs_show_brightness=0`，当前仍是 null 未动）
- 顺序铁律：先确认格子可用，再藏原面板，否则没有入口调亮度
