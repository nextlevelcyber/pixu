# AI Coding 开发规范

适用范围：研发团队所有项目。本文档是给人读的 AI coding 开发规范。

---

## 1. 工具选择与能力基线

团队不强制统一 coding 工具。开发者可以按个人习惯选择 **Claude Code**、**Codex**、**Cursor** 或其他等价工具，但必须启用团队批准的 `superpowers` plugin；在 dev、review、refactor 阶段还必须参考并遵循 `karpathy-guidelines`。

**统一约束的是能力，不是工具：**
- 每个项目必须提供当前工具可读取的 agent 指引文件，例如 `AGENTS.md`、`CLAUDE.md` 或 `.cursor/rules/*.mdc`
- agent 指引必须包含项目技术栈、构建/测试命令、代码边界和工具使用规则
- plugin、skill、MCP server 必须使用团队批准清单，不允许个人随意引入到团队项目
- 不同工具如果能力名称不同，以实际行为对齐：plan、dev、review、debug、test

**当前基线能力：**
- `plan`：拆解需求、识别风险、列出实现步骤和测试策略
- `dev`：按计划修改代码，保持改动范围最小
- `review`：以代码审查视角检查 bug、回归、边界条件和测试缺口
- `debug`：复现问题、定位根因、验证修复
- `test`：运行单元测试、集成测试、lint 或必要的手工验证

**已批准能力清单：**

| 类型 | 名称 | 适用工具 | 用途 |
|------|------|----------|------|
| plugin | `superpowers` | Claude Code / Codex / Cursor | 必装；提供 brainstorming、planning、execution、review、debug、test 等标准工作流 |
| skill | `karpathy-guidelines` | Claude Code / Cursor / 通用文本准则 | 必用；dev、review、refactor 阶段遵循“先思考、简单优先、外科手术式修改、目标驱动执行” |
| plugin / skill | 项目级补充 | 按项目声明 | 可选；必须经过评审，不能替代 `superpowers` 固定流程或 `karpathy-guidelines` 实现准则 |
| MCP | 项目级声明 | 通用 | MCP server 必须在项目 agent 指引中列出名称、用途、权限和数据边界 |

工具侧安装方式由各项目在自己的 agent 指引中说明。例如 Claude Code、Codex、Cursor 都应优先使用 `superpowers` 官方推荐的 plugin 安装方式；`karpathy-guidelines` 必须通过 plugin、Cursor rule 或 agent 指引文件接入；MCP 配置只作为项目补充能力。

---

## 2. 工具接入方式

`ai-coding-standard.md` 是团队 AI coding 准则的 source of truth。各项目可以用不同工具接入，但必须保证 agent 在开始编码前能读取并遵守本文档。

**Claude Code：**

在项目根目录创建 `CLAUDE.md`，用 import 方式引用本规范：

```markdown
# Project Instructions

请先阅读并遵守 @ai-coding-standard.md。
```

**Codex / Code Agent：**

在项目根目录创建 `AGENTS.md`，明确要求 agent 读取并遵守本规范：

```markdown
# Agent Instructions

Before making code changes, read and follow `ai-coding-standard.md`.
```

如果项目已有 `AGENTS.md`，将上述内容合并进去，不要创建重复准则文件。

**Cursor：**

推荐使用 Project Rules。在项目中创建 `.cursor/rules/ai-coding-standard.mdc`：

```markdown
---
description: Team AI coding standard
globs:
alwaysApply: true
---

Follow `ai-coding-standard.md` before planning, editing, reviewing, debugging, or testing code.
```

如果团队选择使用 Cursor 的 `AGENTS.md` 支持，也可以复用 Codex 的写法。`.cursorrules` 属于 legacy 方案，不建议新项目继续使用。

**Superpowers plugin：**

所有项目必须在所选工具中启用 `superpowers`。安装命令以官方仓库为准，常用方式如下：

```bash
# Claude Code
/plugin install superpowers@claude-plugins-official

# Codex CLI
/plugins
# 搜索 superpowers 并选择 Install Plugin

# Cursor Agent chat
/add-plugin superpowers
```

**Karpathy Guidelines：**

所有项目必须让 agent 在 dev、review、refactor 阶段读取并遵守 `karpathy-guidelines`。常用方式如下：

```bash
# Claude Code
/plugin marketplace add forrestchang/andrej-karpathy-skills
/plugin install andrej-karpathy-skills@karpathy-skills
```

Cursor 项目可以复用该仓库的 `.cursor/rules/karpathy-guidelines.mdc`。Codex 或其他工具如果没有原生 skill/plugin 支持，必须把 `skills/karpathy-guidelines/SKILL.md` 的内容合并进项目 `AGENTS.md` 或等价 agent 指引文件，避免依赖运行时临时联网读取。

---

## 3. Session 启动检查

每个 agent session 开始前，最好检查团队批准的 plugin、skill 和 MCP 配置是否有更新，尤其是 `superpowers` 与 `karpathy-guidelines`。这样可以避免不同开发者使用过期 workflow 或不一致的行为准则。

**检查要求：**
- 查看当前工具已启用的 plugin、skill、MCP server 是否与项目文档一致
- 检查 `superpowers`、`karpathy-guidelines` 以及项目级补充能力是否有可用更新
- 如有更新，优先更新后再开始 feature、bugfix 或 review
- 如当前环境无法联网或无法更新，必须在 session 记录或 PR 描述中说明，避免误认为已使用最新准则

---

## 4. Context 管理

大 feature 开发优先使用支持 **1M context window** 的模型，以减少遗漏上下文、重复探索和跨 session 交接成本。模型选择由各工具实际可用能力决定；如果没有 1M context 模型，必须主动缩小任务范围，并用计划文档或任务拆分降低上下文压力。

**默认原则：**
- 一个 feature 尽量由同一个 agent session 从 `brainstorming` 跑到 `verification-before-completion`
- 不要让多个 session 同时修改同一个 feature 的同一批文件
- 大 feature 必须先产出 plan，再执行；plan 是 session 恢复和交接的最小上下文载体
- 长任务中定期压缩上下文：记录已完成事项、未解决问题、关键决策、验证结果和剩余步骤

**无法避免多 session 时：**
- 明确切分 ownership，例如按模块、目录、接口、测试层级分配，避免重叠写同一文件
- 每个 session 开始前必须读取最新 plan、当前 diff、相关测试结果和其他 session 的交接记录
- 每个 session 结束前必须写交接记录：改了什么、为什么改、验证了什么、还有什么风险
- 合并前必须由一个 owner session 统一执行 `requesting-code-review`、`receiving-code-review` 和 `verification-before-completion`
- 如果发现两个 session 的修改方向冲突，先停止编码，更新 plan，再决定保留哪条路径

---

## 5. 固定开发流程

不同类型任务必须走不同的最小流程。流程默认由 `superpowers` plugin 的 workflow 执行，可以增加步骤，但不能跳过必需步骤。

| 任务类型 | 必需流程 |
|----------|----------|
| feature | `brainstorming → writing-plans → executing-plans 或 subagent-driven-development → requesting-code-review → receiving-code-review → verification-before-completion` |
| bugfix | `systematic-debugging → dev → requesting-code-review → receiving-code-review → verification-before-completion` |
| 小代码调整 | `dev → requesting-code-review → receiving-code-review → verification-before-completion` |

**执行规则：**
- `brainstorming` 用于澄清目标、约束、替代方案和验收标准
- `writing-plans` 必须产出范围、风险点、实现步骤和测试策略
- `executing-plans` 适合批量执行有明确 checkpoint 的计划
- `subagent-driven-development` 适合较大 feature，必须包含 spec compliance 和 code quality 两阶段 review
- `dispatching-parallel-agents` 只用于可并行、边界清晰、互不冲突的任务
- `test-driven-development` 不是独立大阶段，而是 `dev`、`executing-plans` 或 `subagent-driven-development` 过程中的实现约束；所有新增或修改可测行为必须先补测试再改实现
- `dev` 必须遵循 `karpathy-guidelines`：明确假设，选择最简单可行方案，只做与任务直接相关的外科手术式修改，并定义可验证成功标准
- `requesting-code-review` / `receiving-code-review` 必须闭环处理反馈，重点检查行为回归、边界条件、错误处理和测试缺口
- `systematic-debugging` 必须先复现、定位根因，再修复；禁止靠猜测改代码
- `verification-before-completion` 必须用测试结果、日志或等价证据确认问题已修复，并运行项目规定的最小验证命令；无法运行时必须在 PR 中说明原因
- `using-git-worktrees`、`finishing-a-development-branch` 按项目分支策略使用；并行开发或长任务优先使用 worktree 隔离

---

## 6. Unit Test 与 Superpowers 覆盖

Unit test 覆盖要求统一通过 `superpowers` workflow 执行。所有新增或修改的可测行为必须经过 `test-driven-development`、`requesting-code-review`、`receiving-code-review` 和 `verification-before-completion` 闭环。

**覆盖要求：**
- `test-driven-development` 必须按 RED-GREEN-REFACTOR 执行：先写失败测试，再写最小实现，再重构
- `requesting-code-review` 必须检查本次变更涉及的所有可测行为、关键分支、边界条件和异常路径是否有 unit test
- `receiving-code-review` 必须修复 review 指出的测试缺口，不能只解释原因
- `verification-before-completion` 必须用测试结果或等价证据确认覆盖缺口已关闭
- 覆盖目标是本次变更范围内的可测行为 100% 被检查，而不是强制全仓代码覆盖率达到 100%

**合入前必须满足：**
- [ ] 新增逻辑有对应 unit test
- [ ] 修改逻辑的旧测试已更新或补充
- [ ] 删除测试必须说明对应行为已删除或被更高层测试覆盖
- [ ] `requesting-code-review` / `receiving-code-review` 未遗留 unit test 缺口
- [ ] 如果某项无法用 unit test 覆盖，PR 中必须写明原因和替代验证方式

---

## 7. Skill / Plugin / MCP 评审流程

新增任何 skill、plugin 或 MCP server，必须先在 `ai-devflow` repo 提 PR。

**PR 描述模板：**

```
## Skill / Plugin / MCP 评审

**名称：** {skill、plugin 或 MCP 名称}
**来源：** {repo URL 或本地路径}
**适用工具：** {Claude Code / Codex / Cursor / 通用}

### 回答以下三个问题

1. **做什么？解决哪个具体问题？**
   {描述}

2. **是否与现有 skill/plugin 功能重叠？**
   现有清单：{列出现有能力}
   {重叠分析}

3. **有哪些副作用？**
   - 文件写入范围：{描述，或"无"}
   - 网络请求：{描述，或"无"}
   - 命令执行权限：{描述，或"无"}
```

**评审规则：**
- 至少 1 人 review + approve 才能合入
- 合入后在团队群发通知："{名称} 已合入，请按对应工具文档更新本地配置"

---

## 8. Wiki 使用规范

每个业务项目必须在项目根目录维护一个 `wiki/` 目录，从 `quant-ai-devflow/wiki-template/` 初始化：

```bash
cp -R /path/to/quant-ai-devflow/wiki-template/ ./wiki/
```

在项目 `CLAUDE.md` 中引入 wiki 操作协议：

```markdown
Wiki 操作协议：@wiki/wiki-schema.md
```

**agent 在开发流程中的 wiki 行为：**

| 流程节点 | 动作 |
|---------|------|
| `brainstorming` / `writing-plans` / `systematic-debugging` 开始时 | Query wiki，获取相关模块的设计决策、历史踩坑、业务约束 |
| `verification-before-completion` 完成后 | 判断是否有值得沉淀的内容，有则 ingest |

**Query 规则：**
- 任务涉及已有模块时触发；找不到相关页面直接跳过
- 纯新功能、无历史背景时跳过

**Ingest 规则：**
- 新的架构/接口决策 → `sources/architecture/` 或 `sources/decisions/`
- 业务逻辑说明 → `sources/business/`
- 踩坑记录、事故复盘 → `sources/ops/`
- 没有值得沉淀的内容时跳过，不强制每次都写

**Lint：** 不纳入自动流程，手动触发。

**wiki-schema.md 版本同步：** schema 有 breaking change 时（目录约定或操作协议变更），quant-ai-devflow PR 描述中注明受影响项目和迁移方式；非 breaking change 无需通知。

---
