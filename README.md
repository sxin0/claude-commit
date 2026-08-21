# Claude Code [Kit]

JetBrains IDE 插件:把 [Claude CLI](https://docs.anthropic.com/en/docs/claude-code) 集成进 IDE——既能在提交面板一键生成 Git 提交消息,也能把文件引用直接送进终端里运行的 Claude Code 会话,另外附带几个围绕终端和项目窗口的快捷操作。

## 功能

- **生成提交消息** — 提交面板工具栏上的 ⚡ 按钮,把勾选的文件交给 Claude 生成提交消息并填回输入框。详见下方[工作原理](#生成提交消息的工作原理)
- **复制 / 发送 CC 引用** — 在编辑器或项目视图中右键(或按 Ctrl+Alt+K,macOS 为 Cmd+Alt+K),为当前文件与选区生成 Claude Code 引用(`@path#Lstart-end`),复制到剪贴板或输入到终端。**发送**只输入到**当前激活的终端标签页**,这一点有意区别于官方 *Claude Code [Beta]* 插件(后者会广播到所有已打开的 Claude 会话),因此可以并排开多个会话、把引用精确送到想要的那一个
- **自动打开 Claude Code** — 发送引用时若没有打开的终端,自动新建终端、启动 Claude CLI 并把引用输入进去(默认开启)
- **Cmd+1~9 切换终端标签页** — 焦点在终端工具窗内时,Cmd+N(Windows/Linux 为 Alt+N)切换到第 N 个终端标签页。焦点在别处、没有第 N 个标签页、或已经在第 N 个标签页时,仍是 IDE 原有行为——再按一次会照常打开对应编号的工具窗(默认开启)
- **Option+1~9 切换项目窗口(仅 macOS)** — 把第 N 个已打开的项目窗口切到前台,编号按窗口打开的先后顺序排列。焦点在终端里时同样生效,因此不用离开 Claude Code 会话就能在项目之间跳转(默认开启)
- **关闭项目前关闭终端** — 在项目关闭时先关掉终端工具窗,这样 shell 已不存在的空终端下次打开项目时不会再被恢复出来(默认关闭)
- **中英双语界面** — 界面与设置跟随 IDE 显示语言

> ⚠️ **Option+1~9 的键盘布局提醒**:在非美式 Mac 键盘布局上,Option+数字是输入字符用的(德语 `{` `}` = Option+8/9,西班牙语 `@` = Option+2)。IDE 的快捷键会先于字符输入拿到按键,所以这些布局下需要关掉该选项。

## 生成提交消息的工作原理

1. 点击提交面板工具栏(提交消息输入框上方)的 ⚡ 按钮(未勾选任何文件时按钮置灰;生成期间按钮变为 ⏹ 停止按钮,点击可取消)
2. 插件把「内置提示词 + 自定义提示词 + 勾选文件的路径列表」发送给本机的 `claude -p`
3. Claude 通过 `--allowedTools` 授权的**只读** git 命令(`git diff` / `log` / `show` / `status` 等)自行查看这些文件的改动
4. 按约定格式从 Claude 输出中提取 `<commit-message>...</commit-message>` 标签内的内容,填入提交消息输入框

diff 不经过插件中转,因此没有长度截断问题;授权的命令均为只读,Claude 无法修改工作区或仓库。

提示词分为两部分:

- **内置部分(不可修改)**:定义任务、提交消息约定(首行摘要 ≤ 50 字、要点列表)以及输出格式契约(标签包裹)。提取逻辑依赖这份契约,因此固定在代码中。
- **自定义部分(可修改)**:在 **Settings > Tools > Claude Code [Kit]** 中编辑,用于补充风格、语气等要求(输出语言由单独的「提交消息语言」设置控制,不在这里)。默认为:

  ```
  生成提交消息时请遵循以下风格：
  - 简洁明了，避免冗长描述
  - 只提供必要信息，省略无关细节
  - 直击要点，不重复啰嗦
  ```

## 前置条件

- 已安装并登录 [Claude CLI](https://docs.anthropic.com/en/docs/claude-code)(命令行执行 `claude -p "hi"` 能正常返回)
- JetBrains IDE 2025.3.6(build 253.33813.25)及以上,需要 JBR 21 运行时
- CC 引用、自动打开 Claude Code、终端标签页切换等功能需要启用捆绑的终端插件;Option+1~9 在终端内生效还需要使用新版(Reworked)终端引擎

## 设置项

**Settings > Tools > Claude Code [Kit]**:

- **Claude 命令** — CLI 的命令名或绝对路径,默认 `claude`。IDE 从 GUI 启动时若找不到命令,填绝对路径(`which claude` 查看)
- **API 地址** — Anthropic 兼容 API 地址。留空使用 claude CLI 自身配置;使用第三方中转时填其地址
- **API Key** — 留空使用 claude CLI 已登录的凭据。填写后以 `ANTHROPIC_AUTH_TOKEN`(`Authorization: Bearer`)方式传递,适用于第三方中转;**明文保存在插件配置文件 `claude-commit.xml` 中,不走系统钥匙串**,共享配置目录或同步设置时请留意
- **模型** — 如 `claude-sonnet-5`。留空使用 claude CLI 默认模型
- **超时(秒)** — 单次调用超时,默认 120
- **提交消息语言** — 跟随 IDE 语言 / 中文 / 英文
- **自定义提示词** — 见上文
- **终端分组** — 关闭项目前关闭终端、无终端时自动打开 Claude Code、Cmd+1~9 切换终端标签页
- **项目窗口分组** — Option+1~9 切换项目窗口

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
- Option+1~9 在终端内生效依赖新版终端的动作放行扩展点;切回旧版(Classic)终端引擎后,终端里的 Option+数字仍会进入 shell
- Windows/Linux 上 Alt+1~9 属于 IDE 工具窗,故项目窗口切换不预设快捷键,需要在 **Settings > Keymap** 中搜索「切换到第 N 个项目窗口」自行绑定
