# HexGuide 现状与迁入路线图（v0.0.2）

> 调研日期：2026-10-29（Asia/Shanghai）。**先不改内容**，等小咩定方向后再动手册/任务文案。
> 目标：取代大包里的 FTB Quests + KubeJS `guide.json` / `great_guide.json`，成为「小咩的咒法学大包」核心引导。

## 1. 模组现状

| 项 | 值 |
|---|---|
| 版本 | `0.0.2`（`gradle.properties`，保持不升） |
| 加载器 | Architectury · MC **1.20.1** · Hex Casting **0.11.3** |
| 发版 | 从未正式 Release；CI 长期红 |
| 本地构建 | `./gradlew :fabric:build` **已通过** → `hexguide-fabric-0.0.2-SNAPSHOT.jar` |

### 1.1 已有功能（代码 / Patchouli）

- **手册增强**：搜索页、内嵌施法网格、spellplay 自动演示（`data/hexguide/spellplays/*.json`）
- **教程章节（guide）**：栈 / Iota / 动作 / 元运行 / 逻辑 / 转义 / foreach / continuation / tips；附属简介（Hexal、Hexcassettes、HexFlow、HexOP、MoreIotas）；示例法术（allay / arrow / blocks / boom / far_away / lights / media）
- **玩家笔记**：紫水晶笔、笔记残页、NoteIota、`note/*` 图案与 `/hexguide` 指令
- **Inline**：`iota:...` 渲染、颜色/换行后缀
- **其它**：探知透镜提示、创造标签页、Connector 下 `hexcasting:action` tag 修复

分类：`guide`（含 `guide/mods`、`guide/spells`）、`notes`；另有 `basics/great`、`casting/hexguide` 条目。

### 1.2 已知问题 / 损坏点

1. **CI**
   - `gradlew` 在 git 中为非可执行（`100644`）→ Actions 上 `./gradlew` **exit 126**
   - `distributionUrl` 曾指向腾讯云镜像，GHA 可能拉不到 → 已改回 `services.gradle.org`
   - `build-hexdoc`：`uv sync --frozen` 但仓库缺 `uv.lock` → 已生成并提交
2. **内容**
   - 英文翻译不完整（README 已提示）；大量文案在 `zh_cn.flatten.json5`
   - `entries/notes/notes.json` 页数极大（256），疑似占位/生成物，需确认是否应 datagen
   - 与大包旧引导（任务章 + guide.json）**尚未对接**，玩家装上 HexGuide 看不到原 8 章进度线
3. **依赖版本**
   - 工程内 Fabric Loader / Inline 等与大包钉死版本不完全一致（开发用 0.14.25 loader 坐标 vs 大包 0.18.0 运行时）——运行时以大包为准做冒烟即可

## 2. 旧大包引导资产（v1.6.4）

路径副本：`/workspace/out/bigpack-packwiz/legacy-guide/`（不打进整合包）。

### 2.1 FTB Quests 八章

| 章 | 主题（title） | 大致内容方向 |
|---|---|---|
| 祝君一路顺风 | 开局须知 | 游玩须知 / 在一切之前（短章） |
| 读书使人强大 | 媒质与笔记 | 前人笔记、媒质！媒质！媒质！ |
| 先人的遗产 | 卓越法术 | 先来后到、秩序分明；大量卓越相关任务 |
| 信息存储 | 读写/结绳 | 第一次输入、结绳记事 |
| 灵魂的监牢 | 意识/睿思 | 听起来像黑魔法、意识的囚笼 |
| 小心！再小心！ | 编辑与珠宝 | 进阶珠宝匠锤、更精确的编辑 |
| 崩坏的前奏 | 破坏与光 | 简单破坏、要有光 |
| 聚集的祈愿 | 收集 | 收集意识等收束 |

任务正文在 `ftbquests/quests/chapters/*.snbt`（描述、依赖、奖励表齐全）。

### 2.2 KubeJS `guide.json` / `great_guide.json`

- **`guide.json`**：约 **1051** 条；键为图案 stroke（如 `qaq`），值为 `[中文说明, "相关章节", "patterns/..."]`
- **`great_guide.json`**：卓越/附属卓越法术 id → 同上结构的中文说明（含 complexhex / ephemera / hexal / hexcasting 等）

这是「画了什么图案 → 弹出一句人话」的轻量图鉴，和 FTB 任务线互补。

## 3. 迁入 HexGuide 的建议路线（待小咩拍板）

### 方案 A — Patchouli 章节映射（推荐作主线）

把 8 章做成 `thehexbook` 下新 category（例如 `guide/campaign` 或顶层 `legacy`），每章若干 entry：

1. 从 snbt 抽 `title` / `description` / 任务依赖图 → 生成 entry 骨架（可脚本化）
2. 奖励与物品检测改为「读手册 + 施法演示 / 合成检查」叙事，不必 1:1 复刻 FTB 完成条件
3. 开篇章「祝君一路顺风」做落地页，链到 HexGuide 已有栈/Iota 教程

### 方案 B — guide.json 图案注释

1. 将 stroke→说明 做成 datapack 或 HexGuide 内部 registry，在**搜索页 / 石板探知 / 画完图案后**显示
2. `great_guide.json` 并入卓越条目或 `basics/great` 扩展页
3. 与现有 `spellplays` 演示互相链接（有演示的优先播动画，无则只显示原文）

### 方案 C — 进度（可选，后期）

若仍要「完成感」：用 Advancement / 自定义 progress（HexGuide 已有笔记与指令基建）代替 FTB Quests，**不要**重新引入 KubeJS。

### 明确不做（本阶段）

- 不改写任务原文、不删 HexGuide 现有教程
- 不升 `modVersion`、不发 Modrinth/Curse 版
- 不把 FTB Quests 加回大包

## 4. 建议落地顺序

1. CI 绿灯（本分支 `fix-ci`）+ Fabric jar 冒烟  
2. 小咩选定 A/B/C 组合与章节命名  
3. 脚本：snbt / guide.json → Patchouli JSON + lang 草稿（另开内容分支）  
4. 大包 packwiz 线把 HexGuide 以 Modrinth 或 overrides jar 钉进整合包  

## 5. 构建产物

- `/workspace/out/hexguide-fabric/hexguide-fabric-0.0.2-SNAPSHOT.jar`
- 本文：`/workspace/out/hexguide-fabric/ROADMAP.md`（亦提交到仓库 `docs/ROADMAP.md`）
