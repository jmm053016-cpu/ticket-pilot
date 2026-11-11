# 提示词模板目录

这里存放**运行时真实生效**的 AI 提示词，由 `ClasspathPromptCatalog` 在应用启动时一次性加载。
加载失败（缺文件）会直接让 Spring Boot 启动失败。

## 命名规则

每个提示词 ID 对应两个文件：

| 文件 | 用途 |
| --- | --- |
| `<id>.system.md` | 作为 `AiRequest.systemPrompt` 原样发送，**不做变量替换** |
| `<id>.user.md` | 作为 `AiRequest.userPrompt`，渲染 `{{变量}}` 后发送 |

当前 ID（见 `PromptIds`）：

| ID | 使用方 | 可用变量 |
| --- | --- | --- |
| `intent-agent` | `IntentAgent.analyze()` | `userMessage`、`customerId`、`orderNo`、`toolMetadata` |
| `order-agent` | `OrderAgent.analyze()` | `userMessage`、`orderToolResult` |
| `knowledge-agent` | `KnowledgeAgent.synthesize()` | `userMessage`、`sources` |
| `direct-answer` | `AssistantWorkflowService.runDirectQwen()` | `userMessage` |

## 编辑约定

1. **文件内容就是提示词原文**，不要加 Markdown 标题、注释或说明文字——它们会一起发给模型。
   人类可读的说明写在本文档里。
2. 占位符只用 `{{name}}` 语法。渲染时只替换调用点传入的变量，其余花括号原样保留，
   因此模板里可以直接写 JSON 示例。
3. 若模板里出现调用点没传的变量，渲染会抛 `IllegalStateException`（不会静默发半成品）。
   `ClasspathPromptCatalogTest` 会为每个模板跑一遍渲染，占位符写错会直接测试失败。
4. 文件编码必须是 **UTF-8 无 BOM**。读取时会统一 `CRLF -> LF` 并去掉尾部空白，
   所以 Windows 换行不会污染提示词。
5. 改完需重新构建/重启后端才生效（`docker compose up -d --build backend`）；
   当前没有热加载，也没有把提示词外置到可挂载目录。

## 历史说明

改造前，这些 md 只是 4 个与本目录代码无关的摘要占位文件（代码里的提示词硬编码在 Java 文本块中），
`final-response.md` 甚至没有任何调用点。改造后 md 成为唯一事实来源，Java 里不再有提示词字面量。
