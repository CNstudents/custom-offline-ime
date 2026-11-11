# 定制版输入法（完全离线 · Android 4.4 ~ 14）

一个纯本地运行的安卓输入法：**不申请网络权限、不上传输入内容、无广告、无埋点**。

- 名称：定制版输入法
- 包名：`com.dingzhi.ime`
- 技术栈：Kotlin + 原生 `InputMethodService`（自绘键盘 View，不依赖任何输入法框架库）
- 兼容范围：`minSdk 19`（Android 4.4）/ `targetSdk 34`（Android 14）
- 唯一依赖：`androidx.appcompat:appcompat:1.6.1` + `androidx.core:core:1.12.0`（低版本兜底，不含 Material / ConstraintLayout / 高版本 Jetpack）

---

## 已构建好的安装包（`apk/` 目录）

| 文件 | 大小 | 说明 |
| --- | --- | --- |
| `apk/定制版输入法-1.0.0.apk` | 2.5 MB | **推荐安装这个**，release 签名版，v1+v2+v3 签名，可装在 Android 4.4~14 |
| `apk/定制版输入法-1.0.0-debug.apk` | 3.2 MB | 调试版，带 `debuggable`，仅用于开发调试 |

已实测的包信息：`package=com.dingzhi.ime`、`sdkVersion=19`、`targetSdkVersion=34`、`application-label=定制版输入法`、权限仅 `VIBRATE`，词库 `assets/dict/*.txt` 已打进包内。

> 这两个包用一次性临时证书签名（证书未随工程提供）。后续若要发布升级包，请按第五节用 `keytool` 生成自己的证书并**一直沿用同一个证书**，否则覆盖安装会因签名不一致失败（需先卸载旧版）。

## 一、全部文件路径与职责

### 1. 工程构建文件

| 文件路径 | 职责 |
| --- | --- |
| `settings.gradle` | 仓库与插件管理，`include ':app'` |
| `build.gradle` | 根工程，AGP 8.6.1 + Kotlin 1.9.24 插件声明 |
| `gradle.properties` | AndroidX、JVM 内存、构建缓存配置 |
| `gradle/wrapper/gradle-wrapper.properties` | Gradle 8.7（仅 version 配置，见第五节说明） |
| `app/build.gradle` | 编译配置：minSdk 19 / targetSdk 34 / compileSdk 34、Java 8 字节码、依赖 |
| `app/proguard-rules.pro` | 混淆保留规则（默认关闭混淆，仅备用） |

### 2. 清单与输入法声明

| 文件路径 | 职责 |
| --- | --- |
| `app/src/main/AndroidManifest.xml` | 仅声明 `VIBRATE` 权限；IME 服务 `BIND_INPUT_METHOD` + `exported`；设置页为桌面入口 |
| `app/src/main/res/xml/method.xml` | 输入法子类型（中文全拼）、`settingsActivity` 指向设置页 |

### 3. Kotlin 源码（`app/src/main/java/com/dingzhi/ime/`）

| 文件路径 | 职责 |
| --- | --- |
| `CustomImeService.kt` | 输入法主服务：视图构建、按键分发、候选渲染、上屏/退格/回车/空格、皮肤与设置热更新 |
| `SettingsActivity.kt` | 设置页：启用状态、深浅皮肤、键盘高度/圆角/主题色、震动音效开关、自定义词库、版本说明、系统栏版本分支 |
| `core/Prefs.kt` | 全部设置项读写（SharedPreferences，应用私有目录） |
| `core/UserDictStore.kt` | 自定义词条 / 快捷短语存储（`pinyin\t词语`、`编码\t内容`，单类上限 500 条） |
| `core/PinyinEngine.kt` | 拼音引擎：全拼索引、简拼索引、前缀二分查找、联想、用户词优先；assets 懒加载 |
| `core/PinyinSyllables.kt` | 标准音节表 + 最长匹配切分（简拼计算） |
| `input/KeyDef.kt` | 按键模型与三套布局（字母 / 数字 / 符号），纯代码生成 |
| `view/KeyboardView.kt` | 自绘键盘：`Canvas.drawRoundRect/drawText`、按下高亮、退格长按连删 |
| `theme/Skin.kt` | 深浅两套皮肤 + 主题色/圆角注入 |
| `util/Compat.kt` | 版本兼容工具：`dp()`、震动权限（M 分支）、低内存设备判断 |
| `util/Haptics.kt` | 震动三级分支：API≥26 `VibrationEffect`，21~25 `vibrate(long[],-1)`，<21 `vibrate(long)` |
| `util/SoundFx.kt` | 系统按键音（`AudioManager.FX_KEY_CLICK`，API 1 起可用，无需版本分支） |

### 4. 词库（assets，纯 txt）

| 文件路径 | 职责 |
| --- | --- |
| `app/src/main/assets/dict/pinyin.txt` | 主词库。两种行格式：`pinyin 汉字串`（拆单字，按位置递减频率）、`pinyin 词语 频率` |
| `app/src/main/assets/dict/assoc.txt` | 联想词库：`前词 联想1 联想2 ...` |

### 5. 资源（`app/src/main/res/`）

| 文件路径 | 职责 |
| --- | --- |
| `layout/activity_settings.xml` | 设置页布局（ScrollView + LinearLayout + 卡片，无 ConstraintLayout） |
| `values/colors.xml` / `values-night/colors.xml` | 浅色 / 深色配色（由 App 内开关切换，不读系统暗色 API） |
| `values/styles.xml` | 主题与控件样式（`Theme.AppCompat.DayNight.NoActionBar`） |
| `values-v29/styles.xml` | Android 10+ 关闭 `forceDarkAllowed`，避免系统二次着色 |
| `values/strings.xml` | 全部中文文案 |
| `drawable/bg_card.xml`、`bg_input.xml`、`bg_primary_button.xml`、`bg_secondary_button.xml` | 卡片 / 输入框 / 按钮背景 |
| `drawable/ic_launcher_foreground.xml` | API 26+ 自适应图标前景（矢量键盘） |
| `mipmap-anydpi-v26/ic_launcher.xml`、`ic_launcher_round.xml` | 自适应图标声明 |
| `mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png`、`ic_launcher_round.png` | API 19~25 使用的位图图标 |

---

## 二、兼容适配点与代码位置（对照需求）

| 需求 | 实现位置 |
| --- | --- |
| 6.0+ 动态权限判断 | [Compat.kt](app/src/main/java/com/dingzhi/ime/util/Compat.kt) `hasVibratePermission()`：M 以下直接 true，M 以上 `ContextCompat.checkSelfPermission` |
| 分区存储 / 存储权限差异 | 全部数据只写应用私有 `SharedPreferences`（[Prefs.kt](app/src/main/java/com/dingzhi/ime/core/Prefs.kt)），不碰外部存储，模拟器与真机均零权限 |
| 输入法弹窗 / 全屏编辑差异 | [CustomImeService.kt](app/src/main/java/com/dingzhi/ime/CustomImeService.kt) `onEvaluateFullscreenMode()=false`；横屏不再进入老系统 ExtractView |
| 自定义 View / 动画差异 | 只使用 `Canvas.drawRoundRect/drawText`（API 1 起可用），不使用 `ViewPropertyAnimator`/`RippleDrawable` 等新特性 |
| 震动 API 分支 | [Haptics.kt](app/src/main/java/com/dingzhi/ime/util/Haptics.kt)：≥26 `VibrationEffect.createOneShot`；21~25 `vibrate(long[],-1)`；<21 `vibrate(long)` |
| 深色模式 | 设置页开关 + `AppCompatDelegate.setDefaultNightMode` + `values-night`；`values-v29` 关 forceDark；不监听系统暗色 API |
| 词库懒加载 / 防 OOM | [PinyinEngine.kt](app/src/main/java/com/dingzhi/ime/core/PinyinEngine.kt)：后台线程 `Thread.MIN_PRIORITY`、单拼音最多 48 条、前缀最多 160 条；低内存机型候选数从 28 降到 14 |
| 状态栏 / 导航栏 | [SettingsActivity.kt](app/src/main/java/com/dingzhi/ime/SettingsActivity.kt) `applySystemBars()`：21/23/26 三段版本分支 |
| 12+ `exported` 必填 | [AndroidManifest.xml](app/src/main/AndroidManifest.xml) 中服务与 Activity 均显式声明 |

所有版本判断统一使用 `if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.XXX)`。

---

## 三、Android Studio 导入步骤

1. **准备环境**
   - Android Studio：Giraffe / Hedgehog / Iguana 及以上（需内置 JDK 17）。
   - 外部 JDK 建议 **17**（AGP 8.5 不支持 JDK 22+；若本机只有 JDK 24，请在 `File → Settings → Build Tools → Gradle` 把 Gradle JDK 切到 Studio 自带的 17）。
   - SDK 平台：Android 14 (API 34) + Build-Tools 34.x。若未安装，打开 `Tools → SDK Manager` 勾选安装。

2. **导入工程**
   - `File → Open`，选择本目录 `个人定制离线输入法`（含 `settings.gradle` 的目录），不要选 `app` 子目录。
   - 首次同步会下载 AGP 8.6.1 / Kotlin 1.9.24 / AppCompat 1.6.1。
   - `settings.gradle` 已把 Google Maven 换成 `https://dl.google.com/dl/android/maven2` 镜像，国内网络下更稳定。
   - `gradle.properties` 中的 `android.overridePathCheck=true` 用于支持本工程放在中文目录下构建。

3. **关于 Gradle Wrapper（重要）**
   - 本工程只提供 `gradle/wrapper/gradle-wrapper.properties`，**未包含 `gradle-wrapper.jar`、`gradlew`、`gradlew.bat`**。
   - Android Studio 打开时会提示使用本机 Gradle 或自动补齐 wrapper，直接同意即可；命令行构建可执行：
     ```
     gradle wrapper --gradle-version 8.7
     ```
     或在 Studio 的 `Gradle` 面板点击 `Sync`，由 IDE 生成 wrapper。

4. **构建**
   - 菜单 `Build → Build Bundle(s)/APK(s) → Build APK(s)`；
   - 产物：`app/build/outputs/apk/debug/app-debug.apk`。
   - 本工程已用 **Gradle 8.7 + JDK 17 + AGP 8.6.1** 实际编译通过（`assembleDebug` 与 `assembleRelease` 均成功），命令行方式为：
     ```
     gradle assembleDebug
     ```

> 说明：本工程未引入 UnitTest / androidTest 依赖，`Build` 菜单直接构建即可，无需跑测试。

---

## 四、安装与启用教程

### 1. 安装
- 方式一：Android Studio 连接手机后点 `Run`。
- 方式二：把 `app-debug.apk` 传到手机，点击安装（需在系统设置中允许「安装未知来源应用」）。
  - Android 8.0+：设置 → 应用 → 特殊权限 → 安装未知应用 → 选择对应文件管理器/浏览器允许。
  - Android 4.4~7.1：设置 → 安全 → 未知来源。

### 2. 启用输入法
1. 打开桌面上的「定制版输入法」。
2. 点「去启用」，进入系统输入法列表，打开「定制版输入法」开关。
   - Android 4.4~8.1：设置 → 语言和输入法 → 当前输入法/键盘 → 勾选。
   - Android 9~14：设置 → 系统 → 语言和输入法 → 屏幕键盘/虚拟键盘 → 管理键盘 → 打开开关。
   - Android 11+ 会弹出「此输入法可以收集你输入的所有文字…」提示，选择「确定/允许」。
3. 返回 App 点「切换输入法」，在弹窗中选择「定制版输入法」。
4. 在任意输入框点按，即可看到键盘（底部候选栏 + 26 键）。

### 3. 日常使用
- 「中 / 英」键切换中英文；「123」「符号」「ABC」切换布局。
- 「↑」单击临时大写，双击锁定大写。
- 「空格」无候选时上屏原文；「回车」优先执行输入框动作（搜索/发送/完成）。
- 「退格」按住 0.4 秒后开始连续删除。

---

## 五、打包与签名步骤

### 1. 生成自己的签名证书（只需一次）
在 JDK 的 `bin` 目录下执行（Windows 示例）：

```
keytool -genkeypair -v -keystore dingzhi-ime.jks -alias dingzhiime ^
        -keyalg RSA -keysize 2048 -validity 36500 ^
        -storepass 你的密码 -keypass 你的密码 ^
        -dname "CN=Custom IME, OU=Personal, O=Personal, L=Beijing, ST=Beijing, C=CN"
```

### 2. 配置签名（二选一）

**方式 A：写在 `app/build.gradle`（推荐，便于 Studio 直接 Run release）**

在 `android { }` 内追加：

```gradle
signingConfigs {
    release {
        storeFile file("../dingzhi-ime.jks")
        storePassword "你的密码"
        keyAlias "dingzhiime"
        keyPassword "你的密码"
    }
}
buildTypes {
    release {
        signingConfig signingConfigs.release
    }
}
```

然后执行：

```
gradlew assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`。

**方式 B：命令行签名（不改工程文件）**

```
:: 1. 对齐（zipalign 位于 build-tools/34.0.0/）
zipalign -v -p 4 app-release-unsigned.apk app-release-aligned.apk

:: 2. 签名（apksigner 同一目录）
apksigner sign --ks dingzhi-ime.jks --ks-key-alias dingzhiime ^
               --out app-release-signed.apk app-release-aligned.apk

:: 3. 校验
apksigner verify --print-certs app-release-signed.apk
```

> minSdk 19 的包建议保留 v1（JAR）签名：`apksigner` 在未指定 `--v1-signing-enabled` 时会自动按 minSdk 开启 v1+v2，无需额外参数。
>
> 注意：`storePassword` 明文写进 gradle 文件不适合公开仓库，个人离线使用可接受；如需更安全，改用 `~/.gradle/gradle.properties` 中的变量。

---

## 六、扩展词库

`assets/dict/pinyin.txt` 每行两种写法，直接追加即可（拼音小写、不带声调、`ü` 用 `v`）：

```
# 单字（位置越靠前越优先）
ni 你尼泥
# 词语（第三个字段是频率，越大越靠前，0~9999）
nihao 你好 9900
```

`assets/dict/assoc.txt`：`前词 联想1 联想2 联想3`，最多取前 8 个。

修改后重新打包生效；词库在输入法首次弹出时后台加载（约几十毫秒到 1 秒，取决于机型）。

用户自定义词条 / 短语在设置页添加，优先级高于内置词库；快捷短语编码与按键输入的拼音串完全一致时排在最前（例如编码 `dz`，输入 `dz` 即可见）。

---

## 七、老旧安卓机型已知问题

| 现象 | 影响范围 | 原因与处理 |
| --- | --- | --- |
| 极少数输入框里拼音没有下划线 | 部分 Android 4.4~5.x 定制 ROM | 老输入框不支持 `setComposingText`，代码已 catch；候选与上屏仍正常 |
| 某些老 ROM 页面切换输入法弹窗不出现 | Android 4.4 定制 ROM | `showInputMethodPicker()` 异常已被吞掉，请用系统设置里的「当前输入法」切换 |
| 横屏时键盘可能被系统压缩 | Android 4.4~7.x 平板/横屏 | 已通过 `onEvaluateFullscreenMode()=false` 关闭全屏编辑；若仍异常，请在设置里调低键盘高度（190~240dp） |
| 512MB 内存老机型首次弹出略慢 | Android 4.4~6.0 低端机 | 词库后台低优先级加载；低内存机型候选数自动降到 14 条，减少 View 创建 |
| 按键震动无效 | 个别 ROM 关闭了系统触感 / 无马达 | 已按 API 选择震动接口并 catch 异常；请在设置中确认「按键震动」开启，且系统未开启省电限制 |
| 按键音效无声 | 系统「触摸音效」被关闭 | 音效跟随系统设置，需在系统「声音 → 触摸音效」中开启 |
| 深色皮肤在 Android 10+ 变灰/失真 | 少数 ROM 强制深色 | 已用 `values-v29` 关闭 `forceDarkAllowed`；如仍异常重启 App 即可 |
| 数字/密码输入框自动弹数字键盘 | 设计如此 | 识别到 `TYPE_CLASS_NUMBER/PHONE/DATETIME` 直接进数字页；密码框不启用拼音候选 |

---

## 八、隐私说明

- `AndroidManifest.xml` 中 **没有** `INTERNET`、`ACCESS_NETWORK_STATE`、定位、相机、读存储、读通讯录等任何权限，系统层面即无法联网。
- 输入内容仅存在于内存中的拼音缓冲与 `InputConnection`，不写文件、不上传、不打点。
- 唯一持久化数据是用户主动添加的自定义词条/短语与设置项，保存在应用私有目录，卸载即删除。
