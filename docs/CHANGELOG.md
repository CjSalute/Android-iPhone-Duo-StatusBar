# IOSDuo 三合一状态栏模块 — v2.2-rb 修复交付

对象：`com.dsh.iosduo`（LSPosed 模块，作用域 `com.android.systemui`）
设备：SM-S9180（S23 Ultra）Android 16 / SDK36 / OneUI 8.5，KernelSU + LSPosed
产物：`IOSDuo-ThreeInOne.apk`（v2.2-rb，versionCode 21，签名 iosduo.jks）

---

## 一、四个 bug 的根因与改法

### 1. 下拉通知栏，三合一图标向下移动
**根因**：位置算法遍历整棵树，把所有匹配 view 的包围盒取并集求中心。下拉后 QS 面板/磁贴里存在同名/同 contentDescription 的控件，把包围盒一路往下拉。
**改法**（`Injector.scanAndDraw`）：
- 只统计「状态栏带内（`y < status_bar_height`）且位于右半屏」的原生图标，其余同名控件一律跳过且不递归其子节点。
- 纵向中心死锁在状态栏带内：`cy = clamp(cy, half, sbh - half)`。
- 每个根视图独立位置锁（`POS` 弱表），小幅抖动忽略，`size` 变化才重算。

**验证**：桌面 / 一级下拉 / 完全展开 QS（`cmd statusbar expand-settings`）三态截图中，图标 bounds 恒为 `Rect(914,19 - 986,91)`。

### 2. 开 WiFi 后 5G 图标不消失
**根因**：5G 文本与 WiFi 状态来自 hook 到的对象字段（机型/版本字段名不固定），`netType` 一旦解析到就永不清空，且原生 5G 文本靠 contentDescription 模糊匹配，成功率不稳定。
**改法**：
- `StatusData.refreshFallback()` 每 600ms 直接问系统服务：`ConnectivityManager.getAllNetworks()` + `NetworkCapabilities.TRANSPORT_WIFI` 判定 WiFi 连通，`WifiManager` 取等级，`TelephonyManager` 取信号与制式。
- **WiFi 连通时强制 `netType = ""`**，双保险：`DuoRingDrawable` 里 5G 绘制条件仍是 `data.wifi < 0`。
- 原生 5G/制式文本（`isNetText`）在状态栏带内一并压掉。

**验证**：`svc wifi enable` 后 DUMP 输出 `wifi=3 ... net=`（空），截图 5G 文本消失、WiFi 扇面转绿。

### 3. 设置页滑块 / 双击改值不实时
**根因**：`SettingsActivity.broadcastAll()` 是死代码，从未被调用；SystemUI 侧只靠 `XSharedPreferences` 轮询，跨进程不可靠。
**改法**：
- `put()` 写 SharedPreferences 后进入 40ms 合并队列，`flush()` 发 `com.dsh.iosduo.CONFIG` 广播（`setPackage("com.android.systemui")`）。
- `SettingsActivity.onCreate` 结束时全量广播一次；「恢复默认」后全量广播。
- SystemUI 侧注册接收器（targetSdk 36 必须显式 `Context.RECEIVER_EXPORTED`），收到即 `Cfg.applyFromIntent` + 立即重扫重绘；`Cfg.liveLocked` 锁住内存值，不再被 XSharedPreferences 覆盖。

**验证**：`am broadcast -a com.dsh.iosduo.CONFIG --ef size 36 --ef thickness 0.14 --ef wifiScale 1.3` 后 2 秒内截图，图标由 72px 变 108px、线宽与 WiFi 同步变化。

### 4. 切应用 / 插拔充电器时，隐藏的原生电池、信号、蜂窝短暂出现
**根因**：旧版只在扫描时把视图设 INVISIBLE；系统在状态刷新（充电状态、配置变更、图标容器重建）时会把同一视图或新实例改回 VISIBLE，抢在下一帧渲染出去。
**改法**（四重保险）：
1. `StatusBarIconView` / `ModernStatusBarMobileView` / `ModernStatusBarWifiView` / `BatteryMeterView` 直接 hook（构造 + 状态/布局/可见性方法），按类名或 `slot` 精确隐藏。
2. `View.setVisibility(int)` 拦截：`beforeHookedMethod` 里把已登记目标的 VISIBLE 参数直接改写成 INVISIBLE —— 系统根本没有机会显示。
3. `ViewGroup.onViewAdded(View)`：新视图加入容器的第一帧就判定并压掉。
4. `OnPreDrawListener` 兜底：每帧绘制前遍历，漏网的当场隐藏。

**验证**：设备端 18 帧连拍（脚本内并发执行拔电 → 切桌面 → 回设置页 → 恢复充电），18 帧状态栏内只有三合一图标，无任何原生图标闪现。

---

## 二、附带修复（你还没提但会看到的）

| 现象 | 原因 | 处理 |
|---|---|---|
| 桌面状态栏整块没有图标 | overlay 只挂在 `NotificationShadeWindowView`，桌面时该窗口不可见 | 改为**多宿主**：每个 hook 到的状态栏根视图各挂一个 `ViewOverlay`（`PhoneStatusBarView` 桌面 / `KeyguardStatusBarView` 锁屏 / `NotificationShadeWindowView` 兜底），谁可见谁画，内层可见时外层自动让位防重影 |
| 白色设置页里图标是白的看不见 | 颜色靠屏幕采样，取到壁纸色 | 改为取**状态栏时钟文字色**（= 系统当前前景色），兜底原生图标 tint，再兜底 `LightBarController` 明暗模式；并给设置页加 `SYSTEM_UI_FLAG_LIGHT_STATUS_BAR` |
| 原生电池图标漏网 | 只靠类名模糊匹配 | 新增 `BatteryMeterView` 直接 hook |

---

## 三、构建

```powershell
cd C:\Users\65401\Downloads\xh_work\iosduo\iosduo
powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1
```

依赖（已就位）：
- JDK：`D:\Tools\jdk25`
- `..\sdk\bt36.zip`（build-tools r36 windows，58,699,878 B）
- `..\sdk\platform36.zip`（platform-36_r02，65,878,410 B）
- 脚本首次运行自动解压到 `..\sdk\build-tools`、`..\sdk\platform`，产物 `IOSDuo-ThreeInOne.apk`

`build.sh` 是原 Linux 脚本，Windows 下不再使用（保留作参考）。

## 四、部署与自检

```powershell
adb push IOSDuo-ThreeInOne.apk /data/local/tmp/iosduo.apk
adb shell su -c "pm install -r -d /data/local/tmp/iosduo.apk"
adb shell su -c "killall com.android.systemui"          # 重启 SystemUI 加载新模块
adb shell am broadcast -a com.dsh.iosduo.DUMP -p com.android.systemui   # 自检：输出宿主树/坐标/状态
```

logcat 关键行（`logcat -s LSPosedFramework`）：

```
IOSDuo v2.2-rb loaded in com.android.systemui
IOSDuo: hook ...ModernStatusBarMobileView / ModernStatusBarWifiView
IOSDuo: view com.android.systemui.battery.BatteryMeterView
IOSDuo: iconview com.android.systemui.statusbar.StatusBarIconView
IOSDuo: root ...NotificationShadeWindowView / ...PhoneStatusBarView / ...KeyguardStatusBarView
IOSDuo: View.setVisibility hooked
IOSDuo: ViewGroup.onViewAdded hooked
IOSDuo: config receiver ready
```

DUMP 一行看懂状态：

```
DUMP roots=3 wifi=3 sig=3 net= batt=47 charging color=ff000000 barMode=0 tint=ff000000
DUMPROOT ...PhoneStatusBarView sbh=93 wh=1080x94 vis=V bounds=Rect(914, 19 - 986, 91)
```

- `wifi=3 net=` 空 → WiFi 已连、5G 已互斥
- `bounds` 的 `y` 恒在 `0..sbh` → 没下移

> 注：`setSlot(String)` hook 在你这个 OneUI 版本上没有命中（`StatusBarIconView` 无该方法），slot 精确隐藏由类名 + contentDescription + 可见性拦截 + pre-draw 兜底承担，实测已足够。

## 五、可调参数

设置页（iOS Duo 设置）里的滑块与双击数值，改完 40ms 内生效，无需重启 SystemUI。
也可直接广播，便于脚本化：

```bash
adb shell am broadcast -a com.dsh.iosduo.CONFIG -p com.android.systemui \
  --ef size 26 --ef thickness 0.10 --ef wifiScale 1.0 --ef netDy 2 --ef offsetX -4
```

参数：`thickness radius gap size offsetX offsetY colorMode` / `sig*` `wifi*` `net*` `num*` `arc*`（`*` = `Scale` / `Dx` / `Dy`）。

配置文件兜底（模块读写，1.5s 轮询）：`/sdcard/Download/DSH-Go/iosduo.conf`，`key=value` 一行一个。

## 六、已知限制

- 图标尺寸超过状态栏高度时会被窗口裁剪（状态栏只有 93px），`size` 建议 ≤ 30。
- overlay 画在宿主窗口上，宿主不可见时自然不显示；极端情况下（三星某些窗口动画）会有 1 帧淡入，非原生图标闪现。
- 取色依赖状态栏时钟控件存在；若某主题把时钟移到非标准容器，会退回原生图标 tint。

---

# v2.4-rb 增补（第二轮需求）

## 1. 弧线 / 信号点颜色：白底黑、黑底白

问题根因有两个：

- Android 16 已彻底移除 `SurfaceControl.screenshot`，`android.window.ScreenCapture` 在 SystemUI 的类加载器里也不可达（实测 `sc1/sc2:NoSuchMethodException`、`sc3:ClassNotFoundException`），所以「截屏采样背景亮度」这条路走不通。
- 多宿主之后，`NotificationShadeWindowView`（锁屏大时钟是白色）和 `PhoneStatusBarView`（真状态栏时钟）会**争抢同一个 `data.color`**，谁最后跑谁生效 —— 这就是白底页面偶尔仍显示白图标的直接原因。

现在的取色顺序（`Injector.updateColor`）：

1. `Cfg.colorMode` 手动锁定（1 强制黑 / 2 强制白），设置页新增「图标颜色」卡片可直接点。
2. **只让可见宿主中优先级最高的那个定色**（`rankOf`：PhoneStatusBarView 50 > KeyguardStatusBarView 40 > StatusBarWindowView 30 > CentralSurrounding 25 > NotificationShadeWindowView 10），每轮 ticker 重置，锁屏时自动让 Keyguard 接管，杜绝污染。
3. 取该宿主子树内**状态栏带内的时钟文字色**（系统前景色，白底黑字 / 黑底白字都准）。
4. 兜底：原生图标 tint → LightBarController 明暗模式。

电池弧、电量数字外环、信号点全部使用该颜色（未填充部分与未点亮信号点是同色的半透明，浅色深色背景都可见）。充电时弧线仍保持绿色，这是 iOS 风格特征，不受影响。

诊断字段（`am broadcast -a com.dsh.iosduo.DUMP`）：

```
DUMP roots=3 wifi=3 sig=3 net= batt=84 charging color=ff000000 barMode=0 tint=ffffffff mode=0 ... clock=ff000000
```

白底页面 `color=ff000000`（黑），深色桌面 `color=ffffffff`（白），一眼可判。

## 2. 5G 与 WiFi 同尺寸、双向互斥

- `5G/4G` 文本字号从 `S*0.115` 提到 `S*0.28`，与 WiFi 扇面视觉尺寸相当，并**占据 WiFi 的同一格位置**（`wcx, wcy`），不再挂在下方。
- 双向互斥：WiFi 已连接 → 只画 WiFi，不画 5G；WiFi 未连接 → 只画 5G，**不再画灰色 WiFi 扇面**（以前会留一个灰扇面占位）。
- 位置/大小仍可用设置页 `WiFi 大小·左右·上下` 与 `5G/4G 大小·左右·上下` 微调。

实测三态：WiFi 关（桌面）= 5G 白色大字；WiFi 关（白底设置页）= 5G 黑色；WiFi 开 = 扇面绿色、5G 消失。

---

# v2.5-rb 增补（第三轮需求）

## 1. WiFi 图标改为跟随主题色（不再固定绿）

`wifi.setColor(base)` / 中心点 `dots.setColor(base)`：白底页面黑、深色背景白，与弧线、信号点、5G 文字完全同色。

## 2. 信号点填充方向与电池弧统一

原来的问题是方向相反：电池弧从**左下开始填**（电量少时只剩左下段），信号点却是**从右下开始亮**（信号弱时只剩最右那个）。

现在改为 `i >= (4 - lvl)` 判定点亮：信号弱只亮最左一个点，信号强从左往右依次填满，与电池弧的「从左下起充」同向。

## 3. 锁屏 / 桌面 / 下拉共用同一套参数

根因：`POS` 位置锁按宿主缓存，参数变化后旧宿主不复算；更严重的是锁屏时 `PhoneStatusBarView`（rank 50）和 `KeyguardStatusBarView`（rank 40）**同时持有 overlay 并同时绘制**，出现重影 + 尺寸不一致。

修复：

- 新增 `HOSTS`（WeakHashMap）记录所有发现过的宿主，ticker 持续扫描全部宿主（不再只看 OVL 里还挂着 overlay 的那些）。让位的宿主下一轮能重新拿回绘制权。
- `canDrawHere` 重写：**锁屏时 KeyguardStatusBarView 可见就它独占**，其他宿主一律让位；非锁屏时按宿主优先级最高的可见者（`reallyVisible`：VISIBLE + isShown + alpha>0 + 宽高>0）独占。
- 让位的宿主直接 `ovlDrop` 摘掉 overlay，彻底杜绝重影（以前只是不主动 invalidate，宿主任何重绘都会把 overlay 画出来）。
- 收到 `com.dsh.iosduo.CONFIG` 广播时 `POS.clear()` + 全部宿主重扫重绘，参数立刻三处同步。
- 图标尺寸上限钳到状态栏高度 `sbh`，`cy` 钳制区间改成 `[half, sbh-half]`，`sbh < side` 时居中放置，不再溢出到状态栏外面。

实测：`--ef size 26` 后 PhoneStatusBarView / KeyguardStatusBarView / NotificationShadeWindowView 三处 bounds 同时变成 72px（904,16-976,88 / 901,16-973,88 / 745,21-817,93），锁屏与桌面视觉完全一致、无重影。

## 4. 设置页顶部被状态栏遮挡

targetSdk 35 → Android 15+ 强制 edge-to-edge，内容会顶到状态栏下面。改为用 `setOnApplyWindowInsetsListener` 拿真实 `WindowInsets.Type.statusBars().top`，再额外 +24dp 下移（低版本回退 `getSystemWindowInsetTop()`，再回退 dimen `status_bar_height`）。「图标颜色」卡片的上边框不再被遮。

---

# v2.7-rb 增补（软重启后参数丢失 5–10 秒）

## 现象

软重启（重启 SystemUI 或整机开机）后，三合一组件先用**默认大小**画出来，5–10 秒后才变回用户设置。

## 根因（三条叠加，全部实测确认）

**1. Android 16 把 app 的 SharedPreferences 挪进了 `/data/misc/apexdata/`**

```
/data/misc/apexdata/044de72f-14c0-41f7-a261-0066aa6b1565/prefs/com.dsh.iosduo/iosduo.xml
-rw-rw---- u0_a416 u0_a416      ← 只有模块自己和它的组能读
```

`XSharedPreferences` 按老路径 `/data/user_de/0/com.dsh.iosduo/shared_prefs/iosduo.xml` 找，**目录根本不存在**，于是每次都读空 → 永远用 `Cfg` 的默认值（size=26）。诊断日志实锤：

```
IOSDuo cfg: readXsp ok=false size=26.0 mode=0
```

**2. 遗留的 conf 文件在运行期反向覆盖**

`/sdcard/Download/DSH-Go/iosduo.conf` 里写着 `size=26`，旧逻辑把它放在 cache 之后读取，把正确的 23 又改回 26。已改为「conf 只作启动最旧兜底」，并把该文件改名为 `.bak`。

**3. `scanRoot` 到首次绘制之间还夹着系统服务查询**

`data.init()`（`registerReceiver(null, BATTERY_CHANGED)`）与 `ensureReceiver()`（注册自己的广播接收器）在开机阶段会被 ActivityManager 卡住 —— 实测从进程启动到 `scanRoot` 耗时 4–10 秒（`04:16:20.074 → firstDraw 04:16:23.710`）。

## 修法

- **配置三路叠加，顺序固定为「最旧 → 最权威」**：conf 文本（历史兜底）→ SystemUI 本机副本 → XSharedPreferences。
- **新增 SystemUI 本机副本**：设置页每次下发参数时，`Cfg.saveCache()` 把全量写进 SystemUI 自己的 `shared_prefs/iosduo_live.xml`（`u0_a40` 自己读写，绝不会失败）。下次启动 `Cfg.loadCache()` 瞬间还原。
- **新增公共文件通道**：设置页同时写 `/sdcard/Android/media/com.dsh.iosduo/iosduo.conf`（`Android/media/<pkg>` 是少数「自己无需权限就能写、其它进程能读」的位置）。副本被清掉也能恢复。
- **`Cfg.initEarly()` 提前到 `handleLoadPackage`**：文件通道不依赖 Context，**进程起来 5 毫秒**就能拿到正确参数。
- **`data.init` / `ensureReceiver` / `refreshFallback` 全部挪到后台线程**，主线程只负责绘制，不再被系统服务查询拖慢首帧。

## 实测（`--ef size 23` 为用户值，副本已删除的极限场景）

```
04:39:12.615  IOSDuo v2.7-rb loaded in com.android.systemui
04:39:12.620  IOSDuo cfg: readXsp ok=false size=23.0 mode=2   ← 进程起来 5ms 就是用户值
04:39:15.506  IOSDuo cfg: loadCache ctx=true keys=0 size=23.0 ← 副本是空的也不影响
04:39:15.507  IOSDuo firstDraw size=23.0 mode=2 locked=false  ← 首帧 23，不再是 26
```

修复前后对比：修复前首帧 `size=26.0`（默认值），现在首帧就是 `size=23.0`。

---

# v2.8-rb 增补（飞行模式）

## 需求

飞行模式开启时不再显示 WiFi / 4G / 5G，改为一个彩色飞机图标（机头朝右），并持续自转提示「正在飞行模式」。

## 实现

- `StatusData` 新增 `airplaneMode`，每 600ms 读 `Settings.Global.AIRPLANE_MODE_ON`；为 `true` 时强制 `wifi=-1 / signal=0 / netType=""`，保证任何网络标识都不残留。
- `DuoRingDrawable`：飞行模式下跳过信号点与 WiFi/5G 绘制，改为调用 `drawAirplane()` —— 自己用 `Path` 画的飞机（机身 + 后掠双翼），**机头朝 +x（右）**，填充 `LinearGradient(#7BE8FF → #3B9CFF → #2A55D8)` 三色渐变 + 白色描边，即彩色。
- 旋转角度取 `SystemClock.uptimeMillis() % 1800 / 1800 * 360`，**1.8 秒一圈**；ticker 在飞行模式下把刷新间隔从 250ms 提速到 **60ms**（全扫频率同步放稀到每 10 tick），保证转动顺滑。
- 原生飞行模式图标（类名含 `airplane`）依旧被隐藏，由组件内的动画飞机接管，位置沿用状态栏右侧图标区。

## 顺带修掉的两个问题

**1. 关闭飞行模式后屏幕上残留最后一帧图案**

原来让位（有更高优先级宿主在画）时是 `ViewOverlay.remove(drawable)` —— 移除后宿主如果不重绘，屏幕上会留着最后一帧的像素。改成「不移除，只把 drawable 标记 `hidden` 并立即 `invalidateSelf()`」，重绘即清空。

**2. 宿主选择逻辑各自为政**

`canDrawHere` 原本对每个 root 单独遍历一遍判断，遍历过程中 `ovlDrop` 会改动 OVL 状态，导致同一次扫描里各 root 的结论互相矛盾（出现两个宿主同时画）。抽成 `pickWinner()`：先定唯一绘制者（锁屏时 Keyguard 独占，否则按宿主优先级取第一个 `reallyVisible` 的），其余全部让位。

**3. 漏网的 `slot=satellite` 图标**

有些图标类名不带 wifi/mobile（如 `SingleBindableStatusBarIconView slot=satellite`），原来只按类名和 contentDescription 匹配会漏掉。`scanAndDraw` 增加一条：类名含 `Icon/Battery/Status` 时再读 `mSlot/slot` 字段，命中隐藏名单也隐藏。

## 实测

飞行模式开 `DUMP` → `wifi=-1 sig=0 net=(空)`，组件内显示彩色飞机；连拍两帧飞机角度不同（在转）。关闭后 WiFi 与信号点正常恢复。




