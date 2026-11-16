# 开发与维护

## 构建

本项目的发布流程由 [课文岛版本发布技能](../skills/lesson-island-release/SKILL.md) 维护，可通过 `$lesson-island-release` 调用。发布使用 `x.y.z` 版本号；未指定本次版本号时，必须先向用户确认。

环境：JDK 17–24、Android SDK 36、Build Tools、联网下载首次 Gradle 依赖。本机已使用 `E:\Android\sdk` 和 JDK 24 验证。

```powershell
.\tools\build.ps1 -SdkPath 'E:\Android\sdk'
```

先在本机私有文件 `.local/signing.properties` 中填写签名信息，路径已设置为 `E:/Android/libb.keystore`：

```properties
storeFile=E:/Android/libb.keystore
storePassword=填写 keystore 密码
keyAlias=填写密钥别名
keyPassword=
```

若密钥密码与 keystore 密码相同，`keyPassword` 留空即可；不同则填写密钥密码。文件使用 UTF-8，Windows 路径建议用 `/`。项目根目录 `signing.properties.example` 是可分发的空白模板，请勿在模板中填写密码。需要查询别名时，可在终端运行 `keytool -list -keystore E:\Android\libb.keystore` 并按提示输入密码。

构建脚本执行调试构建、单元测试、Lint 和正式签名构建，并更新交付 APK。**本地 Debug、Android 测试包与 Release 均使用上述 keystore；配置缺失时会明确报错，不会生成或改用其他证书。** `.local` 已加入忽略规则，不包含在源码交付包中。请自行保管原 keystore 和密码，后续覆盖安装升级必须沿用相同证书。

0.3.0 继续使用原 `E:/Android/libb.keystore` 对应证书签名，GitHub Actions 从仓库 Secrets 中读取同一份签名材料。证书 SHA-256：`28c461e4f2f8612f9d7717d727e772177474798fd7c48b03ca3ffbca8c81bf0a`。可以覆盖安装同一证书的 0.1.x / 0.2.0，保留用户数据。原开发证书与 `libb.keystore` 不同，已有安装不能直接跨证书覆盖；请先备份学习数据，或使用干净的测试模拟器。

0.1.0 的签名与真机安装证据保存在 `test-results/libb-*`；后续版本的构建、回归与真机记录按版本号保存在 `test-results/vx.y.z-*`。

仅构建调试版本：

```powershell
.\tools\build.ps1 -DebugOnly
```

## GitHub CI

[Android CI](https://github.com/whoarei/lesson-island/actions/workflows/android-ci.yml) 在推送 `main`、提交面向 `main` 的 PR、推送 `v*` 标签时运行，也可以在 Actions 页面点击 **Run workflow** 手动运行。

- 检查使用单个 Ubuntu 24.04 任务、JDK 17 和 Gradle 缓存，最长 20 分钟；同一分支或 PR 的新任务取消旧任务，标签任务按顺序运行。
- 校验 `versionName` 为 `x.y.z`、`versionCode` 为有效正整数；标签任务额外要求标签等于 `v<versionName>`，且 `CHANGELOG.md` 有该版本的带日期发布记录。
- 构建 Debug、Release，执行 JVM 单元测试、两种构建的 Lint，并编译 Android 仪器测试代码。不会启动模拟器、连接真机或调用模型 API；设备回归仍按下节执行。
- 核对 Release APK 的包名与版本，拒绝混入 `assets/courses`、`assets/audio` 或 `assets/fixtures` 中的测试素材。
- Actions 产物保留 7 天：`LessonIsland-版本-unsigned-运行编号-尝试编号` 包含未签名 APK 与 `SHA256SUMS.txt`；`android-reports-运行编号-尝试编号` 包含测试与 Lint 报告，检查失败时也尽量保留报告。

检查任务显式传入 `-PciUnsigned=true`，不读取用户签名配置，也不需要 GitHub Secrets。**检查产物中的 APK 未签名，仅供构建与内容检查，不能直接安装或作为正式安装包分发。** 版本标签通过检查后，单独的发布任务会使用下述 Secrets 签名并自动发布 GitHub Release。本地普通构建仍要求原用户证书。

本地复现 CI 检查（使用本机 Android SDK、JDK 与 Python 3）：

```powershell
py -B tools/ci_check.py version
py -B -m unittest discover -s tools -p 'test_*.py' -v
.\gradlew.bat --no-daemon --console=plain -PciUnsigned=true :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:compileDebugAndroidTestJavaWithJavac
py -B tools/ci_check.py apk app/build/outputs/apk/release/app-release-unsigned.apk
```

### GitHub 自动签名发布

一次性配置需要本机安装 Python 3.11+、JDK（含 `keytool`）与已登录的 GitHub CLI。运行：

```powershell
py -B tools/configure_github_signing.py
```

脚本会提示输入现有密钥别名、keystore 密码与密钥密码（后两项隐藏输入，密钥密码留空沿用 keystore 密码），先核对证书与上面记录的发布证书一致，再将签名材料通过标准输入写入 `whoarei/lesson-island` 的 Actions Secrets。密码只在本机输入，不要发到聊天或填进示例文件。可通过 `--keystore`、`--alias` 和 `--repo` 指定其他路径、同一证书的别名或目标仓库。

也可在 [仓库 Actions Secrets](https://github.com/whoarei/lesson-island/settings/secrets/actions) 手动配置：

| Secret | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | 原 `libb.keystore` 文件的单行 Base64 |
| `ANDROID_KEYSTORE_PASSWORD` | 原 keystore 密码 |
| `ANDROID_KEY_ALIAS` | 原私钥别名 |
| `ANDROID_KEY_PASSWORD` | 私钥密码；可省略或留空以沿用 keystore 密码 |

本机默认 keystore 路径仍为 `E:/Android/libb.keystore`。配置工具只上传现有密钥，不生成替代证书、不创建标签或 Release。可用 `gh secret list --repo whoarei/lesson-island` 核对名称，列表不会显示值。Secrets 仅在版本标签的签名步骤中使用，临时 keystore 在步骤结束或失败时清理。

每次发布仍先由用户明确选择 `x.y.z` 版本，更新 `app/build.gradle` 的 `versionName`、递增 `versionCode`，并在 `CHANGELOG.md` 添加 `## x.y.z — YYYY-MM-DD` 发布说明。提交这些修改，创建指向该提交的注释标签 `vx.y.z`，再明确推送这个标签。CI 不自动选择版本、不创建或移动标签。

推送标签后，`Sign APK and publish Release` 自动等待检查任务通过，再下载**同一次运行的确切产物 ID**、校验 SHA256、对 APK 执行对齐与签名。它会再次检查包名、版本、测试素材，以及证书 SHA-256 是否与现有正式版一致。源码 ZIP 来自同一标签提交，并检查其中不含私有配置。GitHub Release 使用当前仓库自带的 `GITHUB_TOKEN`，无需另外配置 PAT；只有发布任务申请 `contents: write`。

Release 最终包含：

- `LessonIsland-x.y.z.apk`：可安装的正式签名 APK。
- `LessonIsland-x.y.z-source.zip`：对应提交的源码包。
- `SHA256SUMS.txt`：APK、源码 ZIP 和更新索引的 SHA256。
- `update.json`：应用内更新索引，包含版本、说明、APK 地址、实际大小、SHA256、最低 Android 版本与源码提交。

`SHA256SUMS.txt` 同时包含 APK、源码 ZIP 和 update.json 三项校验值。更新信息与签名 APK 在同一草稿中准备齐全、核验后一起发布，手机读取 latest 时使用最近一次完整发布。

发布说明取自该版本的更新记录，并记录源码提交和证书指纹。流程先创建草稿、上传文件、核对 GitHub 返回的文件名、大小和 SHA256，再自动发布。仓库和 Release 已公开，手机可直接读取更新索引和下载 APK。

失败后可在 Actions 重跑失败任务；草稿只允许同一提交继续上传，已发布 Release 不会覆盖。也可在标签对应的 Actions 页面运行工作流。仪器测试编译不等于设备回归，正式发布前仍按下面的流程按需验证模拟器或真机。

## 测试

请使用专用测试模拟器；脚本会安装测试应用。测试构建与正式版使用同一份本地签名配置，不应在有重要学习记录的设备上执行安装测试。首次从原开发证书切换到用户 keystore 时，应先备份数据或使用干净的测试模拟器。

```powershell
# 在已启动的专用模拟器上按维护的列表执行离线流程测试
.\tools\test-android.ps1 -Device emulator-5582

# 额外执行真实 API 图片 → 课程 → 音频 → 发布 → 学习测试
# 需要根目录存在用户自己的 llm info.txt，会使用 API 额度
.\tools\test-android.ps1 -Device emulator-5582 -LiveApi

# 单元测试及静态检查
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
```

测试不会写入源码中的 API 密钥。测试配置临时送入模拟器私有目录，读入加密存储后删除。功能测试临时创建专用课程，结束后恢复原学习状态。日志和实际屏幕截图保存在 `test-results`。

没有本机签名密码时，可手动运行 [Android device regression](https://github.com/whoarei/lesson-island/actions/workflows/android-test-apks.yml)。工作流在临时运行器内用现有用户证书签名应用和测试包，再在 API 29 / API 36 的专用模拟器执行 `tools/android-test-classes.txt` 中的离线用例；只上传日志和截图，测试 APK 不作为公开附件保留。该流程不创建版本标签、不发布应用，也不调用真实模型 API。

已有同一源码提交的签名测试包时，可用 `tools/test-android.ps1 -Device emulator-5582 -BuiltApksPath <目录>`，或用跨平台的 `tools/run_device_tests.py --serial emulator-5582 --apks <目录> --output <空报告目录>`；工具会先核对源码提交、SHA256 和用户证书。

## 代码结构

| 文件 | 职责 |
| --- | --- |
| `MainActivity.java` | 家长与学生界面、图片文件选择、校对、发布、学习会话 |
| `Learning.java` | 拼写比对、词块切分、复习间隔、正确率规则 |
| `Store.java` | 课程与进度、题目生成、本机原子存储、备份恢复与回滚 |
| `AiClient.java` | 多图识别、逐句翻译、重点提炼、理解题及 MiMo TTS 请求 |
| `Audio.java` | 缓存、离线 MP3、全文连播、慢速播放、系统语音回退 |
| `ItemAudioEditor.java` | 单条素材的音频生成、麦克风录制、试听和保存前暂存 |
| `ServiceConfig.java` | 模型与语音配置快照、音色和生成语速 |
| `Secrets.java` | Keystore 加密密钥、加盐 PIN 摘要 |
| `StudyTime.java` / `StudyTimeLedger.java` | 按日期累计前台学习时长、跨日拆分和旧记录迁移 |
| `AppUpdater.java` / `AppUpdateFlow.java` | 家长模式检查更新、下载校验与系统安装确认 |
| `UpdateRelease.java` / `UpdateApkProvider.java` | 更新元数据约束与受限的只读 APK 共享 |
| `app/src/androidTest/assets/fixtures` | 测试专用的 4 门示例课程、原图和音频，不进入正式安装包 |
| `app/src/androidTest` | 在真实 Android 运行时执行的集成与界面流程测试 |

可选调试素材工具：`node tools/prepare-content.mjs --audio` 使用本地配置生成测试素材并复用缓存；`node tools/export-content.mjs` 导出单独查阅的示例素材包。生成路径为 `app/src/androidTest/assets/fixtures`，不会将示例课程写入正式安装包。构建不需要再次调用模型 API。

本版本按单设备、单学生设计。朗读训练采用听读及自评，不包含麦克风发音评分。图片识别、翻译和自动出题需要家长在发布前核对；遇到超时或限流，原图和已完成音频保留，可以稍后重试。
