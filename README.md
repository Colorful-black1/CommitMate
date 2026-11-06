# Commit Mate

一个轻量的 JetBrains IDE 插件：读取当前 Git 仓库的**暂存区**变更，调用 OpenAI Chat Completions 兼容接口
（默认 DeepSeek）生成 Conventional Commits 风格的 Commit Message，由开发者自己审核、修改、复制并提交。

> **设计边界**：插件只负责"生成提交信息"，绝不执行 `git add` / `git commit` / `git push` / `git reset` /
> `git checkout` / `git restore`，也不修改工作区文件。

## 功能

- 四个入口：**提交面板工具栏按钮**（Amend/历史那一行的最右侧）、**Tools 菜单**、**VCS 菜单**、
  快捷键 **Ctrl+Alt+G**；也可通过 Action 搜索（双击 Shift 或 Ctrl+Shift+A）找到。
- **提交面板按钮不弹窗**：直接**流式**把中文提交信息写进 IDE 的提交框，只处理**勾选**的文件；
  再点一次可取消，取消或失败会恢复原有内容。仍然只写文本，提交由你自己点。
- 生成内容使用中文：`type(scope): 中文描述`，type 与 scope 保持英文约定。
- 变更来源：优先 `git diff --cached`（暂存区）；暂存区为空时自动改用工作区未提交的修改
  （`git diff`，不含未跟踪文件）。可在设置里关闭该回退，恢复为"只读暂存区"。
- 变更统计：新增 / 修改 / 删除 / 重命名的文件数量，以及完整的变更文件清单。
- 查看 Diff：窗口内直接预览本次送入模型的 Diff（含截断提示）。
- 生成 / 重新生成 / 复制：生成结果填入可编辑的多行文本框，可任意修改后一键复制。
- 多 Git Root 支持：项目包含多个仓库时可在下拉框切换；仓库识别不依赖 `project.basePath`。
- 超长 Diff 保护：超出上限时分文件裁剪并**显式提示**，绝不静默截断。
- 安全存储：API Key 只通过 IntelliJ Platform PasswordSafe（Windows 凭据管理器 / macOS Keychain 等）保存。
- 可取消：生成过程显示可取消的进度条，取消后立即结束等待（阻塞读写跑在独立 daemon 线程中）。
- 跨 IDE：只依赖 `com.intellij.modules.platform` / `com.intellij.modules.vcs`，不使用 Java/Python/Go 等语言模块 API，也不依赖 Git 插件。

## 环境要求

| 项目 | 版本 |
| --- | --- |
| 构建 JDK | 21（Kotlin/Java 字节码目标为 **17**，以兼容 2024.1 的 JBR 17） |
| Gradle | 9.8.1（通过 wrapper 自动下载） |
| Kotlin | 2.4.21 |
| IntelliJ Platform Gradle Plugin | 2.19.0 |
| 编译目标 IntelliJ Platform | 2024.1（IC，最低支持版本） |
| 最低支持的 IDE 版本（sinceBuild） | 241（2024.1） |
| 最高支持版本（untilBuild） | 不限制 |
| 重点验证的 IDE | IntelliJ IDEA 2025.3、WebStorm 2025.3 |
| 单元测试 | JUnit 5.11.4 |

> 说明：规格要求目标 IDE 为 2025.3+；为兼容仍在使用的 2024.1，插件把 `sinceBuild` 下调到 241
> （2024.1）并在 241 上编译，2025.3+ 仍通过 Plugin Verifier 验证。凭据存储对两代 PasswordSafe
> 做了反射适配（见 `PasswordStore.kt`）。

## 安装

1. 构建插件包：

   ```bash
   ./gradlew buildPlugin
   ```

   产物：`build/distributions/commit-mate-0.1.2.zip`

2. 在 IDE 中安装：`Settings → Plugins → ⚙ → Install Plugin from Disk…`，选择上面的 zip，重启 IDE。

### 关于 IDE 版本

插件最低支持 **2024.1（build 241）**，2024.1 及以上均可安装（2025.3+ 同样支持）。
若 IDE 更早（2023.x 及以前），会提示 "requires build 241 or newer"，需要升级 IDE。

也可以不安装，直接用沙箱 IDE 验证：

```bash
./gradlew runIde
```

该命令会启动一个已注入本插件的 IDE（即上面的编译目标版本），可直接验证完整流程。

## 使用

1. 在 Settings → Tools → Commit Mate 中填写：
   - **API Base URL**：默认 `https://api.deepseek.com/v1`
   - **Model**：默认 `deepseek-chat`
   - **API Key**：保存到 PasswordSafe
   - 可选：Temperature（默认 `0.2`）、最大 Diff 字符数（默认 `60000`）、请求超时（默认 `120` 秒）
2. 准备变更：`git add` 到暂存区，或者直接保持工作区修改（IDEA 新版提交面板不会提前 `git add`，
   插件会自动改用工作区未提交的修改）。
3. 打开 **提交面板工具栏按钮**、**Tools → Generate Commit Message**、**VCS → Generate Commit Message**，
   或直接按 **Ctrl+Alt+G**。
4. 点击 **Generate**，等待生成；也可以点 **查看 Diff** 确认送进模型的改动。
5. 在文本框里修改，点 **Copy**（或从提交面板打开时点 **填入提交框**），然后自己在 Git Commit 面板或终端完成提交。

## 本地开发

```bash
# 编译
./gradlew compileKotlin

# 运行单元测试
./gradlew test

# 在沙箱 IDE 中调试（会启动一个带有本插件的 IDE 实例）
./gradlew runIde

# 打包
./gradlew buildPlugin

# 校验插件配置
./gradlew verifyPluginConfiguration

# 使用 IntelliJ Plugin Verifier 做二进制兼容性验证（会下载目标 IDE，体积较大）
./gradlew verifyPlugin
```

Windows 上把 `./gradlew` 换成 `gradlew.bat`。

> 本仓库的构建环境做了两件事以适配当前机器：
> - `gradle.properties` 中 `org.gradle.java.installations.paths=D:/coding/JDK21` 指向本地 JDK 21；
> - 构建时通过环境变量 `GRADLE_USER_HOME` 把 Gradle 缓存放到非系统盘（可选）。

## 配置项

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| API Base URL | `https://api.deepseek.com/v1` | 实际请求 `{baseUrl}/chat/completions`；若已以 `/chat/completions` 结尾则不再拼接 |
| Model | `deepseek-chat` | 任何 OpenAI Chat Completions 兼容模型 |
| Temperature | `0.2` | 范围 0.0 – 2.0 |
| 最大 Diff 字符数 | `60000` | 超过后按文件裁剪并提示 |
| 请求超时 | `120` 秒 | 读取超时；连接超时固定 15 秒 |
| API Key | 无 | 仅保存在 PasswordSafe；窗口里留空表示沿用已保存的 Key |

## 设计说明

### 只读 Git

`GitDiffService` 只执行两条只读命令（`ProcessBuilder`，不经过 shell）：

```
git diff --cached --name-status --find-renames -z --no-ext-diff --no-color
git diff --cached --no-ext-diff --unified=3 --no-color --find-renames
```

同时设置 `GIT_TERMINAL_PROMPT=0`（不会卡在凭据提示）和 `GIT_OPTIONAL_LOCKS=0`（避免 git 在读取时写回索引）。
代码中不存在任何提交、暂存、重置类命令。

### 变更来源：暂存区优先，工作区兜底

`git diff --cached` 只有在你执行过 `git add` 之后才有内容；而 IDEA 2023.1+ 的新版提交面板
在点 Commit 之前**不会**把文件写入索引，所以"面板里有 2 个文件、暂存区却是空的"是正常现象。

因此读取顺序是：

1. 先读暂存区（`git diff --cached`）；
2. 暂存区为空时，再读工作区未提交修改（`git diff`），UI 会明确标注"未暂存"；
3. 两种都为空才提示"没有可生成的变更"。

设置里可以关掉第 2 步（`暂存区为空时，改用工作区未提交的变更`），关闭后严格只读暂存区。

限制：兜底模式读取的是整个仓库的未暂存修改，不会只挑提交面板里勾选的文件；未跟踪文件（untracked）
不会进入 Diff。需要精确控制提交范围时，先 `git add` 想要的文件即可。

### 提交面板一键生成（流式）

提交面板工具栏的按钮走另一条路径，目标是"最少点击"：

1. 从 `VcsDataKeys.COMMIT_WORKFLOW_UI` 取出**当前勾选**的变更（`getIncludedChanges()`），只处理这些文件；
2. 读取对应的暂存区 / 工作区 diff（同上文的双来源策略），并按文件过滤 diff 块；
3. 以 `stream: true` 请求模型，按增量把清洗后的文本写回提交框（约 80ms 合并刷新一次）；
4. 生成过程中再点一次按钮即取消；取消或失败时**恢复原有提交信息**。

如果 IDE 版本较旧、取不到 commit workflow 数据，则自动退回对话框模式。

### 仓库识别

从 `project.basePath`、`guessProjectDir()` 以及所有 content root 收集候选目录，逐级向上查找 `.git`
（目录或文件，兼容 worktree / submodule），去重排序后作为可选仓库列表，因此多 Git Root 项目也能正确识别。

### Diff 长度控制

`DiffCompactor` 在超过上限时按文件块均分预算：保留每个文件的头信息（`diff --git`、`---/+++`、hunk 头）
和尽可能多的行，并写入明确的省略提示；仍然超限时做行对齐的硬截断。截断信息会同时出现在 UI 状态栏、
Diff 预览窗口和发送给模型的 Prompt 中。

### 安全

- Git Diff 被视为不可信数据：System Prompt 明确要求忽略文件内容中出现的任何指令，User Prompt 再次强调。
- API Key 不写入 `commit-mate.xml`，不写入日志，也不会出现在通知文本里。
- 网络请求失败只展示服务端错误摘要（最多 500 字符），不打印 Diff。

### 线程与取消

- Git 读取在 `executeOnPooledThread` 中执行，结果通过 `SwingUtilities.invokeLater` 回到 EDT。
- 模型请求通过 `ProgressManager.runProcessWithProgressSynchronously` 在前台进度条中执行，可取消。
- 真正的阻塞 socket 读写放在独立的 daemon 线程里，调用线程以 100ms 轮询等待：取消时立即返回，
  不依赖 `HttpURLConnection.disconnect()` 是否真的中断了底层读操作（实测它并不可靠）。

### 跨版本兼容

同一份插件包同时支持 2024.1（build 241）到 2025.3+：

- 以最低支持版本 2024.1 作为编译目标，避免误用高版本 API；字节码目标固定为 Java 17。
- `PasswordSafe` 在 2025.3 从 `com.intellij.credentialStore` 迁到 `com.intellij.ide.passwordSafe`
  并从静态方法改为 application service，`PasswordStore.kt` 用反射按版本查找，两代都能工作。
- `CredentialAttributes` 的 Kotlin 默认参数桥接构造函数在 2025.3 被标记废弃，
  用 Java 工厂 `CredentialAttributesFactory` 绑定到未废弃的 `(serviceName, userName)` 重载，
  Plugin Verifier 因此报告 0 处废弃 API 用法。

## 项目结构

```
commit-mate/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradle/wrapper/…
├── gradlew / gradlew.bat
├── src/main/kotlin/com/commitmate/
│   ├── action/GenerateCommitAction.kt        # Tools 菜单 / Action 搜索入口
│   ├── core/OperationCanceledException.kt    # 与平台解耦的取消异常
│   ├── git/
│   │   ├── GitCommandRunner.kt               # ProcessBuilder 执行 git，只读
│   │   ├── GitDiffService.kt                 # 项目级服务：仓库定位 + 读取
│   │   ├── GitRepositoryLocator.kt           # 向上查找 .git（纯逻辑）
│   │   ├── StagedDiffParser.kt               # 解析 --name-status -z
│   │   ├── DiffCompactor.kt                  # Diff 长度控制
│   │   └── StagedChangesReader.kt            # 组合读取流程（纯逻辑）
│   ├── llm/
│   │   ├── ChatCompletionClient.kt           # HttpURLConnection + Gson，可取消
│   │   ├── CommitMessageSanitizer.kt         # 清洗代码块/前缀
│   │   └── LlmModels.kt                      # LlmConfig / ChatMessage / LlmException
│   ├── model/GitChanges.kt                   # 变更数据模型
│   ├── prompt/PromptBuilder.kt               # System / User Prompt
│   ├── service/CommitMessageService.kt       # Prompt + 调用 + 清洗
│   ├── settings/CommitMateSettings.kt        # 持久化配置 + API Key
│   ├── settings/PasswordStore.kt             # 跨版本 PasswordSafe 适配
│   ├── settings/CommitMateConfigurable.kt    # Settings → Tools → Commit Mate
│   ├── ui/CommitMateDialog.kt                # 主窗口
│   ├── ui/DiffPreviewDialog.kt               # Diff 预览
│   ├── ui/UiSupport.kt                       # Esc 关闭等小工具
│   └── util/CommitMateNotifications.kt       # 原生通知
├── src/main/java/com/commitmate/settings/
│   └── CredentialAttributesFactory.java      # 规避已废弃构造函数的 Java 桥接
├── src/main/resources/META-INF/plugin.xml
└── src/test/kotlin/com/commitmate/…          # JUnit 5 测试
```

## 测试

自动化测试（`./gradlew test`，共 51 个用例，全部通过）：

| 测试类 | 覆盖内容 |
| --- | --- |
| `StagedDiffParserTest` | `--name-status -z` 解析：修改、新增、删除、重命名、含空格路径 |
| `GitRepositoryLocatorTest` | 子目录向上查找、`.git` 文件形式、非 Git 目录、多仓库去重 |
| `StagedChangesReaderTest` | 真实 git 仓库：只读暂存区、新增、删除、重命名、无暂存、超大 Diff 截断 |
| `DiffCompactorTest` | 未超限原样返回、超限截断且有提示、保留文件头、非法参数 |
| `ChatCompletionClientTest` | 本地 HTTP 服务：200 正常解析、401/404 错误映射、空内容、非法 JSON、取消、无 Key |
| `CommitMessageServiceTest` | 端到端：请求包含 Prompt、响应代码块被清洗、空暂存区报错、URL 规范化 |
| `PromptBuilderTest` | System Prompt 规则、结构化上下文、截断提示 |
| `CommitMessageSanitizerTest` | 代码块 / 前缀 / 空行处理 |

需要手动验证的部分（`./gradlew runIde` 后操作）：

- [ ] IDE 能正常加载插件，Tools 菜单出现 Generate Commit Message
- [ ] 对话框正常打开，Light / Dark 主题下显示正常
- [ ] 无暂存内容时提示"当前没有已暂存的代码变更，请先执行 Git Add"
- [ ] 多 Git Root 项目能切换仓库并正确统计
- [ ] 生成中显示进度条，取消后 UI 恢复
- [ ] 生成结果可编辑、可复制，复制后出现原生通知
- [ ] API Key 未配置时提示填写
- [ ] `git log` 中不会出现任何插件自动创建的提交

## 已验证 / 未验证

**已验证**

| 验证项 | 结果 |
| --- | --- |
| `./gradlew compileKotlin` | BUILD SUCCESSFUL（无编译错误） |
| `./gradlew test` | 51 个测试全部通过 |
| `./gradlew buildPlugin` | 产出 `build/distributions/commit-mate-0.1.2.zip`（约 118 KB，未打包 Kotlin 标准库） |
| Plugin Verifier（IntelliJ IDEA 2024.1） | `IC-241.14494.240`：**Compatible** |
| Plugin Verifier（IntelliJ IDEA 2025.3） | `IU-253.28294.334`：**Compatible**，0 处废弃 API |
| Plugin Verifier（WebStorm 2025.3） | `WS-253.28294.332`：**Compatible**，0 处废弃 API |
| 字节码目标 | class file major version **61**（Java 17），可在 2024.1 的 JBR 17 上加载 |
| 平台 API 核对 | 基于 2024.1 与 2025.3 两代实际类签名核对；`PasswordSafe` 的类包与获取方式在两代之间不同，已用反射适配 |

**未验证（需要真实 IDE 手工操作）**

- UI 在真实 IDE 中的显示与交互（本构建环境无法启动图形化 IDE 完成点击验证，只完成了编译期与
  Plugin Verifier 的二进制兼容性验证）
- 真实 DeepSeek 账号的端到端调用（自动化测试用本地 HTTP 服务模拟了 OpenAI 兼容响应）
- PyCharm / GoLand / PhpStorm / Rider / DataGrip / CLion 的实机加载（结构上只依赖
  `com.intellij.modules.platform`，且 WebStorm 已通过 Verifier）
- 2025.2 及更早版本（2024.1 与 2025.3 之间的中间版本）的实机加载，二进制层面未发现差异

## 已知限制

- 只分析暂存区，不分析未暂存或未跟踪文件（这是第一版明确的范围）。
- 只支持 OpenAI Chat Completions 协议，不支持 Responses API / Anthropic Messages 等其它协议。
- 生成结果不会自动写回 IDE 的 Git Commit 面板，需要手动复制。
- 超大 Diff 采用按文件裁剪策略，极端情况下（单文件数千行）只有该文件的前若干行会进入 Prompt。
- Windows 上 git 输出按 UTF-8 解码；若仓库文件使用 GBK 等编码，Diff 中的中文可能显示为乱码（不影响提交信息生成）。

## 明确不做（第一阶段）

AI Agent、MCP、Codex / OpenCode / Claude Code 集成、自动 Git Commit / Push / Add、分支与冲突管理、
代码自动修改、AI 代码审查、多轮对话、账号体系、独立后端、数据库、复杂统计面板。
