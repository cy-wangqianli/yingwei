# 影位 · 模拟定位（ShadowLoc）

纯原生 Android 的模拟定位工具：给自己开发/联调的应用注入可控的 GPS 位置。
**不依赖 Google Play 服务、不依赖任何第三方 SDK**，内置离线地图与中文地名词库。

> 已在一台 Android 16（Redmi / HyperOS）真机上完成端到端验证：
> gps / network provider 被替换为 `[mock]` 位置、路线匀速移动、悬浮摇杆实时挪位置、
> 离线地图与地名搜索可用、停止后自动恢复真实定位。

本仓库包含两个工程：

| 目录 | 说明 |
|---|---|
| [`shadowloc/`](shadowloc/) | 主 App「影位 · 模拟定位」 |
| [`locprobe/`](locprobe/) | 「位置探针」独立验证 App（对照实验用，可选装） |

编译好的 APK 在 [Releases](../../releases) 页面下载，仓库中不提交二进制产物。

---

## 1. 能做什么

| 功能 | 说明 |
|---|---|
| 定点模拟 | 输入经纬度，或从剪贴板解析高德/Google 地图复制的坐标，立即生效 |
| **离线地图** | 内置世界海岸线/国界 + 3800 个城市点，**完全不需要联网**；可切在线 OSM 看街道 |
| **地名搜索** | 离线搜中文 / 拼音 / 英文（如「成都」「chengdu」「Chengdu」），点结果直接跳过去 |
| 我的位置 | 地图里一键回到系统当前定位（连自己都不知道在哪时最有用） |
| 收藏地点 | 保存常用坐标并命名，点一下就切换过去 |
| 路线模拟 | 多个路标点组成折线，按设定速度（km/h）匀速移动，可循环 |
| 悬浮窗摇杆 | 在其他 App 上层显示虚拟摇杆，满推约 8 m/s，边用边挪位置 |
| 前台常驻 | 运行时显示通知，随时一键停止；停止时自动撤掉模拟 provider |
| 跨 App 生效 | 走系统 `LocationManager` 测试 provider，**所有读系统定位的 App 都会被改写**（见第 5 节） |

系统要求：**Android 8.0（API 26）及以上**，targetSdk 34，无架构限制。iOS 不提供（见第 10 节）。

---

## 2. 产物

| 文件 | 说明 |
|---|---|
| `shadowloc-v1.0.0.apk` | 主 App（约 837 KB，已签名） |
| `locprobe-v1.0.0.apk` | 「位置探针」验证工具（约 20 KB，可选装） |

两者都在 [Releases](../../releases) 里提供。仓库源码构建后产物落在各自的 `dist/`（已被 `.gitignore` 排除）。

---

## 3. 安装

### 3.1 装 APK

把 `shadowloc-v1.0.0.apk` 传到手机安装即可。

- 小米 / Redmi / HyperOS 通过数据线安装时，若提示 `INSTALL_FAILED_USER_RESTRICTED`，需先在
  **开发者选项 → USB 安装** 打开开关，再重试（部分系统还会弹「继续安装」确认框）。
- 首次启动会依次申请：**定位（选「精确位置 / 仅在使用中允许」）**、**通知**。
  悬浮窗权限按需申请（只在使用摇杆时需要）。

### 3.2 关键一步：允许「模拟位置」

Android 从 6.0 起，模拟位置必须由用户在系统里显式指定应用，**App 自己无法绕过**：

1. 打开系统 **设置 → 开发者选项**（没有的话先在「关于手机」里连点版本号 7 次）；
2. 找到 **「选择模拟位置信息应用」**（部分机型叫「模拟位置」）；
3. 选中 **影位 · 模拟定位**。

做完这一步回到影位点「开始模拟」即可。没做的话 App 会弹窗提示并直接跳到开发者选项。

---

## 4. 使用

### 4.1 不知道自己在哪 → 用离线地图

点主界面 **地图选点**，默认就是**离线底图**（不联网也能用）：

1. 地图上有世界海岸线、国界和主要城市的中文名；
2. 顶部搜索框输入 **成都 / chengdu / Chengdu** 都能搜到，点结果即跳转并把绿点放上去；
3. 拖动地图或直接拖绿点微调；
4. 点 **用此位置** 返回主界面并开始用该坐标模拟；
5. 点 **我的位置** 可回到系统当前定位（适合先看一眼自己在哪）。

> 想看清街道时，点底部 **在线** 切到 OpenStreetMap 瓦片（需联网）；网络不通会自动切回离线底图。

### 4.2 定点模拟
1. 顶部输入纬度 / 经度（或点「用真实位置」「粘贴解析」「地图选点」）；
2. 点 **立即定位** 或 **开始模拟**；
3. 状态卡变成「模拟中 · 定点」，坐标每秒上报一次。

### 4.3 收藏地点
点 **＋ 保存当前坐标** 并起名；之后点列表项即可切换。数据存本地，重启不丢。

### 4.4 路线模拟
1. 用「＋ 加当前点」逐个加入途经点（或点「示例路线」生成绕当前点 180 m 的环线）；
2. 填速度（km/h，默认 5，接近快走）；
3. 需要反复跑就勾「循环」；
4. 点 **开始走路线**，App 沿折线匀速位移，并同步上报速度与方向角。

### 4.5 悬浮窗摇杆
1. 打开卡片右侧开关，授予「显示在其他应用的上层」；
2. 摇杆浮在屏幕左上方（拖动背景可挪位置，点 × 关闭）；
3. 拖动即按方向移动，满推约 8 m/s；松手即停。

### 4.6 停止
点 **停止模拟**（或通知栏「停止」），测试 provider 会被移除，系统定位恢复真实。

---

## 5. 到底能不能在「各个 App」里生效？

**能——只要是读取系统定位的应用，都会被改写。** 因为影位改的不是某个 App，而是系统
`LocationManager` 里的 `gps` / `network`（尽力而为还有 `fused`）provider：所有 App 读到的
都是同一份位置，系统还会把它标记为 mock。

我们用**一个完全独立的 App** 做了对照实验（源码在 [`locprobe/`](locprobe/)，APK 同名）：

```
# 影位正在模拟时，位置探针（独立包名 com.shadowloc.probe）读到的：
I LocProbe: BEST provider=gps lat=39.875503 lng=116.422759 mock=true

# 影位停止后，同一个探针恢复真实定位：
I LocProbe: BEST provider=fused lat=31.092718 lng=104.385547 mock=false
```

你可以自己装这个探针，打开影位点「开始模拟」，再切到探针看数字是否一致。

### 哪些情况会「看起来没生效」

| 现象 | 原因 / 处理 |
|---|---|
| 某个 App 位置没变 | 对方有定位缓存（尤其地图/外卖/打车）→ 杀掉该 App 重开 |
| 高德/百度 SDK 的应用位置不动 | 它们可能优先用自己的网络/Wi-Fi 定位结果或缓存，等下一轮定位或杀进程重开通常就变了 |
| 打卡/游戏/金融 App 拒绝 | 它们检测 `isFromMockProvider`/`isMock`，或校验签名与传感器；**这是对方的风控，不是本 App 能改的** |
| Google 系 App 用了 FusedLocationProvider | 本 App 会尽力注册 `fused` 测试 provider，但多数 ROM 由 GMS 托管，不一定成功；通常 gps 被改写后 GMS 也会跟随 |

想做「对检测也隐身」的注入，必须 root + Xposed/LSPosed 之类的框架去 hook 定位服务，
那属于另一类方案（有风险，本工程不做）。

---

## 6. 离线地图数据

| 数据 | 来源 | 许可 |
|---|---|---|
| 世界海岸线 / 国界（110m） | [Natural Earth](https://www.naturalearthdata.com/) via `world-atlas` | Public Domain |
| 3800 个城市（坐标、人口、中文名） | [GeoNames cities15000](https://download.geonames.org/export/dump/) | CC BY 4.0 |
| 地图引擎 / 交互 | [Leaflet 1.9.4](https://leafletjs.com/)（已打包进 `assets/`） | BSD-2-Clause |

- 离线底图是**世界级**（能定位到城市、看清省界国界），**不含街道级细节**——街道需要按城市下载
  离线瓦片包（MBTiles），体积几十 MB 到几百 MB，本版未内置；需要的话可以再扩展。
- 中文名来自 GeoNames 别名 + 常见城市覆盖表（`shadowloc/tools/build-geo.mjs` 里的 `OVERRIDES`）。
- 重新生成数据：`node tools/build-geo.mjs`（会缓存 `cities15000.zip` 到 `.cache/`）。

---

## 7. 常见问题

**Q：弹「还差一步：模拟位置授权」**
去「开发者选项 → 选择模拟位置信息应用」选影位。

**Q：摇杆不出现**
授予「显示在其他应用的上层」权限后再开开关；模拟未运行时不显示。

**Q：通知栏没有常驻通知**
Android 13+ 需要允许通知；不影响模拟本身。

**Q：后台跑一会儿被系统杀掉**
把影位的电池策略设为「无限制」，并允许自启动（MIUI/HyperOS）。

**Q：地图一片黑 / 只有海岸线**
这就是离线底图，本来只画到城市级；切「在线」看街道。

**Q：之前用别的模拟 App 后定位卡住**
影位每次启动会先清理残留的测试 provider；若仍异常，重启手机或切一次飞行模式。

---

## 8. 实现要点

- **注入方式**：`LocationManager.addTestProvider()` 注册 `gps` / `network`（`fused` 尽力而为），
  每秒 `setTestProviderLocation()`；权限依赖 manifest 的 `ACCESS_MOCK_LOCATION` +
  用户在开发者选项中的选择（`AppOps OP_MOCK_LOCATION`）。停止时 `removeTestProvider()`。
- **位置细节**：同时写 `accuracy / altitude / speed / bearing / elapsedRealtimeNanos`，
  API 31+ 显式 `setMock(true)`，附带 `satellites=12` extras，贴近真实上报格式。
- **前台服务**：`foregroundServiceType="location"`，通知带「停止」动作，持有唤醒锁保证路线不被 Doze 拖慢。
- **悬浮窗**：`WindowManager` + `TYPE_APPLICATION_OVERLAY` + 自绘 `JoystickView`，
  把「触点位移 → 东/北分量 → 按速度积分」映射到坐标。
- **离线地图**：Leaflet 画 Natural Earth GeoJSON（内置 `assets/geo/*.js`），城市按缩放级别做
  标签抽稀；搜索在前端内存里对 3800 条数据做前缀/包含匹配。
- **无第三方依赖**：界面全 framework 原生 View，APK 837 KB。

---

## 9. 自己构建

不需要 Gradle。构建脚本用 `aapt2 + javac + d8 + zipalign + apksigner` 手工打包。

### 环境要求

- Windows + PowerShell 5.1 及以上
- JDK 17+（`JAVA_HOME` 或 `-JdkHome`）
- Android SDK：**build-tools 35.0.0** + **platforms;android-35**（`ANDROID_HOME` / `ANDROID_SDK_ROOT` 或 `-SdkRoot`）

### 构建主 App

```bat
cd shadowloc
powershell -ExecutionPolicy Bypass -File build.ps1 -SdkRoot "C:\Android\Sdk" -JdkHome "C:\Program Files\Java\jdk-21"
```

产物：`shadowloc\dist\shadowloc-v1.0.0.apk`（或脚本输出的 `build\out\` 下）。

签名密钥默认由 `keytool` 自动生成到 `shadowloc\keystore\shadowloc.jks`（该目录已被 `.gitignore` 排除，**不要提交**）。
需要固定口令时传 `-KsPass <口令>`。

### 构建探针 App

先构建主 App（探针复用主 App 生成的 keystore）：

```bat
cd locprobe
powershell -ExecutionPolicy Bypass -File build.ps1 -SdkRoot "C:\Android\Sdk" -JdkHome "C:\Program Files\Java\jdk-21"
```

### 三个坑（脚本已规避）

1. **aapt2 不认含中文的路径**：统一 `Push-Location` 到工程目录后只用相对路径。
2. **`resources.arsc` 必须 STORED + 4 字节对齐**（targetSdk ≥ 30）：
   .NET 的 `NoCompression` 实际是 deflate(0)，会导致安装报
   `Targeting R+ requires the resources.arsc ... uncompressed`；改用 `jar cf0` + `zipalign -f 4`。
3. PowerShell 5.1 按 GBK 解析无 BOM 的 `.ps1`：脚本保存为 **UTF-8 with BOM**，改完保持 BOM。

### 逻辑单测

```bat
set SDK=%JAVA_HOME%
%SDK%\bin\javac -encoding UTF-8 -d out test\LogicTest.java src\com\shadowloc\app\Geo.java src\com\shadowloc\app\RoutePlayer.java
%SDK%\bin\java -cp out LogicTest
```

---

## 10. 关于 iOS

iOS 没有对第三方开放的模拟位置 API：非越狱设备只能靠 Xcode 给调试版注入 GPX，需要 Mac +
开发者证书；越狱也属于破坏系统完整性。因此本项目只做 Android。

---

## 11. 目录结构

```
yingwei/
├─ shadowloc/                      主 App
│  ├─ AndroidManifest.xml
│  ├─ build.bat / build.ps1        无 Gradle 打包脚本
│  ├─ dist/                        成品 APK（.gitignore）
│  ├─ res/                         深色主题、卡片、自绘图标、布局
│  ├─ assets/
│  │  ├─ map.html                  地图页（离线/在线切换 + 地名搜索）
│  │  ├─ leaflet.js / leaflet.css
│  │  └─ geo/                      land.js / countries.js / cities.js（离线数据）
│  ├─ tools/build-geo.mjs          离线地图数据生成脚本
│  ├─ src/com/shadowloc/app/
│  │  ├─ MainActivity.java         主界面
│  │  ├─ MapActivity.java          地图选点
│  │  ├─ MockService.java          前台服务：注入、三模式、通知、悬浮窗
│  │  ├─ JoystickView.java         自绘摇杆
│  │  ├─ RoutePlayer.java          折线匀速推进（纯 JVM）
│  │  ├─ Geo.java                  距离/方位角/按米偏移（纯 JVM）
│  │  └─ Store.java                本地持久化
│  └─ test/LogicTest.java          纯 JVM 逻辑自测
└─ locprobe/                       独立的「位置探针」验证 App
   ├─ AndroidManifest.xml
   ├─ build.bat / build.ps1
   ├─ res/
   └─ src/com/shadowloc/probe/MainActivity.java
```

---

## 12. 使用边界

模拟定位是 Android 官方给开发者的调试能力，但**上报位置会被标记为 mock**，对方 App 可以检测。
请只用于自己开发/测试的应用联调；用于考勤代打、游戏作弊、外卖/网约车违规接单等场景可能违反
平台规则或当地法律。

---

## 13. 许可证

本项目代码以 [MIT](LICENSE) 许可证开源。

第三方数据/库遵循各自许可：Natural Earth（Public Domain）、GeoNames（CC BY 4.0）、
Leaflet（BSD-2-Clause），详见第 6 节。
