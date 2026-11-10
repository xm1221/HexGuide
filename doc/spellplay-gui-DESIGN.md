# 法术演示独立 GUI 设计

本文只定方案，不改播放行为。目标是把「法术演示」（页面类型 `hexguide:spellcast_demo`）从咒术笔记的书页里拆成一块可以单独打开的客户端界面，同时让现有书页继续能播同一份 `spellplays` JSON。

不在本方案里重写的东西：Hex Casting 的 `GuiSpellcasting`、服务端 `CastingVM`、`spellplays` JSON 格式、交互式练习网格（`hexguide:spellcasting`）。

---

## 1. 现状

### 1.1 两套网格，不要混成一件事

手册里有两块都画在 `GuiSpellcasting` 上、但职责不同的 UI：

| | 法术演示 | 交互式施法网格 |
|---|---|---|
| 页面类型 | `hexguide:spellcast_demo` | `hexguide:spellcasting` |
| 类 | `SpellcastDemoPage` | `SpellcastingPage` |
| 行为 | 按 JSON 自动画图案、改栈、可单步 | 玩家自己画；Cast/Write/Clr |
| 鼠标 | `mouseClicked` 直接返回 false，格子不能画 | `SpellcastingWidget` 把点击/拖拽转发给 `GuiSpellcasting` |
| 发包 | 自己发 `MsgBookExecDemoC2S` / `MsgBookPushIotaC2S` | 写模式拦截；施法模式走原版 `MsgNewSpellPatternC2S`，回包经 `MixinMsgNewSpellPatternS2C` 只路由到 `SpellcastingPage.ACTIVE` |

本方案的「独立 GUI」指**法术演示**。练习网格继续留在书里。两边共用的只是「把一个并未成为当前 Screen 的 `GuiSpellcasting` 嵌进来」这套 mixin，见 §3.2。

### 1.2 演示现在嵌在哪

注册入口只有客户端一处：`HexGuideClient.init()` 把 `hexguide:spellcast_demo` 放进 Patchouli `ClientBookRegistry`。没有物品、没有按键、没有 `/hexguide` 子命令能打开它。玩家只能翻开《咒术笔记》里带这个页面类型的条目。

书内引用（`assets/hexcasting/patchouli_books/thehexbook/...`）：

| 条目 | `demo` | 备注 |
|---|---|---|
| `entries/guide/logics.json` | `hexguide:logics` | |
| `entries/guide/spells/lights.json` | `hexguide:raycast` | |
| `entries/guide/spells/blocks.json` | `hexguide:torth` | |
| `entries/guide/mods/hexcassettes.json` | `hexguide:quines` | `flag: mod:hexcassettes` |

数据包 `data/hexguide/spellplays/` 里还有 **没有任何书页引用** 的配置：`text.json`、`quine.json`、`m_quine.json`、`intorth.json`。它们只有被某页的 `"demo"` 指到、并且服务端资源管理器读得到时才会播放。

生成草稿的路径也不经过 GUI：

- 图案 `hexguide:demo`（`OpDemoSave`）：栈顶列表 → `<gameDir>/hexguide/spellplays/<name>.json`
- `/hexguide demoexport <索引>`：从一节笔记正文里抽 `iota:` / `pattern{...}`，写到同一目录

草稿写在**执行该指令/法术的那一侧**的游戏目录（专用服务器上就是服务器目录）。客户端要看到它，今天必须再拷进数据包，并手写一条书页。独立 GUI 要补的就是这条缺口，而不是另做一套配置格式。

### 1.3 打开之后实际发生什么

`SpellcastDemoPage.onDisplayed`：

1. 把自己放进静态集合 `SpellcastDemoPage.ACTIVE`。
2. `new GuiSpellcasting(MAIN_HAND, 空列表, 空列表, null, 1)`，再用窗口 GUI 尺寸调用 `Screen.init`。这会播施法环境音，随即 `stop(CASTING_AMBIANCE)`。
3. 经 `BookSpellcastingAccess`（由 `MixinGuiSpellcasting` 实现）打开 `blockSending` 和 `writeMode`，设置 `hex_size`（书页 JSON，缺省 28；现有条目都写了 16）和 `coordsOffset`（画布中心的屏幕坐标）。
4. 发 `MsgBookLoadSpellplayC2S(ns, name)`。

服务端 `ServerMessageHandler`：

- 读 `data/<ns>/spellplays/<name>.json`（单文件，`ResourceLocation`，**不能枚举目录**）。
- `readPatternVector`：扫数据包 `data/hexguide/pattern_vector/*.json`，按路径排序合并，后写覆盖同名 action 的 `origin`。
- 回 `MsgBookLoadSpellplayS2C`。JSON 与 pattern vector 各自最多 65536 字符。

客户端 `ClientMessageHandler` 在 `SpellcastDemoPage.ACTIVE` 里找 `matches(ns, name)` 的页，调用 `onSpellplayLoaded`。解析全部写在页面类里（`parseSteps`、`resolvePushIota`、`resolveIotaString`、`splitTopLevel`、颜色/标题继承、origin 优先级）。

播放在 `render()` 里用 `partialTick` 累加，不是客户端 tick：

| 步骤 | 本地 | 网络 |
|---|---|---|
| `execute`（默认） | `demoAddPattern` 画**显示**图案（`pattern` 优先） | `MsgBookExecDemoC2S` 带**执行**图案的签名和方向，外加本地 `CastingImage` NBT |
| `push` | 可选画图案，再 `demoPushIota` | 无 |
| `push_in` | 可选画图案 | `MsgBookPushIotaC2S`，服务端按括号/转义处理 |
| `peek` | 可选画图案，再按栈顶为 0 的下标删除 | 无 |
| `clear` | `demoClearCanvas`（清网格，不清栈） | 无 |

`execute` / `push_in` 的服务端路径相同：`DemoCastEnv`（`StaffCastEnv` 子类，不写玩家法杖栈、不发螺旋、`extractMediaEnvironment` 恒为 0）+ 新的 `CastingVM` + `queueExecuteAndWrapIota`。回包 `MsgBookExecDemoS2C(image, resolutionType)` **没有关联 id**，客户端对 `ACTIVE` 里每一页调用 `onExecResult`。`ERRORED` 染红并冻结；`ESCAPED` 用 `escaped_color`（默认黄）。

`SecurityGuards` 的注释写明了：故意**不做**「该玩家正在看哪一页」的会话状态。现有限额是 `exec_demo` 12 次/秒、`push_iota` 16 次/秒、`load_spellplay` 8 次/秒，外加签名字符集、iota 类型白名单和 NBT 体积。这挡不住合法图案的任意组合。README 已说明演示会真的改世界。

### 1.4 它依赖什么，不依赖什么

依赖（缺一不可）：

- **Hex Casting 0.11.4**：`GuiSpellcasting`、`HexPattern` / `HexDir` / `HexCoord`、`HexActions.REGISTRY`、`CastingImage`、`CastingVM`、`StaffCastEnv`、`HexSounds`、`IotaType`。
- **Patchouli**：只作为宿主。页面继承 `BookPage`，按钮经 `GuiBookEntry.addWidget` 进 Screen 子组件，画布宽被写成 `GuiBook.PAGE_WIDTH`（116）。三个按钮被算成 `2+36+2+36+2+36 = 114`，就是为了塞进这一页。
- **Architectury 网络**：`HexGuideNetworking` 按 sealed companion 注册包。
- **一个服务端**（单人集成服务器或专用服）：`execute` / `push_in` 和数据包读取都在服务端。纯主菜单没有世界，打不开这条链路。

软依赖：

- **Inline**：`iota:<a85>` 和 `iota:<ns>:<file>.json` 走 `IotaInlineCodec`。`double:` / `vec:` / `pattern:` / `pattern{...}` / `null` / `iota:[...]` 不需要 Inline。未装 Inline 时演示页仍注册。

明确不存在：

- 仓库与 `gradle/libs.versions.toml` 里都没有 **HexCLI**、**JEI**、**EMI**。
- Mod Menu（`FabricHexGuideModMenu`）只打开 Cloth Config，而客户端配置目前只有一个占位布尔值。
- `doc/` 的 hexdoc 会渲染网页版手册，**不会**播放演示。
- `MixinMsgNewSpellPatternS2C` 与演示无关，只服务练习网格。`MixinGuiBook` 只把滚轮交给笔记目录。

### 1.5 书页宿主带来的具体限制

这些是独立 GUI 要解开的耦合，也是迁移时最容易回归的点：

1. **画布约 116×106**（`CANVAS_Y = 22`，`BTN_Y = 138`，`CANVAS_H = BTN_Y - CANVAS_Y - 10`）。栈框 110×56 叠在画布左上角：先 scissor 画整张 `GuiSpellcasting`，再把姿态平移 `stx - 10, sty - 10` 画第二遍，只留原版栈框所在的 (10,10) 那一块。图案和栈在抢同一小块像素，所以现有条目把 `hex_size` 降到 16。
2. **渲染要先抵消** `drawPage` 的 `translate(left, top)` 和书的 `bookLeft/bookTop`。这段坐标只对书页有意义。
3. **`coordsOffset` 在 `onDisplayed` 里算一次**，`spellcasting` 实例被缓存。书页位置或窗口尺寸变了，网格中心会偏。独立屏幕至少要在 `resize` 时重算。
4. **结果广播**。两页同时 `ACTIVE`（或将来屏幕和书页同时在）会把同一次执行结果写进两边。
5. **配置只能按 id 拉**，不能列出服务器上有哪些演示，也不能直接播游戏目录里的草稿。

---

## 2. 「独立 GUI」在这个仓库里指什么

指：**HexGuide 客户端里的一个 `Screen`（`SpellplayScreen`）**，不经过 `GuiBookEntry` 就能打开、列出并播放 `ns:name` 演示。书页改成同一播放器的薄宿主，JSON 和网络包保持原样。

这是本仓库里已经有的做法。笔记编辑器 `NoteEditorScreen` 是独立 `Screen`，书只负责展示；演示应当同样「书是入口之一，不是容器」。

### 不选的方案

**外部程序对接 HexCLI。** 本仓库没有 HexCLI，演示执行也不是一个可替换的解释器。`execute` 走的是当前世界里的 `CastingVM`：附属模组注册的 action、方块/实体副作用、`pattern_vector` 数据包、iota 反序列化都在这条链上。HexCLI 即使能画角度字符串，也复现不了 `push_in` 的括号/转义和 `DemoCastEnv` 的「真执行、假媒质」。再做一套远程协议等于把 `SecurityGuards` 已经小心收窄的 C2S 面再开一个口。独立 GUI 仍然是游戏内界面。

**可拖出的 HUD 浮层。** 演示需要标题、步骤计数、播放/单步/重播，以及一块比 116px 宽的格子。浮在书上要么继续受书页裁剪，要么盖住书并且和 Patchouli 抢滚轮（`MixinGuiBook` 默认把滚轮当翻页）。`GuiSpellcasting` 也不是为「当前 Screen 仍是书」设计的，现有代码已经在用 scissor 裁它。浮层不解决宿主问题。

**拆成另一个仅客户端模组。** mixin、`DemoCastEnv` 和包都在 `common`。再拆一个模组会复制 `GuiSpellcasting` 的注入面和 C2S 校验，专用服还得装两份。要分开的是 **Patchouli 页面生命周期**，不是模组边界。Fabric/Forge 继续走现有 Architectury 工程。

**Mod Menu 里的一页。** Mod Menu 在这个工程里是配置屏幕。演示是世界内的内容，依赖服务端数据包和 `CastingVM`，放进模组列表没有玩家路径，Forge 侧也没有对等入口。

### 打开方式（按这个顺序做）

1. 服务端指令 `/hexguide demo open <ns:name>`，下发一个很小的 S2C，客户端 `setScreen`。专用服上指令本来就在服务端，和现有 `/hexguide demoexport` 一致；不要做只能在集成服务器上用的客户端指令。
2. 客户端按键，默认不要占用探知用的 `R`（`HexGuideKeybinds.OPEN_HEXBOOK`）。按键打开目录，而不是写死某一个演示。
3. 书页上的「弹出」放在目录和屏幕都能用之后。`setScreen` 会 `removed()` 掉 `GuiBookEntry`，关屏幕时把同一个书实例设回去不一定还能翻到原页。MVP 不承诺「从书弹出再回到原页」。

单人游戏可用，因为集成服务器会处理现有 C2S。主菜单不可用，和现在一样。

---

## 3. 结构

### 3.1 模块

```
common/.../demo/                         已有 DemoGenerator，继续放与 Screen 无关的模型
  Spellplay.kt                           解析结果：title、interval、clearBefore、startDir、escapedColor、steps
  SpellplayParser.kt                     从 SpellcastDemoPage 搬出的 JSON / iota 字符串解析
  SpellplayPlayer.kt                     播放状态机，不引用 Screen、不引用 Patchouli

common/.../client/spellplay/             仅客户端
  SpellGrid.kt                           持有 GuiSpellcasting，实现画布矩形上的绘制与 demo* 调用
  SpellplayScreen.kt                     目录 + 播放器 + 按钮
  SpellplaySessions.kt                   当前唯一会话，取代页面上的 ACTIVE 广播

common/.../patchouli/SpellcastDemoPage   变薄：创建会话、把书页矩形交给 SpellGrid、转发按钮
```

练习网格的 `SpellcastingPage` / `SpellcastingWidget` / `MixinMsgNewSpellPatternS2C` 这次不动。`BookSpellcastingAccess` 的方法名先留着：它们是 mixin 打进 Hex Casting 类的接口，改名要连同 `MixinGuiSpellcasting` 一起改，没有行为收益。注释里写明它是「嵌入网格」的 API，不只服务书。

`SpellcastDemoPage` 之外的新代码不要 import `vazkii.patchouli`。这样「不打开手册」时，播放器、网格和网络仍然完整。书页适配器是唯一知道 `GuiBook.PAGE_WIDTH` 的地方。

### 3.2 数据流

```
spellplays JSON（数据包 或 客户端本地草稿）
        │
        ▼
SpellplayParser  →  Spellplay
        │
        ▼
SpellplayPlayer.advance / stepOnce / restart
        │
        ├─ 本地 ─► SpellGrid
        │           demoAddPattern / demoPushIota / demoPeek / demoClearCanvas
        │
        └─ execute、push_in ─► 现有 C2S
                    DemoCastEnv + CastingVM
                    MsgBookExecDemoS2C
                          │
                          ▼
                    SpellplaySessions.current.onExecResult
                    （只交给这一份播放器，不再遍历书页集合）
```

显示图案和执行图案的分裂保持现状：格子用 `resolveDisplayPattern`（签名优先），服务端用 `resolveExecPattern`（action 优先）。origin 优先级不变：步骤 `origin` > `q`/`r` > `pattern_vector` > `[-1, 2]`。

`SpellGrid` 做的事就是今天页面 `render` 后半段，但矩形由宿主传入：

- 宿主给出画布矩形、栈矩形、`hexSize`、网格中心。
- 网格点、scissor、深色边框、第二遍只露栈框，都留在 `SpellGrid`。书页继续传现在的 116×106 和叠在画布上的栈框，像素级行为才能不变。独立屏幕把栈放到画布旁边，并使用更大的 `hexSize`（建议 28，与页面缺省一致），这是屏幕自己的布局，不回写书页 JSON。
- 继续 `new GuiSpellcasting` + `init(窗口尺寸)` + 停环境音。不要把 `GuiSpellcasting` 设成 `Minecraft.screen`。原版 `recvServerUpdate` 在栈清空时会 `setScreen(null)`；演示必须继续走 `demoSetImageNbt`，不能走那条回包。

播放状态（`playing`、`nextStep`、`animTicks`、`currentWait`、`errored`、`lastPatternIdx`、`lastStepCustomColor`）从页面挪进 `SpellplayPlayer`。`render` 里调用 `advance(pticks)` 的时序保持不变，避免「改成 tick 事件之后步骤变快」。

草图（不是要提交的源码）：

```kotlin
interface SpellplayHost {
    fun onSpellplayReady(play: Spellplay)
    fun onExecResult(image: CompoundTag, resolutionType: String)
}

class SpellplaySession(
    val ref: ResourceLocation,
    val grid: SpellGrid,
    val player: SpellplayPlayer,
) : SpellplayHost

object SpellplaySessions {
    var current: SpellplaySession? = null
        private set
    fun activate(session: SpellplaySession) { current = session }
    fun deactivate(session: SpellplaySession) {
        if (current === session) current = null
    }
}
```

书页 `onDisplayed` 里 `activate`，`onHidden` 里 `deactivate`。屏幕 `init` / `removed` 同样做。同一时刻只有一个 `current`，所以现有的无 id 回包仍然正确，**MVP 不改包格式**。

### 3.3 不打开手册时怎么用

屏幕启动时：

1. 发一次已有的 `MsgBookLoadSpellplayC2S` 拉玩家点的那个 id（指令打开时 id 已知）。
2. 目录模式再要一份**新的**列表包（见 §4 阶段 2）：服务端用和 `readPatternVector` 一样的 pack 遍历，收集 `spellplays/*.json` 的 `ns:path`。只返回 id 和可选的 `title`，不把每个文件的全文塞进一个包（64KiB 上限）。
3. 客户端另外扫描**本机** `<gameDir>/<ns>/spellplays/*.json`，标成「本地草稿」。`push` / `peek` / `clear` 能离线播；`execute` / `push_in` 仍发 C2S，服务端不需要有这份文件，因为它收到的是签名和 `CastingImage`，不是文件名。`pattern_vector` 仍以服务端合并结果为准；本地文件播放前照样请求一次，拿 origin 表。专用服上 `OpDemoSave` 写在服务器磁盘，客户端目录扫不到，这要写在界面说明里，而不是假装两边是同一个文件夹。

屏幕不读取书的条目 JSON，因此 `text`、`quine`、`m_quine`、`intorth` 以及管理员后加的数据包演示都会出现。`hexcassettes` 那条书页的 `flag` 是 Patchouli 的显示条件；独立目录不做这条过滤，没装附属时播 `quines` 只会在对应 action 缺失时走现有的失败染红。

### 3.4 生命周期

| 事件 | 行为 |
|---|---|
| 打开屏幕或翻到书页 | `activate`；若网格还没有，创建并 `init`；请求配置 |
| 配置到达 | 只交给 `matches` 的那次请求。屏幕和书页不要共用一个「谁都写 steps」的静态字段 |
| 播放中关闭 | `deactivate`，停环境音（若仍在播）。在途的 S2C 到达时 `current == null`，直接丢掉 |
| 窗口缩放 | 屏幕 `resize` 时重算 `coordsOffset` 和 scissor。书页 MVP 可以仍在 `onDisplayed` 算一次，与今天相同 |
| 失败冻结 | 状态留在 `SpellplayPlayer`。Play / Step / Restart 才清除 `errored`，不要在下一次成功回包里清（现有注释就是为了防这个） |

---

## 4. 迁移

### 4.1 阶段

**阶段 0（本文）**  
只加这份设计。不改包、不改页面、不改 mixin。

**阶段 1：抽出播放器，书页外观不变**  
把解析和状态机搬出 `SpellcastDemoPage`，页面改成调用它们。`SpellGrid` 吃进现有的画布常量，书上的按钮坐标、标题、`hexguide.demo.step_counter` / `step_failed` 都不动。  
回包从 `for (page in SpellcastDemoPage.ACTIVE)` 改成 `SpellplaySessions.current`。书页是唯一调用 `activate` 的宿主，行为与「集合里通常只有当前页」一致。  
这一阶段没有新屏幕，用现有四条书页做回归。

**阶段 2：屏幕 + 指令 + 按键**  
`SpellplayScreen`、`MsgOpenSpellplayS2C`（只带 `ns` 和 `name`）、`/hexguide demo open`。按键打开目录。  
新文案走 lang 键（`hexguide.demo.screen.*`），中英都写上，不要硬编码中文。  
列表包 `MsgListSpellplaysC2S/S2C` 可以跟屏幕同一批，也可以紧跟着做。没有列表时，指令打开单个 id 已经比书页多一个入口。

**阶段 3：书页弹出、速度、进度条**  
确认 Patchouli 屏幕能恢复之后，再在书页加按钮。速度滑杆和步骤拖动只放在独立屏幕，避免把 116px 的按钮行再撑破。

不要在这些阶段里把 `SpellcastingPage` 的 Cast/Write 并进演示屏幕。那是另一条输入路径（`MixinMsgNewSpellPatternS2C`、法杖栈同步 `MsgBookSyncStackC2S`），混进来会让「演示不碰法杖」的不变量变模糊。

### 4.2 复用与重写

| 复用 | 重写或搬迁 |
|---|---|
| `MixinGuiSpellcasting` 的全部 demo 钩子 | 页面里的解析函数 → `SpellplayParser` |
| `DemoCastEnv`、三个现有演示包、`SecurityGuards` 限额 | 播放字段与 `advance` / `stepOnce` / `restart` → `SpellplayPlayer` |
| `DemoGenerator` 的 JSON 形状（`SPELLPLAYS.md` §1.3） | 画布/栈的 scissor 与二次渲染 → `SpellGrid` |
| `SpellcastingButtonWidget` 或普通 `Button`（屏幕用后者即可） | `ACTIVE` 集合的广播 |
| 书页上的布局常量，原样留在书页适配器里 | 独立屏幕的布局（新的） |

`resolveIotaString` 依赖 `IotaType`、`HexActions`、`TagParser`、`IotaInlineCodec`，**不是**像 `NoteShareCodec` 那样的纯 JVM 代码。单测只覆盖能离开 Minecraft 的部分：`splitTopLevel`（已有注释里的嵌套例子）、颜色/标题继承、origin 优先级、缺省 `type=execute`。iota 字符串解析留在游戏内手工看 `text.json`，不要为了单测去起客户端。

### 4.3 风险

1. **书页回归。** 抽出时最容易改掉的是：按钮仍由 `parent.addWidget(..., pageNum)` 注册、标签仍走 `I18n`、失败时不自动恢复播放、`clear_before` 只清网格。阶段 1 的验收就是这四条书页，而不是新屏幕。
2. **双宿主吃同一条回包。** 用单个 `current` 而不是「书页集合 + 屏幕集合」。打开屏幕时书页应已经 `onHidden`（因为书不再是当前 Screen）。若以后做弹出并保留书实例，必须先 `deactivate` 书页会话。
3. **`GuiSpellcasting.init` 的副作用。** 环境音已经在停。不要把按键转发给它，否则 Esc 可能被内层屏幕吃掉。演示格子本来就不接收鼠标。
4. **mixin 签名。** 颜色替换打在 `render` / `m_88315_` / `method_25394`。新屏幕只要继续调用 `spellcasting.render`，就不要新增注入点。Hex Casting 升级时这里仍是最脆的地方，独立 GUI 没有把它变得更脆，也没有把它去掉。
5. **更容易误触世界改动。** 目录让 `raycast`、方块类演示离书更近。MVP 不要加「输入任意签名并执行」的自由施法框；只播放已有 JSON。安全边界仍是 README 里那一段，不在 GUI 里放宽 `SecurityGuards`。
6. **64KiB。** 单份 spellplay 或合并后的 pattern vector 超过 `readUtf(65536)` 会断。列表包不要复用这个字段去塞全部正文。
7. **本地草稿的位置。** 见 §3.3。界面上把「本机草稿」和「服务器数据包」分成两列，避免玩家以为 `demo` 图案存盘后客户端一定看得到。
8. **`coordsOffset` 过期。** 阶段 1 不要「顺便」改书页的计算时机。阶段 2 的屏幕在 `resize` 里重算。

---

## 5. 怎么交付

### MVP 与以后

MVP 是阶段 1 + 阶段 2 的指令打开：

- 书页看起来和现在一样，四条现有演示都能播放、单步、重播、失败冻结。
- `/hexguide demo open hexguide:text` 打开一块比书页大的屏幕，播放同一解析器。
- 没有目录、没有弹出按钮、没有速度滑杆也可以合。目录是屏幕「能发现未挂到书上的 JSON」的那一半，建议紧跟 MVP，不要再拖一整个版本。

不算 MVP、也不要挡合并的东西：进度条、复制当前图案、从书页弹出并返回原页、练习网格并入、HexCLI、网页 hexdoc 里的动画。

### 建议的 PR 形状

| PR | 分支内容 | 基线 |
|---|---|---|
| A（当前） | 只有本文件 | `main` |
| B | 阶段 1 抽取。diff 的主体应是 `SpellcastDemoPage` 变短，而不是新行为 | `main`（设计合并与否都可以，B 不依赖 A 的文件才能编译） |
| C | `SpellplayScreen`、打开包、指令、按键、lang | B 的分支 |

B 和 C 不要跟笔记、探知、Inline 的改动捆在一起。C 若目录列表包很大，再拆成 C2。实现分支沿用 `cursor/<简述>-3ade` 即可，例如 `cursor/spellplay-screen-3ade`。

阶段 1 的手工检查（没有自动化客户端测试）：

- `logics`：含 `push`、`peek`、转义色，步骤多，最容易看出时序和栈。
- `raycast`：连续 `execute`，确认服务端回包仍更新栈且不关屏幕。
- `torth`、`quines`：确认抽取后书页 `hex_size: 16` 仍然生效。
- 故意播一个会 `ERRORED` 的步（或临时改本地 JSON）：红图案留下，播放停下，Step 能继续。
- 练习网格页（`stack` / `foreach`）仍能画、能切换 Cast。这次不改它，但 mixin 是共享的，要看一眼。

阶段 2 再查：指令在单人与专用服都能让**客户端**打开屏幕；关闭屏幕后书页演示仍能播；快速重开不会把上一次的回包写进新会话（`deactivate` 之后丢包）。
