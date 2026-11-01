# HexGuide —— 演示与 Iota 内嵌文档（备用 README）
# HexGuide — Demo & Iota Inline Documentation (Supplementary README)

本文档介绍 HexGuide 的新功能：**手册内嵌施法演示**（动画配置）与 **Iota 文本内嵌**（Inline 渲染）。主 README 见根目录 `README.md`。

This document covers HexGuide's new features: the **in-book spellcasting demo** (animation configs) and **Iota text inlining** (Inline rendering). See the main `README.md` for the rest of the mod.

---

## 目录 / Contents

- [1. 手册内嵌施法演示（Spellplay 动画配置）](#1-手册内嵌施法演示spellplay-动画配置)
- [2. Iota 文本内嵌（Inline 渲染）](#2-iota-文本内嵌inline-渲染)
- [3. 笔记分享串（剪贴板 / Clipboard share strings）](#3-笔记分享串剪贴板--clipboard-share-strings)
- [4. 语言文件 / 界面补充](#4-语言文件--界面补充)

---

## 1. 手册内嵌施法演示（Spellplay 动画配置）

### 1.1 是什么 / What it is

《咒术笔记》的 **guide** 分类中新增「施法演示」条目：书页内嵌真实的法阵绘制界面（`hexguide:spellcast_demo` 页面类型），自动播放一串预定义的步骤，演示 **图案绘制** 与 **栈变化**，全程带音效。

The HexBook's **guide** category now has a "Spellcasting Demo" entry: a real spellcasting grid embedded in the page (page type `hexguide:spellcast_demo`) that automatically plays a sequence of predefined steps, demonstrating **pattern drawing** and **stack changes**, complete with sounds.

### 1.2 配置文件 / Config files

演示配置存放在**数据包目录**，通过页面 JSON 引用：

Configs live in the **datapack directory**, referenced from the page:

```
data/<命名空间 ns>/spellplays/<名称 name>.json
```

页面引用 / Page reference:

```json
{
  "type": "hexguide:spellcast_demo",
  "demo": "hexguide:text",   // ns:name（默认 ns 为 hexguide）
  "hex_size": 8              // 网格点间距（可选）
}
```

客户端向服务端请求配置（服务端读取数据包），因此**多人服务器可自行添加演示**，客户端无需安装任何文件。

The client requests the config from the server (which reads the datapack), so **server admins can add their own demos** — clients need nothing installed.

### 1.3 配置格式 / Config format

```json
{
  "title": "咒术演示 Demo",    // 全局大标题（未播放时显示）
  "interval": 50,           // 全局每步间隔（tick），可被每步覆盖
  "clear_before": true,     // 每一步之前是否清空画布（网格，不清栈），默认 true
  "start_dir": "NORTH_EAST",// 全局图案起始朝向
  "steps": [
    { "type": "execute", "action": "hexcasting:get_caster" },
    { "type": "push", "pattern": "aqw", "push": "double:3.14", "title": "压入数字" },
    { "type": "clear", "title": "清空画布" },
    { "type": "peek", "index": 0 }
  ]
}
```

> 顶部标题为**动态**的：播放中显示**当前步骤标题**，未播放（暂停/未开始/播完）显示配置文件的**全局大标题**（`title`）。每步可用 `title` 自定义标题；未配置时默认取该步 `action` 的本地化名称（`hexcasting.action.<id>`），若翻译键不存在或没有 action 则不显示标题。

> The top title is **dynamic**: while playing it shows the **current step title**; when not playing (paused/not started/finished) it shows the config's **global title** (`title`). Each step can set a custom `title`; by default it uses the step's `action` localized name (`hexcasting.action.<id>`), and is hidden if no such translation key exists or the step has no `action`.

### 1.4 步骤类型 / Step types

| 类型 type | 作用 / Behavior |
|---|---|
| `execute`（默认） | 绘制图案（默认**执行蓝**）→ 把本地 CastingImage **上传服务端**运行（新 VM，**不碰玩家法杖栈**）→ 结果传回 → 本地栈 = 结果。失败（ERRORED）时图案染红 |
| `push` | 绘制图案 → 把**配置的自定义 Iota** 压入本地栈（纯本地，不理会括号/转义） |
| `push_in` | 绘制图案（可选）→ 把自定义 Iota **上传服务端按原版逻辑压入**：`escapeNext`（刚考察过）→ **转义**（进括号列表或压栈，消耗转义，染转义色）；括号内 → **进括号列表**；无转义无括号时压普通 Iota → 按原版丢 `MishapUnescapedValue`（要纯压栈请用 `push`） |
| `clear` | **清空画布（只清网格，不清栈）**，可作为序列中间的一步 |
| `peek` | **移除本地栈指定位置的 Iota**（栈顶为下标 0），支持单个或多个，越界忽略 |

> `push_in`（English): draws an optional pattern, then uploads the Iota to the server and pushes it via the **vanilla** logic: right after an `escape` it becomes **escaped** (into the paren list or onto the stack, consuming the escape, tinted the escape color); inside parentheses it joins the **paren list**; otherwise pushing an ordinary (non-pattern) Iota throws the vanilla `MishapUnescapedValue` — use `push` for a plain local stack push.

### 1.5 每步字段 / Per-step fields

| 字段 | 说明 / Description |
|---|---|
| `type` | 步骤类型（execute/push/push_in/clear/peek） |
| `pattern` | 图案**笔顺**（角度字符，如 `"aqw"`）——**网格显示**优先 |
| `action` | 已注册图案 id（如 `"hexcasting:get_caster"`）——**服务端执行**优先 |
| `start_dir` | 该步的起始朝向（如 `"EAST"`），默认取全局 |
| `origin` / `q`,`r` | 图案起始**网格坐标**（默认 `[-1, 2]`；`origin` 为 `[q, r]` 数组；有 `action` 时优先读 `pattern_vector` 默认表） |
| `color` | 图案颜色（`"#rrggbb"` / `"0xrrggbb"` / 十进制），默认执行蓝 |
| `interval` | 该步播放间隔（tick），默认取全局 |
| `push` | push 步骤的 Iota（见下文 §2） |
| `index` / `indices` | peek 步骤：移除的下标（`index`: 单个 / `indices`: `[a,b,c]` 多个） |

> **pattern 与 action 同时指定时**：网格绘制 `pattern` 的图案，服务端执行 `action` 的图案（显示与执行分离）。

> **When both `pattern` and `action` are given**: the grid draws `pattern`, while the server executes `action` (display and execution are decoupled).

### 1.6 默认起点表 / Default origin table (pattern_vector)

步骤未显式给出 `origin` 且带有 `action` 时，演示页会从数据包 `data/hexguide/pattern_vector/` 读取该 action 的**默认起点**。

When a step has an `action` but no explicit `origin`, the demo page reads the action's **default origin** from the datapack `data/hexguide/pattern_vector/`.

**规则 / Rules:**

- 目录下每个 `.json` 文件可记录**多个** action 的默认起点：
  ```json
  {
    "hexcasting:get_caster": { "origin": [-1, 2] },
    "hexcasting:raycast":     { "origin": [0, 0] }
  }
  ```
- 多个文件按**文件名顺序**加载并合并，后加载覆盖先加载的**同名 action**（例如 `aaa.json` 与 `zzz.json` 都定义了 `hexcasting:get_caster`，以 `zzz.json` 为准）。
- 文件可省略 `.json` 中任意条目；没有对应条目时回落到默认 `[-1, 2]`。

Each `.json` file under the directory may hold defaults for **multiple** actions. Files are loaded in **filename order** and merged — a later file overrides the same action defined by an earlier one (e.g. if both `aaa.json` and `zzz.json` define `hexcasting:get_caster`, `zzz.json` wins). Missing entries fall back to `[-1, 2]`.

**优先级 / Priority**：`origin` 显式 > `q`/`r` > `pattern_vector` 表 > 默认 `[-1, 2]`。

示例 / Example: `data/hexguide/pattern_vector/example.json`

### 1.7 从游戏里生成草稿 / Generating drafts in-game

有两种不用手写 JSON 的方式，生成的文件都落在 `<游戏目录>/hexguide/spellplays/`，
可直接拷进数据包 `data/<ns>/spellplays/`，再在演示页用 `"demo": "<ns>:<name>"` 引用。

**a) 图案 `demo`（栈上列表 → 草稿）**

栈顶压一个列表，列表里的每个 `PatternIota` 变成一步图案步骤，其它 iota 变成 `push` 步骤。
输出是完整的 `title` / `interval` / `clear_before` / `start_dir` / `steps[]` 结构（§1.3），不是片段。

- 列表**第一个元素是笔记 Iota** 时，用它的标题作为演示标题与文件名（笔记一键变动画）
- 否则文件名为 `demo_<时间戳>_<随机>`
- 聊天栏会打印可点击复制的路径提示

```
[列表] demo
```

**b) `/hexguide demoexport <索引>`（笔记 → 草稿）**

把第 `<索引>` 节笔记里**能识别**的引用抽出来生成草稿：

| 正文写法 | 解析结果 |
|---|---|
| `iota:<a85>` | 解码为对应 iota（列表会展开为多步） |
| `iota:<ns>:<name>.json` | 从服务端数据包 `data/<ns>/iotas/` 或游戏目录加载 |
| `pattern{<朝向>,<笔顺>}` / `pattern[<朝向>,<笔顺>]` | 该图案的一步 |

解不出的引用**跳过并计数**（提示「跳过 N 处」），不做完整 Markdown 解析。
这是务实取舍：笔记正文是给人读的，图案被引用即可演示，正文散文不参与解析。

Two in-game ways to produce a draft without hand-writing JSON; both write to
`<gameDir>/hexguide/spellplays/`, ready to copy into `data/<ns>/spellplays/`.

**a) Pattern `demo`** — push a list; each `PatternIota` becomes a pattern step and every other
iota becomes a `push` step. If the first element is a note Iota its title is used as the demo
title and filename. **b) `/hexguide demoexport <index>`** — extracts recognizable
`iota:<a85>` / `iota:<ns>:name.json>` / `pattern{<dir>,<sig>}` references from note section
`<index>`; unparseable references are skipped and counted.

---

## 2. Iota 文本内嵌（Inline 渲染）

HexGuide 集成了 [Inline](https://modrinth.com/mod/inline) 的字体钩子：聊天、书本中书写 `iota:...` 文本会**直接渲染为对应的 Iota 图形**（可悬停查看、点击复制引用）。

HexGuide hooks into [Inline](https://modrinth.com/mod/inline)'s font renderer: typing `iota:...` text in chat or books renders the corresponding **Iota graphic** inline (hover to inspect, click to copy the reference).

### 2.1 文本格式 / Text formats

| 形式 / Form | 示例 / Example | 结果 / Result |
|---|---|---|
| `iota:<a85>` | `iota:<压缩编码>` | 内联编码（NBT→deflate→Ascii85），短 |
| `iota:<ns>:<name>.json` | `iota:hexguide:vec0.json` | 从 `assets/<ns>/iotas/` 或游戏目录加载 |
| `iota:<name>.json` | `iota:vec0.json` | 短引用（默认 ns = hexguide） |
| `iota:[<元素>,...]` | `iota:[double:1, vec:{2,3,4}]` | ListIota（便携输入，可嵌套） |
| `double:<数字>` | `double:3.14` | DoubleIota（便携输入） |
| `vec:{a,b,c}` | `vec:{1,2,3}` | Vec3Iota（便携输入） |
| `pattern:<action id>` | `pattern:hexcasting:empty_list` | 已注册图案 → PatternIota |
| `pattern{<朝向>,<笔顺>}` | `pattern{NORTH_EAST,qqaeaae}` | 直接解析 → PatternIota（花括号：避免在 `iota:[...]` 列表内与外层 `[` 冲突） |
| `null` | `null` | NullIota |
| `""` | `""` | **不入栈任何东西** |

> 上述格式同样可用于演示配置的 `push` 字段（字符串形式）。`iota:[...]` 的元素可递归使用本表任意形式（含嵌套 `iota:[...]`）；逗号分割按括号深度进行，`vec:{...}` / `pattern{...}` 内部的逗号不会误分割。

> These formats also apply to the demo config's `push` field (string form). Elements of `iota:[...]` may recursively use any form above (including nested `iota:[...]`); splitting is bracket-depth aware, so commas inside `vec:{...}` / `pattern{...}` are safe.

### 2.2 自动保存 / Auto-save (OpTextCopy)

`OpTextCopy`（图案「复制」）施法后，会把 Iota **自动保存**为 JSON 文件：

```json
{ "nbt": "<SNBT 字符串>" }
```

保存位置 / Location: `<游戏目录 gameDir>/hexguide/iotas/<hash>.json`

同时聊天栏提示引用 `iota:<hash>.json`（点击复制）。可放进书本页面直接内嵌显示。

The game also prints a reference `iota:<hash>.json` (click to copy), usable directly in book pages.

### 2.3 iota 资源文件格式 / Iota resource file format

`assets/<ns>/iotas/<name>.json`（或游戏目录回退）两种写法均可：

```json
{ "nbt": "{iota_type:\"hexcasting:pattern\", data:{startDir:0, angles:[0,1,2]}}" }
```

或直接 SNBT JSON / or direct SNBT JSON:

```json
{ "iota_type": "hexcasting:double", "data": 3.14 }
```

---

## 3. 笔记分享串（剪贴板 / Clipboard share strings）

没有传文件权限的多人服里，可以把一节笔记压成一个字符串发在聊天里。

```
/hexguide export <索引> clipboard      # 生成分享串并复制到剪贴板
/hexguide import clipboard <HG1:...>   # 校验并导入
```

**格式 / Format**

```
HG1: + Ascii85( DEFLATE( JSON ) )
```

JSON 与 `/hexguide export` 写出的文件**完全同一套 schema**（`{"version":1,"pages":[{title,body,author,id,time},...]}`），
所以既能分享串、也能落文件，两者可以互相转换。Ascii85 字母表与 `iota:` 内联编码相同（`!`..`u`，`z` = 4 个零字节）。

The JSON is byte-for-byte the same schema as the file written by `/hexguide export`, so share strings and
files are interchangeable. The Ascii85 alphabet matches the `iota:` inline encoding.

**限制 / Limits**（全部在服务端校验，客户端不能直接写笔记库 / validated server-side）

| 项 | 上限 |
|---|---|
| 分享串长度 | 8192 字符（超出直接拒绝，不截断） |
| 解压后 JSON | 128 KiB（防压缩炸弹） |
| 页数 | 64 |
| 标题 / 正文 | 64 / 2048 字符 |
| 尝试频率 | 每玩家 10 次/分钟 |

**说明 / Notes**

- 服务端**不能**直接把内容放进你的剪贴板（Minecraft 只允许客户端点击 `COPY_TO_CLIPBOARD` 触发），
  所以 `/hexguide export ... clipboard` 会下发一个小包，由客户端写入剪贴板，并在聊天栏再留一条可点击复制的消息。
- 导入走 [`SecurityGuards`](common/src/main/kotlin/cn/xm1221/HexGuide/networking/SecurityGuards.kt) 的同一套权限与上限，
  并沿用 `/hexguide authority` 的单玩家开关。
- 编解码器 `NoteShareCodec` 是纯 JVM 代码（不引用 Minecraft 类），单测见
  `common/src/test/kotlin/cn/xm1221/HexGuide/api/notes/NoteShareCodecTest.kt`
  （往返、坏前缀、超长、压缩炸弹、a85 边界）。

---

## 4. 语言文件 / 界面补充

- 创造模式标签页：全部已注册图案的石板 / 卷轴（排除逐世界图案与卓越图案）
- 手册新增条目：搜索、施法网格（交互）、施法演示（动画）
- 探测透镜（Scry Sight）下查看石板/卷轴显示图案提示，按键打开咒术笔记对应页

- Creative tab: slates / scrolls for all registered non-per-world, non-great patterns
- New book entries: search, interactive spellcasting grid, animated demo
- Under Scry Sight, slates/scrolls show pattern hints; a key opens the matching book page

---

*本文档为备用说明，随功能迭代更新。 / This is a supplementary document; it follows feature development.*
