# Wiki Schema
<!-- version: 1.0 -->

本文档定义项目 wiki 的操作协议，供 LLM 读取。嵌入项目 CLAUDE.md 后即生效。

---

## 目录约定

| 路径 | 用途 |
|------|------|
| `wiki/sources/architecture/` | 架构设计文档、技术方案原文 |
| `wiki/sources/business/` | 业务逻辑说明、产品需求原文 |
| `wiki/sources/ops/` | 运维手册、事故报告、配置说明 |
| `wiki/sources/decisions/` | 架构决策记录（ADR）、技术选型说明 |
| `wiki/pages/{category}/` | LLM 生成的 wiki 页面，与 sources 分类对齐 |

**文件命名：**
- sources：`wiki/sources/{category}/{YYYY-MM-DD}-{title}.md`
- pages：`wiki/pages/{category}/{topic}.md`

---

## 操作一：Ingest

**触发时机：** 代码变更涉及架构、接口、配置、踩坑；或 PR merge 前自查。

**两种来源：**

```
外部文档（先放 sources/）：
  wiki/sources/ 新增文件 → 读取内容 → wiki/pages/ 写入或更新 → 更新 index.md + log.md

对话上下文（直接 ingest，不落 sources/）：
  当前对话内容 → wiki/pages/ 写入或更新 → 更新 index.md + log.md
```

**执行步骤：**
1. 读取 source（文件路径或对话上下文）
2. 在 `wiki/pages/{category}/{topic}.md` 写入或更新页面
3. 更新 `wiki/index.md`：新增条目或修改对应行的摘要和日期
4. 在 `wiki/log.md` 末尾追加：`## [YYYY-MM-DD] ingest | {标题}`

**约束：**
- 一个 source 可以更新多个 pages 页面
- 新信息与现有页面矛盾时：更新页面内容，在 log.md 条目中注明"修订：{原因}"
- 禁止修改 `wiki/sources/` 下的任何文件

---

## 操作二：Query

**何时跳过：** 纯代码操作（读文件、改逻辑、调试、写测试）直接操作代码，不查 wiki。

**何时触发：** 问题涉及"为什么这样设计"、"这个模块做什么"、"历史上踩过什么坑"等知识性问题时触发。

**执行步骤：**
1. 读 `wiki/index.md`，定位相关页面路径
2. 读对应页面，综合回答
3. 如果回答产生新洞察或发现新连接，执行 Ingest 将其写入 wiki

---

## 操作三：Lint

**触发时机：** 用户手动触发（"lint wiki" 或等价表达）。

**检查项（逐一执行）：**

1. **孤立页面**：列出 `wiki/pages/` 下所有文件，找出 `wiki/index.md` 中未记录的条目
2. **内容矛盾**：读 index.md 中同一 category 的页面，检查对同一概念的描述是否一致
3. **缺失页面**：扫描 pages/ 中被多处提及但没有独立文件的概念
4. **覆盖度**：对比 `wiki/log.md` 最近 10 条 ingest 记录与 git log 近期 commit，判断是否有重要变更未 ingest

发现问题后，列出清单询问用户是否修复，不自动修改。
