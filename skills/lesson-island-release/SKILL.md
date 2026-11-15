---
name: lesson-island-release
description: "处理课文岛 Lesson Island 的版本发布、发布准备与失败排查：确认 x.y.z，推送版本标签，由 GitHub Actions 构建、签名并发布 GitHub Release，核验下载产物。普通代码提交、一般构建和技能编辑不触发发版。"
---

# 课文岛版本发布

适用于 Lesson Island 仓库，包名为 `top.mossmoss.lessonisland`。默认向现有仓库 `whoarei/lesson-island` 推送版本标签，通过 GitHub Actions 完成签名发布，再下载正式产物核验。用户对本项目的发版请求及本次版本确认已授权这套流程，无需再询问是否推送或发布；用户明确要求仅本地交付时使用后面的本地分支。

以下文件路径均相对项目根目录。执行前读取 `AGENTS.md`、`README.md`、`.github/workflows/android-ci.yml` 和 Git 状态，核对 `origin` 指向现有目标。仓库中的 `skills/lesson-island-release` 是技能源文件，本机发现副本应与之同步；不要将本技能应用到其他 Android 项目。

## 发布版本号是必需输入

- 应用版本采用纯数字 `x.y.z`，每段为非负整数且不带多余前导零，例如 `0.2.0`、`1.0.0`。Git 标签使用 `v` 前缀，例如 `v0.2.0`。
- 版本号只来自用户对**本次发布**的明确指示或确认。当前 `versionName`、上一个标签、变更规模和历史发布消息都不能代替用户选择。
- **用户未说明本次发布版本号时，必须先询问：**“这次课文岛要发布哪个版本？请提供 x.y.z 版本号，例如 0.2.0。”等待明确回复后再继续。可以先做只读检查；不得自行递增、沿用当前版本、把建议当成确认，或因等待超时继续发布。
- 确认之前不修改版本，不构建发布产物，不创建发布提交或标签，不覆盖已有交付文件。收到不符合格式的版本号时，先请用户明确一个符合 `x.y.z` 的版本。
- 用户已经明确给出本次版本时直接执行，不重复询问。普通代码提交、一般构建、只读发布检查及技能编辑不等于发起一次发布，不修改版本或创建发布标签。

## 签名与产物约定

- `app/build.gradle` 的 `versionName` 和 `versionCode` 是版本来源；应用关于页从安装包读取版本。版本变化时提升 `versionCode`；若工作区已为同一个待发布版本完成提升，核查后沿用，避免每次重试重复提升。
- 默认读取仓库 Actions Secrets：`ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`；`ANDROID_KEY_PASSWORD` 可省略或留空，以沿用 keystore 密码。使用工作流自带的 `GITHUB_TOKEN`，无需另配 PAT。
- 用 `gh secret list --repo whoarei/lesson-island` 核对名称即可。已配置时直接沿用，不重复上传、不索要或尝试读取 Secret 的值。缺项时告知具体名称，用户可在本机运行 `py -B tools/configure_github_signing.py` 隐藏输入密码并上传现有签名材料；不要让用户把密码发到聊天或写入示例文件。
- 正式 APK 必须使用原用户证书；预期指纹以 `tools/ci_check.py` 的 `RELEASE_CERTIFICATE_SHA256` 为准。不得生成替代证书或修改预期指纹来绕过验证。云端签名由 `tools/github_release.py` 执行，临时 keystore 由脚本清理。
- **云端发版不要求本机存在 `.local/signing.properties`，其缺失不阻塞默认流程。** 只有本地签名构建、完整本地仪器测试才依赖该文件及原 `E:/Android/libb.keystore`；本地 Debug、测试包和 Release 沿用同一用户证书。
- 示例素材只能位于 `app/src/androidTest/assets/fixtures`。正式 APK 不得包含示例课文、图片或音频；首次安装从空课程开始。测试允许临时装载示例，结束时恢复原状态。
- `.local/`、`llm info.txt`、keystore、签名密码和本机配置不进入 Git 或源码包。只分发空白 `signing.properties.example`。
- Actions 中带 `unsigned` 的 APK 是检查产物，不能作为正式安装包分发或安装。正式交付从对应 GitHub Release 下载：`LessonIsland-x.y.z.apk`、`LessonIsland-x.y.z-source.zip`、`update.json`、`SHA256SUMS.txt`。仓库已公开，应用内更新直接读取本仓库 `releases/latest/download/update.json`，无需另建分发仓库或跨仓库 Token。

## 默认：GitHub Actions 发布

1. 核对本次明确版本、已有本地和远程标签、同名 Release 及工作区变更，保留无关改动。已有同一提交的待发布标签或草稿时进入运行核验与恢复，不重建提交或标签；目标已发布时核验既有发布。需要另发版本则取得新的明确版本号，不覆盖旧发布。核对必填 Secrets 名称与仓库权限；目标缺失或不符时先核对目的地，不擅自另建仓库。
2. 更新 `versionName`、必要的 `versionCode`，将本次变更整理到 `CHANGELOG.md` 的 `## x.y.z — YYYY-MM-DD` 节，更新 README 的版本、APK 路径与交付说明。日期采用用户所在时区；历史测试记录保留原版本与实际验证范围。
3. 本地预检可运行 `py -B tools/ci_check.py version` 和 `py -B -m unittest discover -s tools -p 'test_*.py' -v`。云端工作流负责完整构建、JVM 测试、Debug/Release Lint 和仪器测试代码编译；默认流程不以运行本地签名构建为前置条件。根据本次改动执行或复用有效设备回归，范围见后文。
4. 检查提交范围、私有文件排除和 `git diff --cached --check`，提交发布改动，创建指向该提交的注释标签 `vx.y.z`。按现有分支约定推送发布提交及**仅本次标签**到已核对的远程，可用原子推送。不得强推、移动已有标签或顺带推送其他标签。
5. 跟进 `.github/workflows/android-ci.yml` 中**该版本标签、该发布提交**对应的运行，不能只看 `main` 上的成功检查。等待 `Build, test and lint` 与 `Sign APK and publish Release` 均成功；不要在推送标签后立即结束任务。
6. 工作流先构建未签名检查包，再按同次运行的确切产物 ID 下载、验 SHA256、对齐、签名并核验原证书。源码 ZIP 来自同一标签提交，`update.json` 按签名 APK 的实际大小、SHA256、版本与最低系统要求生成。发布脚本先建草稿、上传四个正式文件、核对 GitHub 返回的名称、大小和 SHA256，再发布；沿用脚本，不另行手动构建或上传不同来源的 APK。
7. 用 `gh release view <标签> --repo whoarei/lesson-island --json isDraft,targetCommitish,assets,url` 确认已发布且目标提交正确，四个正式附件均齐全。下载本次附件到空目录，校验 `SHA256SUMS.txt` 中 APK、源码 ZIP、update.json 三项文件，并核对更新索引的版本、APK 地址、大小、SHA256 和源码提交，再整理到 `deliverables/`；不要用旧交付文件代替本次下载结果。
8. 在对应版本源码下运行 `py -B tools/ci_check.py apk <下载的APK路径> --signed`，核对包名、版本、原证书与正式包不含测试素材；核对源码 ZIP 的提交标识与标签提交一致，且不含私有配置。保留本次日志、设备验证与截图到 `test-results/vx.y.z-*`，最后核对标签、发布提交和工作区状态。

## 设备验证与真机安装

- 按改动范围验证正式 APK；可在全新专用模拟器检查安装、冷启动、空课程首页、首次家长 PIN 和创建课程入口。安装和 ADB 操作均指定明确设备序列号。自行启动的测试模拟器完成后关闭。
- 完整本地离线回归使用 `tools/test-android.ps1 -Device <明确设备序列号>`，测试集合以脚本为准，并需要原用户证书的本地签名配置。只有需要验证模型链路时才加 `-LiveApi`，它会消耗用户 API 额度。云端仪器测试编译成功不等于实际运行通过；烟雾测试也不等于完整设备回归。
- 缺少本地签名配置时，可手动运行 `.github/workflows/android-test-apks.yml`，等待 API 29 与 API 36 的设备回归均完成。工作流在运行器内签名、测试，只上传日志截图；离线用例统一维护于 `tools/android-test-classes.txt`，不得把同类中的真实模型 API 用例混入默认回归。
- 如用户授权真机安装，完成所需测试后用 `adb -s` 安装并核对启动与安装版本，沿用会话中的已有授权。不在真机执行会装载测试课程的仪器测试。签名冲突不自动卸载应用，先保护数据；手机拒绝安装时请用户处理设备端提示后重试。

## 失败与恢复

- 读取该运行的失败步骤日志并处理可恢复问题；仅发布任务失败时重跑失败任务，复用已通过检查的产物。可用 `gh run rerun <运行ID> --failed --repo whoarei/lesson-island`，再继续跟进。
- 新建草稿的列表查询存在短暂延迟，当前脚本已有有限次数的重试。同一错误重跑一次仍出现时，先定位原因，不无限重跑或重复创建发布。
- 同一提交的草稿可继续上传并校验，已发布 Release 不覆盖。重试不重复提升 `versionCode`、不移动标签。标签后的修复提交不冒充原标签源码；发现需修改发布源码时，说明影响并与用户确定版本安排。
- 签名 Secret 缺失、密码或别名错误、证书不匹配时，请用户修正具体配置项，不退回其他证书。缺少必要输入或设备端允许时暂停相应依赖步骤，说明已完成范围及缺项；不能把草稿、未签名 APK、旧产物或未完成运行报告为发布成功。

## 仅用户明确要求：本地构建或离线交付

使用 `.local/signing.properties` 和原用户 keystore，运行 `tools/build.ps1` 得到 `deliverables/LessonIsland-x.y.z.apk`，按改动执行设备回归并核对签名与内容。若要求完整本地发布，提交发布改动、创建注释标签，从该标签 `git archive` 生成源码 ZIP 与 SHA256 文件。凭据缺失时请用户填写私有配置，不生成临时证书。仅本地交付不推送标签；未远程发布时反馈应明确为本地交付。

## 完成反馈

简要给出版本、发布提交、标签、GitHub Release / 签名 APK 链接和实际验证范围，说明真机是否更新。有条件时附本机下载副本链接。区分已发布、草稿和本地交付，设备烟雾测试、完整回归与真实 API 测试分别按实际结果报告。
