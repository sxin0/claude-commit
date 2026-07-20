# Claude Code Commit

JetBrains IDE 插件:在提交面板中一键调用 [Claude CLI](https://docs.anthropic.com/en/docs/claude-code) 根据当前改动自动生成 Git 提交消息。

## 工作原理

1. 点击提交面板工具栏(提交消息输入框上方)的 ⚡ 按钮(未勾选任何文件时按钮置灰;生成期间按钮变为 ⏹ 停止按钮,点击可取消)
2. 插件把「内置提示词 + 自定义提示词 + 勾选文件的路径列表」发送给本机的 `claude -p`
3. Claude 通过 `--allowedTools` 授权的**只读** git 命令(`git diff` / `log` / `show` / `status` 等)自行查看这些文件的改动
4. 按约定格式从 Claude 输出中提取 `<commit-message>...</commit-message>` 标签内的内容,填入提交消息输入框

diff 不经过插件中转,因此没有长度截断问题;授权的命令均为只读,Claude 无法修改工作区或仓库。

提示词分为两部分:

- **内置部分(不可修改)**:定义任务、提交消息约定(首行摘要 ≤ 50 字、要点列表)以及输出格式契约(标签包裹)。提取逻辑依赖这份契约,因此固定在代码中。
- **自定义部分(可修改)**:在 **Settings > Tools > Claude Code Commit** 中编辑,用于补充语言、风格等要求。默认为:

  ```
  请用中文回答。回答时请遵循以下原则：
  - 简洁明了，避免冗长描述
  - 只提供必要信息，省略无关细节
  - 直击要点，不重复啰嗦
  ```

## 前置条件

- 已安装并登录 [Claude CLI](https://docs.anthropic.com/en/docs/claude-code)(命令行执行 `claude -p "hi"` 能正常返回)
- JetBrains IDE 2024.2 及以上(需要 JBR 21 运行时)

## 设置项

**Settings > Tools > Claude Code Commit**:

- **Claude 命令** — CLI 的命令名或绝对路径,默认 `claude`。IDE 从 GUI 启动时若找不到命令,填绝对路径(`which claude` 查看)
- **API 地址** — Anthropic 兼容 API 地址。留空使用 claude CLI 自身配置;使用第三方中转时填其地址
- **API Key** — 留空使用 claude CLI 已登录的凭据。填写后以 `ANTHROPIC_AUTH_TOKEN`(`Authorization: Bearer`)方式传递,适用于第三方中转;**保存在系统钥匙串(PasswordSafe),不写入配置文件**
- **模型** — 如 `claude-sonnet-5`。留空使用 claude CLI 默认模型
- **超时(秒)** — 单次调用超时,默认 120
- **自定义提示词** — 见上文

三项 API 设置均为可选,且只在填写后才通过 CLI 参数(`--model`、`--settings`)传给 claude:全部留空时不传任何额外参数,行为与直接在终端运行 `claude` 完全一致;配置后也仅对本插件的调用生效,不影响终端里的 claude。(Key 只以 `ANTHROPIC_AUTH_TOKEN` 传递、绝不设置 `ANTHROPIC_API_KEY`:CLI 对陌生的 `ANTHROPIC_API_KEY` 会等待交互确认,`-p` 模式下会永久卡住。)

## 安装

1. 下载/构建 `claude-commit-plugin.jar`
2. **Settings > Plugins > Install Plugin from Disk**,选择 jar 并重启 IDE

## 构建

`Makefile` 自动探测本机安装的 JetBrains IDE(使用其自带的 kotlinc、JBR 21 和平台 jar,无需额外安装工具链):

```bash
make            # 编译 + 打包 claude-commit-plugin.jar
make clean      # 清理
```

探测失败时手动指定:

```bash
make IDE_HOME="$HOME/Applications/IntelliJ IDEA.app/Contents"
```

## 已知限制

- 新增但尚未 `git add` 的文件(Unversioned Files)即使勾选也不会计入(git 没有它们的 diff;IDE 将文件加入 VCS 后即可)
- 多 Git 仓库项目中,claude 在项目根目录所在仓库内执行 git 命令
- Claude 需要自行执行 git 命令读取改动,耗时比直接传 diff 略长
