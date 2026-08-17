# 手册渲染验证方案（思路记录）

> 状态：思路记录。验证工具尚未实现，后续计划做成 doc 管线插件并入构建/CI。

## 背景

编写/修改《咒术笔记》（Patchouli）条目时，作者本人无法直接查看游戏内书页的实际渲染效果（需游戏环境 + 截图），而 Patchouli 的标记语法、lang key 引用、`$(l:...)` 链接、`iota:xxx` 内联一旦出错，书页会显示红字、键名或空白。本文件记录一套**逐层递进**的验证思路，用于在改动落地前尽早发现问题。

## 涉及内容

- 书内容：`common/src/main/resources/assets/hexcasting/patchouli_books/thehexbook/en_us/`
  - `entries/**`、`categories/**`、`templates/**`
- 文字：`common/src/main/resources/assets/hexguide/lang/{zh_cn,en_us}.flatten.json5`（pkjson5 编译为 json），全部走 lang key 引用
- 内联 Iota：`iota:xxx`（资源文件 `assets/hexguide/iotas/`、`iota:<ns>:<name>.json`、Ascii85 压缩串）
- 可用离线参照（本地已克隆）：HexMod / HexOverpowered / HexFlow / Hexal 等仓库，可解析其 book 条目 id 与页面 anchor 用于链接校验

## 分层方案

### A 层：静态校验（脚本 + CI，最优先）

一个可独立运行的校验器（Python 脚本或独立插件），检查：

1. **lang key 完整性**：每个 entry/category/page 引用的 key 在 `zh_cn` 与 `en_us` 中都存在；两侧 key 集合对齐（防漏译/键名显示）。
2. **Patchouli 标记校验**：`$(...)` 成对闭合；格式码在已知白名单内（`$(br)` `$(br2)` `$(li)` `$(l:...)` `$(action).../$` `$(thing)` `$(media)` `$(o)` `$(t:...)` `$(k:...)` 等）；`$()` 闭合正确。
3. **iota 引用存在性**：`iota:xxx` 引用的资源文件确实存在（`assets/hexguide/iotas/` 或数据包内）。
4. **链接校验**：`$(l:...)` 的目标条目 id 存在——`hexcasting:` 本体条目对照本地 HexMod 仓库的 book 文件核对；addon 锚点（`hexoverpowered:`、`hexflow:`、`hexal:` 等）从本地各 addon 仓库的 `entries/**` JSON 提取 anchor 白名单核对。
5. **基线全检**：对全部现有页面跑一遍，输出报告；接入 CI 后失败即报。

### B 层：hexdoc 网页预览（已有管线，零新增依赖）

- `doc/` 已含 hexdoc 全套：根目录配好 `.env` 后 `uv sync` + `hexdoc serve`（或 watch 模式 `nodemon --config doc/nodemon.json`）。
- 网页可直观检查：排版、换行、Iota 内联的近似位置、链接文字。
- 已知局限：web 构建的资源目录不含 hexop/hexflow 等 addon 的书文件，指向它们的链接在预览中显示为未链接；其正确性由 A 层裁决（游戏中实际可用）。

### C 层：游戏内截图回环（发布前最终校样）

- `./gradlew :fabric:runClient` 打开对应书页截图。
- 用视觉工具核验：换行截断、元素重叠、标题遮挡、Iota 大小/位置、字体观感。
- 这是唯一能查"肉眼观感"的手段，仅在 A/B 层通过后使用。

## 后续落地计划

- [ ] 把 A 层校验实现为 doc 管线插件（`doc/src/hexdoc_hexguide` 下的 hexdoc 插件），并入 `hexdoc build` 与 CI，出错即失败。
- [ ] 插件命名与实现形态（脚本 / hexdoc 插件 / Gradle 任务）落地时再定，本文件先记录思路。
