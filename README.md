# 课文岛 · Lesson Island

Android 单机英语课文学习软件。原生 Java UI，无账号、无自建服务器、无广告。支持 Android 8.0（API 26）及以上，已在 Android 10 和 Android 16 模拟器上执行验证；实际验证结果见 `test-results` 和交付目录中的测试说明。

中文名：课文岛；英文名：Lesson Island；Android 包名：`top.mossmoss.lessonisland`。英文系统的应用名称显示为 Lesson Island。包名、源码命名空间、测试入口和安装脚本已保持一致。

旧包名 `com.textisland.app` 对应独立的 Android 应用，无法直接覆盖安装为新包名。已有课程和学习记录可从旧版家长设置导出 ZIP，再在新版导入；API 配置与家长 PIN 需要在新版设置。本地构建使用用户提供的 `E:/Android/libb.keystore`。

## 开始使用

安装 `deliverables/LessonIsland-0.1.2.apk`。正式版首次启动没有课程，首页会显示「家长创建课程」。工程中的四组图片与生成素材只供调试测试，放在 Android 测试包中，不随正式应用安装。

1. 点击首页「家长创建课程」，或右上角「家长入口」。首次进入时设置并确认自己的 4 位家长 PIN，随后即可进入制课页面。
2. 家长设置 → 导入 `llm info.txt`，选择用户提供的配置文件。也可以手动填写 API 地址、模型和密钥。点击“测试模型连接”。
3. 家长首页或课程工作台 → 创建课程。一次最多 8 张 JPG/PNG 图片；按课文阅读顺序选择，必要时使用“上移”调整顺序。尚未配置 API 时，制课页会提供「配置制课服务」入口。
4. 生成课文、逐句中文、重点词汇/短语/句子、阅读理解题和英文音频。生成结果保存为草稿。可对照原图逐条编辑、添加、删除素材和校对答案。
5. 音频全部下载后，确认已校对，发布课程。发布只发生在本机。
6. 切到学生模式，依次完成“初识课文、词汇探险、短语搭桥、听力寻宝、句子工坊、理解挑战”六关。

Android 文件选择器只显示手机中的文件。将电脑上的 `llm info.txt` 和新课文图片先复制到手机“下载”目录。密钥通过 Android Keystore 的 AES-GCM 加密保存；安装包和源码不包含用户的密钥。家长 PIN 没有预设值，请记住首次设置的 PIN。

家长设置的 API 密钥默认隐藏。输入框右侧的小眼睛可以显示或隐藏当前内容，也能查看已经保存的密钥；切换时保留输入和光标，进入后台或离开设置页后自动隐藏。确认内容后可用「测试模型连接」验证服务是否可用。

从旧版升级时，原先自动导入且未修改的示例课程会移出课程列表，原文件保留在本机归档中。家长自己创建或修改过的课程、学习进度和 API 配置会保留。

## 学习与复习规则

- 听读课文：逐句朗读，可展开中文，鼓励学生自己再读一次。
- 词汇、短语：选择意思；极少素材无法构造唯一干扰项时使用学习卡片。
- 听力：听英文，选择对应中文；作答前不显示英文答案。
- 句子工坊：听音拼写和点选词块组句；重复词块分别处理。
- 理解：根据课文回答问题，提交后展示依据。
- 错题在本轮末尾重新出现，必须纠正才能完成本轮。
- 每关首次完成 +20 XP，重复练习保留记录，不重复加经验；复习不加 XP。
- 正确内容在 1、3、7、14、30 天后逐次复习；答错重置间隔，纠正后从 1 天开始。
- 自动保存当前题目位置、重练队列和已完成作答。后台停留时间不计入学习时长。
- 学习概览包含完成关卡、作答正确率、学习时长、最近记录及历史错题。正确率按实际全部作答次数计算，含重练。

发布后的课程通过“复制为新草稿并编辑”修改，避免改变孩子正在学习的题目。下架后学生暂时不可见，原有进度保留。已有学习记录的草稿同样通过复制修改。每次重新进入应用，家长模式需要重新验证 PIN；文件选择期间保持家长操作会话。

## 网络与数据

新课制作和首次音频生成需要联网，图片及文本发送到家长配置的模型 API。已下载的课程和音频可以离线学习，不依赖设备安装英语 TTS 引擎。系统 TTS 仅作为缺失音频的临时回退，正式发布仍要求完整离线音频。

默认使用 `https://api.xiaomimimo.com/v1`、图片模型 `mimo-v2.6-flash`、语音模型 `mimo-v2.5-tts`，内置英语音色 Mia。模型名称可配置，语音接口采用 MiMo 的 chat completions 音频协议。接口依据：[MiMo 语音 API](https://mimo.mi.com/docs/en-US/api/audio/tts-post)。

家长设置支持 ZIP 备份和恢复：包含课程、原图、缓存音频和学习记录，不含 API 密钥、家长 PIN。导入合并课程，同编号课程和学习记录以备份为准，未完成的本轮练习不恢复。单份备份解压后最多 64 MB。先完整校验备份，再写入；恢复中断时下次启动回滚到恢复前。建议换手机或卸载前先导出备份。软件不会自动同步到云端。

`deliverables/课程素材.zip` 是另行提供的基础素材包，供查阅或二次开发；它不是应用内部的备份格式。素材包里有逐句中英文本、重点素材、原图、MP3 和带音频相对路径的 `素材索引.json`。相同英文复用同一音频。音频文件名为英文原文 UTF-8 的 SHA-256。

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

当前交付目录的 `LessonIsland-0.1.2.apk` 使用 `E:/Android/libb.keystore` 签名。证书 SHA-256：`28c461e4f2f8612f9d7717d727e772177474798fd7c48b03ca3ffbca8c81bf0a`。可以覆盖安装同一证书的 0.1.0 / 0.1.1，保留用户数据。原开发证书与 `libb.keystore` 不同，已有安装不能直接跨证书覆盖；请先备份学习数据，或使用干净的测试模拟器。

0.1.0 的签名与真机安装证据保存在 `test-results/libb-*`；后续版本的构建、回归与真机记录按版本号保存在 `test-results/vx.y.z-*`。

仅构建调试版本：

```powershell
.\tools\build.ps1 -DebugOnly
```

## 测试

请使用专用测试模拟器；脚本会安装测试应用。测试构建与正式版使用同一份本地签名配置，不应在有重要学习记录的设备上执行安装测试。首次从原开发证书切换到用户 keystore 时，应先备份数据或使用干净的测试模拟器。

```powershell
# 在已启动的专用模拟器上跑 8 项离线流程测试
.\tools\test-android.ps1 -Device emulator-5580

# 额外执行真实 API 图片 → 课程 → 音频 → 发布 → 学习测试
# 需要根目录存在用户自己的 llm info.txt，会使用 API 额度
.\tools\test-android.ps1 -Device emulator-5580 -LiveApi

# 单元测试及静态检查
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
```

测试不会写入源码中的 API 密钥。测试配置临时送入模拟器私有目录，读入加密存储后删除。功能测试临时创建专用课程，结束后恢复原学习状态。日志和实际屏幕截图保存在 `test-results`。

## 代码结构

| 文件 | 职责 |
| --- | --- |
| `MainActivity.java` | 家长与学生界面、图片文件选择、校对、发布、学习会话 |
| `Learning.java` | 拼写比对、词块切分、复习间隔、正确率规则 |
| `Store.java` | 课程与进度、题目生成、本机原子存储、备份恢复与回滚 |
| `AiClient.java` | 多图识别、逐句翻译、重点提炼、理解题及 MiMo TTS 请求 |
| `Audio.java` | 缓存、离线 MP3、全文连播、慢速播放、系统语音回退 |
| `Secrets.java` | Keystore 加密密钥、加盐 PIN 摘要 |
| `app/src/androidTest/assets/fixtures` | 测试专用的 4 门示例课程、原图和音频，不进入正式安装包 |
| `app/src/androidTest` | 在真实 Android 运行时执行的集成与界面流程测试 |

可选调试素材工具：`node tools/prepare-content.mjs --audio` 使用本地配置生成测试素材并复用缓存；`node tools/export-content.mjs` 导出单独查阅的示例素材包。生成路径为 `app/src/androidTest/assets/fixtures`，不会将示例课程写入正式安装包。构建不需要再次调用模型 API。

本版本按单设备、单学生设计。朗读训练采用听读及自评，不包含麦克风发音评分。图片识别、翻译和自动出题需要家长在发布前核对；遇到超时或限流，原图和已完成音频保留，可以稍后重试。
