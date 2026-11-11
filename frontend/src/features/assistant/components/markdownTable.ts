/**
 * 极简 Markdown 表格解析。
 *
 * <p>回答渲染器原本只认 `**加粗**`、`- ` 列表和 `**标题**：内容`，
 * 模型一旦输出 `| 商品 | 价格 |` 表格，界面上就是一串裸管道符。
 * 这里只做一件事：把连续的表格行解析成表头 + 数据行，交给 React 渲染。
 *
 * <p>刻意不引 markdown 库：需要支持的语法就这几样，自己解析可控、零依赖，
 * 也不会因为流式追加时"表格还没写完"而整段崩掉（解析失败就当普通文本）。
 */

export type TableModel = {
  headers: string[]
  rows: string[][]
  /** 表格最后一行在 lines 里的下标（调用方据此跳过已消费的行）。 */
  endIndex: number
}

/** 是否是表格行：以 `|` 开头且至少还有一个 `|`。 */
export function isTableRow(line: string): boolean {
  const trimmed = line.trim()
  return trimmed.startsWith('|') && trimmed.indexOf('|', 1) > 0
}

/** 是否是分隔行，如 `|---|---|`、`| :--- | ---: |`。 */
export function isSeparatorRow(line: string): boolean {
  const trimmed = line.trim()
  if (!isTableRow(trimmed)) {
    return false
  }
  return /^\|(\s*:?-{1,}:?\s*\|)+$/.test(trimmed)
}

/** 拆单元格：去掉首尾的 `|`，按 `|` 切分并 trim。 */
export function splitCells(line: string): string[] {
  let trimmed = line.trim()
  if (trimmed.startsWith('|')) {
    trimmed = trimmed.slice(1)
  }
  if (trimmed.endsWith('|')) {
    trimmed = trimmed.slice(0, -1)
  }
  return trimmed.split('|').map((cell) => cell.trim())
}

/**
 * 从 startIndex 开始尝试解析一张表。
 *
 * @returns 解析成功返回表格模型；不是表格（或只有表头没有分隔行，说明还在流式输出中）返回 null。
 */
export function parseTableBlock(lines: string[], startIndex: number): TableModel | null {
  if (startIndex >= lines.length || !isTableRow(lines[startIndex])) {
    return null
  }
  const separatorIndex = startIndex + 1
  // 没有分隔行就还不成表格：流式输出时到这里会返回 null，先按普通文本显示
  if (separatorIndex >= lines.length || !isSeparatorRow(lines[separatorIndex])) {
    return null
  }
  const headers = splitCells(lines[startIndex])
  const rows: string[][] = []
  let index = separatorIndex + 1
  while (index < lines.length && isTableRow(lines[index]) && !isSeparatorRow(lines[index])) {
    const cells = splitCells(lines[index])
    // 补齐/截断到表头列数，避免错位
    rows.push(headers.map((_, column) => cells[column] ?? ''))
    index += 1
  }
  return { headers, rows, endIndex: index - 1 }
}
