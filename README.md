# 课文岛 · Lesson Island

Android 单机英语课文学习软件。原生 Java UI，无账号、无自建服务器、无广告。支持 Android 8.0（API 26）及以上。自动构建与发布结果见对应标签的 GitHub Actions 运行；设备验证另行记录在 `test-results`，历史版本的验证范围见交付目录中的测试说明。

中文名：课文岛；英文名：Lesson Island；Android 包名：`top.mossmoss.lessonisland`。英文系统的应用名称显示为 Lesson Island。包名、源码命名空间、测试入口和安装脚本已保持一致。

旧包名 `com.textisland.app` 对应独立的 Android 应用，无法直接覆盖安装为新包名。已有课程和学习记录可从旧版家长设置导出 ZIP，再在新版导入；API 配置与家长 PIN 需要在新版设置。本地构建使用用户提供的 `E:/Android/libb.keystore`。

## 开始使用

当前版本为 0.3.0，新增实际前台学习计时、独立的课文与复习断点、已完成关卡自由重练，以及家长模式中的应用更新。

从 [GitHub Release v0.3.0](https://github.com/whoarei/lesson-island/releases/tag/v0.3.0) 下载 `LessonIsland-0.3.0.apk`，本机交付副本为 `deliverables/LessonIsland-0.3.0.apk`。仓库已公开，下载无需 GitHub 账号。正式版首次启动没有课程，首页会显示「家长创建课程」。工程中的四组图片与生成素材只供调试测试，放在 Android 测试包中，不随正式应用安装。

1. 点击首页「家长创建课程」，或右上角「家长入口」。首次进入时设置并确认自己的 4 位家长 PIN，随后即可进入制课页面。
2. 家长设置 → 直接填写 API 地址、模型名称和密钥，保存设置。可用“测试模型连接”和“测试语音并试听”分别验证文本模型和语音服务。
3. 家长首页或课程工作台 → 创建课程。一次最多 8 张 JPG/PNG 图片；按课文阅读顺序选择，必要时使用“上移”调整顺序。尚未配置 API 时，制课页会提供「配置制课服务」入口。
4. 生成课文、逐句中文、重点词汇/短语/句子、阅读理解题和英文音频。生成结果保存为草稿。可对照原图逐条编辑、添加、删除素材和校对答案。
5. 音频全部下载后，确认已校对，发布课程。发布只发生在本机。
6. 切到学生模式，依次完成“初识课文、词汇探险、短语搭桥、听力寻宝、句子工坊、理解挑战”六关。

Android 文件选择器只显示手机中的文件。将新课文图片先复制到手机“下载”目录。服务配置直接在家长设置中填写，不再提供配置文本导入入口。密钥通过 Android Keystore 的 AES-GCM 加密保存；安装包和源码不包含用户的密钥。家长 PIN 没有预设值，请记住首次设置的 PIN。

家长设置不回显已保存的 API 密钥，输入框留空并提示「已保存密钥；留空保持当前密钥」。右侧小眼睛仅用于显示或隐藏本次新输入的内容；保存后再次清空输入框，留空保存不会覆盖原密钥。切换显示时保留输入和光标，进入后台或离开设置页后自动隐藏。服务测试使用当前表单，可以先修改地址、模型、密钥、音色与语速，再试听，满意后保存；测试不会自动保存配置，失败时保留输入。

句子和重点素材的校对窗口均提供音频试听、重新生成和直接录音。生成使用已保存的语音配置；录音无需联网，首次使用请求麦克风权限，最长两分钟，停止后可试听。新音频仅在点击「保存」时生效；取消、生成失败或未完成的录音保留原音频。修改英文后需要为新文本重新准备音频，避免继续使用旧文本录音。同一句英文的某条素材替换音频，不会覆盖其他素材或其他课程的音频。自定义音频用于素材朗读、闯关和复习，并随 ZIP 备份恢复。

从旧版升级时，原先自动导入且未修改的示例课程会移出课程列表，原文件保留在本机归档中。家长自己创建或修改过的课程、学习进度和 API 配置会保留。

## 学习与复习规则

0.2.0 的界面改版见 [界面设计说明](docs/ui-design.md)。使用原创读书小海龟「芽芽」、海岛路线和记忆花园主题；已解锁的路标和课程卡片可以直接点击，答题与继续按钮固定在底部。成长徽章根据真实的完成关卡、整篇通关和学习天数计算，课程素材仍只由家长创建。

课文及重点素材列表可以直接轻点内容，从所点位置依次连播至末尾；再点当前内容，或点击播放时出现的底部状态栏即可停止。点击其他内容会从新位置开始；停止后再点从该项开头重播。播放项高亮并自动跟随，手动滚动后音频继续、页面保持自由浏览，重新点击内容可再次跟随。在家长模式中，长按未播放的内容即可校对；编辑时会停止播放。已发布、曾发布或有学习记录的版本会在开始编辑时自动建立新草稿。离开素材页、切换标签或进入后台时停止朗读。

课文正文采用紧凑的英中逐句对照排版：一句英文，下一行紧接对应中文，然后显示下一组。所有句子统一左对齐、顶格开始，长句换行也不缩进，不拆成逐句卡片。逐句操作直接作用于对应的文字范围，中英文可同时高亮当前句；已在视野内的句子不触发自动滚动。重点词汇和短语保留独立的素材条目。

家长制作页点击标题即可修改标题与主题，已发布版本会自动建立新草稿后打开编辑。制作状态右侧的下载图标用于下载或补全离线音频，纸飞机图标用于校对后发布；长按图标可查看说明。制作音频时图标暂时禁用，进度在下方显示。发布后图标显示勾选状态，点击可确认下架。

- 初识课文：整篇按一句英文、一句中文连续展示；轻点任一句从那里开始连读，再点当前句停止。底部可减速、加速（0.5–2 倍）或完成本关，退出后保留朗读位置和倍速。
- 词汇、短语：点选意思后立即判题；答对标绿，答错将所选项标红并标绿正确项。极少素材无法构造唯一干扰项时使用学习卡片。
- 听力：听英文，选择对应中文；作答前不显示英文答案。
- 句子工坊：听音拼写和点选词块组句；重复词块分别处理。
- 理解：根据课文回答问题，提交后展示依据。
- 错题在本轮末尾重新出现，必须纠正才能完成本轮。
- 每关首次完成 +20 XP；已完成关卡可直接进入自由重练，不覆盖主线断点，不改变正式进度、XP、正确率或错题安排。复习不加 XP。
- 正确内容在 1、3、7、14、30 天后逐次复习；答错重置间隔，纠正后从 1 天开始。
- 每篇课文分别保存当前关卡的题目位置、错题再练队列和已完成作答；点击当前关卡直接继续，点击旧关卡从头自由重练，均不弹出放弃练习的选择框。
- 今日复习和错题复习分别保存，与各课文的主线练习互不覆盖，退出后可独立续上。
- 今日小目标累计学生在闯关、复习、自由重练和素材阅读页面的实际前台时长，未完成也计入。每两秒保存检查点，退出或进入后台时结算；主页、家长管理和后台不计时，跨天分开记录，目标显示到秒。
- 学习概览包含完成关卡、正式作答正确率、学习时长、最近记录及历史错题。正确率包含本轮错题再练的作答，不包含自由重练。

家长可以直接点击已发布课文的标题、长按句子或重点素材、编辑理解题，应用自动建立新草稿并进入相应编辑窗口。草稿显示创建日期时间；单纯查看和朗读不会建立草稿。未发布且没有学习记录的草稿继续在原草稿中编辑，不会反复复制。

课程工作台将同一课文集中展示，点击「草稿与版本」可任选一份继续编辑，也可从当前版本「新建草稿」。未发布且没有学习记录的草稿可删除；原图和音频由同课文的其他版本继续使用。任一草稿校对并发布后，成为学生看到的唯一当前版本，其他草稿和历史版本保留。旧版本的学习记录保留在旧版本，新版本独立记录进度；历史版本也可重新发布。下架后学生暂时不可见，原有进度保留。每次重新进入应用，家长模式需要重新验证 PIN；文件选择期间保持家长操作会话。

同课文关联、草稿创建时间与当前发布版本随 ZIP 备份恢复。旧版没有关联信息的课程保持独立，不按同名标题自动合并。

## 网络与数据

新课制作和首次音频生成需要联网，图片及文本发送到家长配置的模型 API。已下载的课程和音频可以离线学习，不依赖设备安装英语 TTS 引擎。系统 TTS 仅作为缺失音频的临时回退，正式发布仍要求完整离线音频。

默认使用 `https://api.xiaomimimo.com/v1`、图片模型 `mimo-v2.6-flash`、语音模型 `mimo-v2.5-tts`、英语音色 Mia 和慢速跟读。音色可选 Mia、Chloe、Milo、Dean 等预置名称，也可填写兼容服务的自定义音色名称。生成语速提供慢速、标准、较快三档，通过 MiMo 支持的风格指令控制节奏，不代表精确的倍速参数；切换设置只影响新生成的音频，现有素材可单独重新生成。模型名称可配置，语音接口采用 MiMo 的 chat completions 音频协议；其他服务须兼容该协议，支持的音色和节奏以服务端为准。接口依据：[MiMo 语音 API](https://mimo.mi.com/docs/en-US/api/audio/tts-post)。

家长设置支持 ZIP 备份和恢复：包含课程、原图、缓存音频和学习记录，不含 API 密钥、家长 PIN。导入合并课程，同编号课程和学习记录以备份为准，未完成的本轮练习不恢复。单份备份解压后最多 64 MB。先完整校验备份，再写入；恢复中断时下次启动回滚到恢复前。建议换手机或卸载前先导出备份。软件不会自动同步到云端。

`deliverables/课程素材.zip` 是另行提供的基础素材包，供查阅或二次开发；它不是应用内部的备份格式。素材包里有逐句中英文本、重点素材、原图、MP3 和带音频相对路径的 `素材索引.json`。相同英文复用同一音频。音频文件名为英文原文 UTF-8 的 SHA-256。

## 应用更新

家长设置 → 应用更新，可以查看当前版本并手动检查。进入家长模式时默认每天自动检查一次，可在同一页面关闭；自动检查失败时保持安静，手动检查会给出结果。更新检查从本仓库公开 Release 读取信息，不发送课程或模型 API 密钥。

发现新版后可查看说明、下载、取消或重试。安装包通过大小、SHA256、包名、精确版本、系统要求和原签名证书校验后，才会请求打开系统安装器；首次可能需要在 Android 设置中允许课文岛安装更新。系统仍需用户确认，覆盖升级保留课程、学习记录、家长 PIN 与 API 配置。

更新索引为 `https://github.com/whoarei/lesson-island/releases/latest/download/update.json`，不需要在应用中保存 GitHub Token。0.2.0 尚无内置更新入口，需先手动安装 0.3.0，此后可使用应用内更新。

## 构建

本项目的发布流程由 [课文岛版本发布技能](skills/lesson-island-release/SKILL.md) 维护，可通过 `$lesson-island-release` 调用。发布使用 `x.y.z` 版本号；未指定本次版本号时，必须先向用户确认。

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
