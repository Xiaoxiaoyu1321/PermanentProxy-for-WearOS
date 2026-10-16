# flag.md — PermanentProxy for Wear OS · 需求与项目进度

> 本文件是本仓库的**需求登记册 + 进度看板**。
> 每完成一个阶段性更改：先 `git commit`，随后**立即**在本文件「项目进度」章节追加一条记录。
> 工作分支：`dev`（正式发布合并回 `master` 并打 tag）。

---

## 1. 任务输入（原始需求原文）

本次任务由以下几项输入构成，全部登记在此，作为验收依据：

### 1.1 仓库地址

```
git@github.com:Xiaoxiaoyu1321/PermanentProxy-for-WearOS.git
```

### 1.2 拉取时使用的代理环境

```sh
export https_proxy=http://127.0.0.1:7890 http_proxy=http://127.0.0.1:7890 all_proxy=socks5://127.0.0.1:7890
```

### 1.3 用户需求原文（逐条）

> 1. 拉取这个仓库，通过代理：`export https_proxy=http://127.0.0.1:7890 http_proxy=http://127.0.0.1:7890 all_proxy=socks5://127.0.0.1:7890`
> 2. 分析仓库内容。
> 3. 我记得这个项目是一个基于蓝牙 adb 的项目，但是它**无法在不支持蓝牙 adb 的设备上使用**。
> 4. 我需要你**重新编写关于与设备通讯的部分，重置为调用 Shizuku API 的版本**，以便于**适配更多的设备**。
> 5. 请在项目下创建一个 `flag.md`，整理本项目需求，包括这些输入的内容和需求。
> 6. **创建并切换到 `dev` 分支，在 `dev` 分支上工作**。
> 7. 在**每完成一个阶段性更改**的时候，创建一个 commit 描述当前的项目进度，**并立即在 `flag.md` 文件下的项目进度**（登记）。
> 8. 在完成所有的编写工作后，创建 **GitHub Action Workflow**，用于自动构建 Android 版本。
> 9. 当创建**带有版本号的 tag** 的时候，将**编译产物用作 Release 发布**，记得编写相应的 **Release 文本**。
> 10. 完成上述工作后，**发布一个正式可用版本**。

---

## 2. 原项目分析（改造前）

### 2.1 项目是什么

`PermanentProxy for Wear OS`（作者 Jolan Rensen，包名 `nl.jolanrensen.permanentproxy`，原版本 4.0）。
核心功能：在 Wear OS 手表上开启**系统级 HTTP 代理**，从而让 Google Pay 等有地区锁的应用在非支持地区可用。

系统代理的写入方式是 Android 全局设置：

```
settings put global http_proxy <host>:<port>
```

这条命令**普通 App 无权执行**，需要 `android.permission.WRITE_SECURE_SETTINGS`（签名/特权权限）。

### 2.2 原项目的实现方式（问题所在）

| 环节 | 原实现 |
| --- | --- |
| 提权手段 | **Bluetooth ADB（ADB over Bluetooth）**：手表开发者选项里打开「通过蓝牙调试」，App 连接 `localhost:7272` |
| 通讯库 | `com.tananaev:adblib:1.2`（自己实现 ADB 协议 + RSA 密钥协商） |
| 通讯代码 | `wear/.../SendSingleCommand.kt` —— 建立 Socket → `AdbConnection` → `open("shell:")` → 写命令 → 读回显 |
| 权限申请 | `RequestPermissionActivity.kt` 通过蓝牙 ADB 执行 `pm grant nl.jolanrensen.permanentproxy android.permission.WRITE_SECURE_SETTINGS` |
| 设置代理 | `Constants.startProxy()` 直接用 `Settings.Global.putString(...)`（此时已拿到 WRITE_SECURE_SETTINGS） |
| 关闭代理 | `Constants.stopProxy()` 再用蓝牙 ADB 执行一串 `settings delete ...` + `reboot` |

**结论：`WRITE_SECURE_SETTINGS` 的获取完全依赖蓝牙 ADB，这就是致命限制。**

### 2.3 原方案的致命缺陷（必须解决）

1. **设备支持面窄**：只有部分 Wear OS 2/3 设备在开发者选项里提供「Debug over Bluetooth」。
2. **新版 Wear OS 已移除**：Wear OS 3.5+ / 4 / 5 上该选项在很多机型上不复存在，或在「无线调试」迁移过程中被移除。
3. **体验差**：蓝牙 ADB 需要配对、`always allow this computer`、连接慢（代码里给了 25s+ 的超时）、耗电。
4. **脆弱**：自实现 ADB 协议 + RSA 密钥文件，容易在系统升级后失败；旧的 `commons-codec` repack jar 是历史包袱。
5. **关闭代理要重启手表**：`stopProxy` 最后会 `reboot`，体验很差。

---

## 3. 新需求（本次改造）

### 3.1 功能性需求

| 编号 | 需求 | 验收标准 |
| --- | --- | --- |
| **F1** | 用 **Shizuku API** 替换蓝牙 ADB 通讯 | 代码中不再存在 adblib / 7272 端口 / `SendSingleCommand`；提权路径为 Shizuku |
| **F2** | 通过 Shizuku 执行 shell 命令 | 能够以 shell（uid 2000）或 root（uid 0）身份执行 `settings put/delete global ...` |
| **F3** | 保留「设置 / 关闭系统代理」核心功能 | 输入 host + port 可开启代理；可关闭代理；状态与当前出口 IP 正常显示 |
| **F4** | 适配更多设备 | 支持任何可运行 Shizuku 的 Android 6.0+ 设备（含 Wear OS 3/4/5 与非手表设备） |
| **F5** | 保留手动 PC ADB 兜底 | 若系统已有 `WRITE_SECURE_SETTINGS`（用户用电脑 `pm grant` 过），App 免 Shizuku 直接工作 |
| **F6** | 关闭代理不再强制重启 | 优先直接清设置；重启作为可选项 |
| **F7** | Shizuku 不可用时有清晰引导 | 引导安装/启动 Shizuku（无线调试 / PC adb / root） |

### 3.2 工程性需求

| 编号 | 需求 | 验收标准 |
| --- | --- | --- |
| **E1** | `flag.md` 需求 + 进度文件 | 本文件；每次 commit 后立即更新 |
| **E2** | `dev` 分支工作 | 所有改造提交在 `dev` 分支 |
| **E3** | 阶段性 commit | 每个阶段一个描述性 commit |
| **E4** | 项目可编译 | 旧的 `jcenter()`/AGP 4.0/JDK8 组合今天已无法构建，需现代化到可构建状态 |
| **E5** | GitHub Actions 自动构建 Android | push / PR / 手动触发时构建 APK 并上传 artifact |
| **E6** | tag 触发 Release | 打 `v*.*.*` tag → 自动构建 → 创建 GitHub Release 并附带 APK/AAB 与 Release 文本 |
| **E7** | 发布正式版本 | 合并 `master`、打 tag、Release 可用 |

### 3.3 非目标（本次不做）

- 不改动 UI 视觉设计语言（仅按需增删控件与文案）。
- 不引入 root 专用功能（Shizuku 已经统一了 root/shell 两条路径）。
- 不做代理服务器本身的实现。

---

## 4. 技术方案

### 4.1 为什么用 Shizuku

Shizuku 通过 ADB（无线调试 / 电脑 adb）或 root 启动一个常驻服务，普通 App 通过它获得 **shell (uid 2000) / root (uid 0)** 身份，从而可以执行 `settings put global http_proxy ...`。
相比蓝牙 ADB，它在**支持面、稳定性、速度**上都更好，且是社区标准方案。

### 4.2 通讯层设计（`rikka.shizuku` API 13.1.5）

不使用方法已废弃的 `Shizuku.newProcess`（官方已宣布移除），改用官方推荐的 **UserService**：

```
App 进程 (uid = 应用)
  │  Shizuku.bindUserService(UserServiceArgs, ServiceConnection)
  ▼
Shizuku Server（shell / root）
  │  以 shell(2000) 或 root(0) 身份拉起
  ▼
UserService 进程（uid = 2000 / 0）  ← 运行我们在 APK 里的 IShizukuShellService.Stub 实现
  │  Runtime.exec("/system/bin/sh -c <cmd>")
  ▼
/system/bin/settings put global http_proxy host:port
```

- AIDL：`IShizukuShellService`，方法 `ShellResult exec(String command)`、`int getUid()`、`void destroy()`（Shizuku 约定的销毁事务号 `16777114`）。
- `ShellResult` 为 `Parcelable`（exitCode / stdout / stderr），避免反射与隐藏 API。
- App 侧 `ShizukuManager` 统一封装：可用性检测、权限申请、绑定/解绑、带超时的命令执行。

### 4.3 命令映射

| 动作 | 命令 |
| --- | --- |
| 开启代理 | `settings put global http_proxy <host>:<port>` |
| 关闭代理 | `settings delete global http_proxy; settings delete global global_http_proxy_host; settings delete global global_http_proxy_port; settings delete global global_http_proxy_exclusion_list; settings delete global global_proxy_pac_url` |
| 重启（可选） | `reboot` |
| 读取状态 | `settings get global global_http_proxy_host` / `..._port` |

### 4.4 提权优先级

1. App 自身已有 `WRITE_SECURE_SETTINGS` → 直接 `Settings.Global` 读写（最快，无 Shizuku 依赖）。
2. 否则走 Shizuku UserService 执行 shell 命令。
3. 都不可用 → 引导用户（安装 Shizuku / 用电脑 adb 授权）。

### 4.5 构建现代化

| 项目 | 改造前 | 改造后 |
| --- | --- | --- |
| Gradle | 6.1.1 | 8.10.2 |
| Android Gradle Plugin | 4.0.0 | 8.5.2 |
| Kotlin | 1.3.72 | 1.9.24 |
| 仓库 | `jcenter()`（已停服） | `google()` + `mavenCentral()` |
| compileSdk / targetSdk | 29 | 34 |
| View 绑定 | `kotlin-android-extensions`（已删除） | ViewBinding |
| 通讯依赖 | `com.tananaev:adblib` + `commons-codec` repack jar | `dev.rikka.shizuku:api` + `:provider` 13.1.5 |
| Wear 库 | `com.android.support:wear` 28（靠 jetifier） | `androidx.wear:wear:1.3.0` |
| 签名 | 无 release 签名配置 | 支持从环境变量注入 keystore（CI 用），无则用 debug 签名产出可安装 APK |

---

## 5. 项目进度

> 规则：每个阶段性 commit 之后立刻追加一条。格式：`日期 — commit 主题 — 做了什么 / 状态`。

| # | 时间 | 阶段 | 内容 | 状态 |
| --- | --- | --- | --- | --- |
| 0 | 2026-10-14 | 仓库获取与分析 | 通过代理 clone 仓库；通读 `README.md`、`SendSingleCommand.kt`、`Constants.kt`、`MainActivity.kt`、`RequestPermissionActivity.kt`、`build.gradle`、Manifest；确认「提权依赖蓝牙 ADB」为致命限制 | ✅ |
| 1 | 2026-10-14 | 需求登记 + 分支 | 创建 `dev` 分支；创建本 `flag.md`，登记全部输入、需求、技术方案 | ✅ |
| 2 | 2026-10-14 | 构建现代化 + Shizuku 底层 | Gradle 6.1.1→8.10.2、AGP 4.0.0→8.5.2、Kotlin 1.3.72→1.9.24、去 jcenter、compileSdk 34、minSdk 23→24；`kotlin-android-extensions` → `findViewById`；`WearableActivity` → `ComponentActivity`；`com.android.support:wear` → `androidx.wear:wear:1.3.0`；`RemoteIntent` → `RemoteActivityHelper`；补齐本地 `color/black`、`color/grey`；**新增 Shizuku 底层**：`IShizukuShellService.aidl`、`ShellResult.kt`、`ShizukuShellService.kt`、`ShizukuManager.kt`、`ProxyManager.kt`。本地 `:wear:assembleDebug` 通过。commit `e4a54dd0` | ✅ |
| 3 | 2026-10-14 | **通讯层切换为 Shizuku（核心改造）** | 删除 `SendSingleCommand.kt`、`adblib`、`commons-codec` repack jar、7272 端口常量、`EnableADBBluetoothActivity`、`OldWatchActivity`；新增 `ShizukuManager`（binder 跟踪 / 权限申请 / UserService 绑定 / `suspend exec`）、`ShizukuShellService` + AIDL + `ShellResult`（以 shell uid 2000 或 root uid 0 执行 `/system/bin/sh`）、`ProxyManager`（优先直写 `Settings.Global`，否则走 Shizuku shell）、`ShizukuSetupActivity`、`AdbFallbackActivity`、`PermanentProxyApp`、`signing.gradle` + 公开 release keystore；关闭代理不再强制重启（改为可选勾选）；UI 显示提权状态。本地 `:wear:assembleDebug/assembleRelease`、`:mobile:assembleRelease` 全部通过，`apksigner verify` 通过。commit `dae765b4` | ✅ |
| 4 | 2026-10-14 | **GitHub Actions + 文档** | 新增 `.github/workflows/android-build-release.yml`：push/PR 构建 4 个 APK 并上传 artifact；打 `v*.*.*` tag 时自动构建、校验签名、生成 `SHA256SUMS.txt`、写出 Release 文本并发布 GitHub Release；版本号从 tag 注入（`-PversionNameOverride` / `-PversionCodeOverride`）。本地已用真实脚本验证 YAML 合法、版本解析、Release 文本渲染、版本覆盖构建（versionName 5.0.0 / versionCode 42）。重写 `README.md`。commit `adc7553e` | ✅ |
| 4.1 | 2026-10-14 | CI 修障（第二次运行） | 首次运行在 `Set up Android SDK` 失败：`android-actions/setup-android@v3` 只支持 Node 20，被 runner 强制在 Node 24 上运行而崩溃。改为直接使用 runner 预装的 Android SDK（自动定位 `sdkmanager`、导出 `ANDROID_HOME`/`ANDROID_SDK_ROOT`），checkout / setup-java 升到 v5。`0f526c67` 后 CI 全绿 | ✅ |
| 4.2 | 2026-10-14 | CI 修障（tag 发布） | tag 构建的 Publish Release 步骤失败：`softprops/action-gh-release@v2` 同样是 Node 20 目标、被强制到 Node 24。改为用 `curl` + `jq` 直接调用 GitHub REST API 创建 Release 并上传资源（零 Node 依赖、可重复执行、失败时以 `::error` 注解回传 API 响应体）。commit `e62ad695` | ✅ |
| 5 | 2026-10-14 | **发布正式版本 v5.0.0** | 合并 `dev` → `master`，打标签 `v5.0.0` 并推送。CI 自动完成：构建 → 单元测试 → 收集产物 → `apksigner` 校验签名 → 创建 GitHub Release（含 Release 正文 + 4 个文件）。已下载发布产物复核：SHA-256 与 `SHA256SUMS.txt` 一致、签名有效、`versionName=5.0.0` / `minSdk 24` / `targetSdk 34`。<br>Release：https://github.com/Xiaoxiaoyu1321/PermanentProxy-for-WearOS/releases/tag/v5.0.0 | ✅ |
| 6 | 2026-10-14 | **修复关闭代理不生效（回归修复）** | 复核 AOSP 源码后确认：`ConnectivityService` 只监听 `Settings.Global.HTTP_PROXY`，而 `ProxyTracker.loadGlobalProxy()` **从不把 `mGlobalProxy` 清空**，唯一清空点是 `ProxyTracker.setGlobalProxy()` 的 else 分支，且只有 `http_proxy` 为「非空字符串 + 主机部分为空」时才会走到。因此单纯删除设置关不掉代理，这正是 4.x 必须 `reboot` 的原因，也是我在 5.0.0 把它改成「可选、默认关闭」时引入的回归。<br>修复：`ProxyManager.disableProxy()` 改为两步——先写 `http_proxy=:0` 让 system_server 真正丢弃内存中的全局代理，间隔 700ms（避免 `ContentObserver` 通知被合并）后再删除全部 5 个键；勾选框保留给老机型。**注意不能用 `":"`**：`":".split(":")` 返回空数组，会命中提前返回。commit `9a8c186c` | ✅ |
| 7 | 2026-10-14 | **署名信息** | 新增 `AboutActivity` + `activity_about.xml`（主界面与捐赠页各有入口）：列出原作者 Jolan Rensen (@Jolanrensen)、维护者 @Xiaoxiaoyu1321、Shizuku (@RikkaApps)，并注明「Shizuku 集成（5.0 起）、构建现代化与自动发布流程由 DeepSeek Harness 辅助生成 / developed with assistance from DeepSeek Harness」（中英双语）。捐赠页底部加署名分隔线 + 入口，**原作者的四档 PayPal 捐赠卡片与文案原样保留、未改动**。`README.md` 同步更新 Credits。commit `a9c5d8cb` | ✅ |
| 8 | 2026-10-14 | **Release 文本自动生成** | 把写死在 workflow 里的 heredoc 换成 `.github/release-notes-template.md` 模板 + `awk` 替换，并在 Release 文本中自动插入「自上一个版本 tag 以来的 commit 列表」（`git log prev..HEAD`）。好处：正文不再随版本过时、避免 heredoc 缩进/反引号转义陷阱、commit message 里的反引号不会被 shell 执行。commit `43bed768` | ✅ |
| 9 | 2026-10-14 | **发布 v5.0.1** | 版本号 5.0.1 / versionCode 18；`clean` 全量构建 4 个 APK 通过、0 warning；推送 `dev` + `master` + tag `v5.0.1` 触发 CI 发布。<br>Release：https://github.com/Xiaoxiaoyu1321/PermanentProxy-for-WearOS/releases/tag/v5.0.1 | ✅ |
| 10 | 2026-10-15 | **两套关闭策略互为备份** | 按需求把「设置为 `:0`」与「删除」显式保留为两个策略，并加**真实校验 + 自动回退**：<br>① `SENTINEL_THEN_DELETE`（优先）——写 `http_proxy=:0`，轮询等待 system_server 确认（判据：`global_http_proxy_host` 由「不存在」变为「存在但为空」，因为只有 `ProxyTracker.setGlobalProxy()` 的 else 分支会写空串，而这正是把 `mGlobalProxy` 置空的同一段代码），确认后再删除全部 5 个键；<br>② `DELETE_ONLY`（回退）——若 ① 写不进去、或设备根本没响应，自动退回 4.x 的纯删除逻辑，清理持久化状态，并提示可能需要重启。<br>两条路径返回同样的 `Result`、同样的界面流程（`Proxy disabled`），用户侧无差别；策略名写入 logcat。本地 `:wear:assembleDebug/assembleRelease` 通过、0 warning。commit `c6d9279f` | ✅ |
| 11 | 2026-10-15 | **发布 v5.0.2** | 版本号 5.0.2 / versionCode 19。顺带清掉最后 4 条编译警告（debug+release 各 2 条）：`Constants.getCurrentIP` 原本用 `GlobalScope`（delicate API），改为对象自持有的 `SupervisorJob + Dispatchers.IO` 作用域 + `withTimeoutOrNull`，回调**恰好调用一次**（旧的双协程写法在「超时 / 完成」竞态下理论上可能回调两次）。`clean` 全量构建 4 个 APK 通过、**0 warning**，versionName 5.0.2 / versionCode 19。commit `d9373294` | ✅ |

---

## 5.1 需求验收对照

| 编号 | 需求 | 结果 | 证据 |
| --- | --- | --- | --- |
| **F1** | 用 Shizuku API 替换蓝牙 ADB 通讯 | ✅ | `SendSingleCommand.kt` / `adblib` / `commons-codec` / 端口 7272 全部删除；提权入口只剩 `ShizukuManager` |
| **F2** | 通过 Shizuku 执行 shell 命令 | ✅ | `ShizukuShellService`（Shizuku UserService，uid 2000 或 0）执行 `/system/bin/sh -c`；`ProxyManager` 调用之 |
| **F3** | 保留设置/关闭代理与状态显示 | ✅ | `ProxyManager.enableProxy` / `disableProxy` / `currentProxy`；`MainActivity` 显示状态、出口 IP、提权方式 |
| **F4** | 适配更多设备 | ✅ | 不再依赖「通过蓝牙调试」开发者选项；任何能运行 Shizuku 的 Android 6.0+ 设备可用（minSdk 24，Wear OS 2.0+） |
| **F5** | 保留手动 PC ADB 兜底 | ✅ | 检测到 `WRITE_SECURE_SETTINGS` 时直接 `Settings.Global` 写入；`AdbFallbackActivity` 给出 `pm grant` 指令 |
| **F6** | 关闭代理不再强制重启 | ✅ | `disableProxy(reboot = false)` 为默认；重启改为界面上的可选勾选框 |
| **F7** | Shizuku 不可用时有引导 | ✅ | `ShizukuSetupActivity`（安装/无线调试/PC adb/root 三种启动方式）+ 首页 `privilege_status` 明确提示当前缺什么 |
| **E1** | `flag.md` 需求 + 进度 | ✅ | 本文件 |
| **E2** | 在 `dev` 分支工作 | ✅ | 全部改造先提交到 `dev`，最后 ff 合并到 `master` |
| **E3** | 阶段性 commit | ✅ | 6 个功能/构建 commit + 5 个 `flag.md` 进度 commit |
| **E4** | 项目可编译 | ✅ | `./gradlew clean :wear:assembleDebug :wear:assembleRelease :mobile:assembleDebug :mobile:assembleRelease` 全部成功 |
| **E5** | GitHub Actions 自动构建 | ✅ | 每次 push / PR 构建 4 个 APK 并上传 artifact（run `38587824591`、`38588464182` 均为 success） |
| **E6** | tag 触发 Release | ✅ | push `v5.0.0` → 自动发布 Release 并附带 APK + `SHA256SUMS.txt` + Release 正文 |
| **E7** | 发布正式可用版本 | ✅ | https://github.com/Xiaoxiaoyu1321/PermanentProxy-for-WearOS/releases/tag/v5.0.0 |

---

## 6. 风险与注意事项

- **Shizuku 需手动启动**：非 root 设备每次重启后必须重新启动 Shizuku（Android 11+ 可在手表上直接开无线调试启动；更旧版本需要电脑 adb 一次）。这不是本项目能绕过的限制，README 中已明确说明。代理设置本身仍然跨重启保留。
- **Wear OS 上安装 Shizuku**：Shizuku 是标准 APK，可通过 `adb install` 侧载到手表，或从手机的 Play 商店推送。
- **Release 签名**：仓库中的 `keystore/permanentproxy-release.jks` 是**公开的测试密钥**（口令 `permanentproxy`），目的是让 CI 与任何从源码构建的人都产出**签名一致、可原地升级**的 APK。若要正式分发（Play/F-Droid），请通过环境变量 `RELEASE_STORE_FILE` / `RELEASE_STORE_PASSWORD` / `RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD` 换成自己的密钥。
- **`settings put global http_proxy` 的生效时机**：部分老系统需要重启才完全生效，因此保留「关闭代理并重启」选项。
- **minSdk 由 23 提升到 24**：`dev.rikka.shizuku:api` 的清单要求 minSdk ≥ 24；Wear OS 2.0 起就是 API 25，因此实际不损失任何设备。
- **GitHub-hosted runner 的 Node 20 → 24 迁移**：仓库中已避开所有依赖 Node 20 的第三方 action（`android-actions/setup-android@v3`、`softprops/action-gh-release@v2`），改用 runner 预装 SDK 与 REST API，避免后续再次失效。
- **未做设备实机验证**：本次改造通过了完整编译、签名校验与产物检查，但 Shizuku 的实际授权弹窗与代理生效需要在真机/手表上验证。

