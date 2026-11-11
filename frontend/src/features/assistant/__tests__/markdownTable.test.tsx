import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { MarkdownAnswer } from '../components/StreamingAnswer'
import { isSeparatorRow, isTableRow, parseTableBlock, splitCells } from '../components/markdownTable'

describe('markdownTable', () => {
  it('splits cells and strips outer pipes', () => {
    expect(splitCells('| 商品 | 价格 |')).toEqual(['商品', '价格'])
    expect(isTableRow('| 商品 | 价格 |')).toBe(true)
    expect(isTableRow('普通一行')).toBe(false)
    expect(isSeparatorRow('|---|---|')).toBe(true)
    expect(isSeparatorRow('| :--- | ---: |')).toBe(true)
    expect(isSeparatorRow('| 商品 | 价格 |')).toBe(false)
  })

  it('parses header + body rows and reports the consumed range', () => {
    const lines = ['说明一行', '| 商品 | 价格 |', '|---|---|', '| T恤 | ¥99.00 |', '| Polo衫 | ¥169.00 |', '结尾一行']
    const table = parseTableBlock(lines, 1)

    expect(table).not.toBeNull()
    expect(table!.headers).toEqual(['商品', '价格'])
    expect(table!.rows).toEqual([
      ['T恤', '¥99.00'],
      ['Polo衫', '¥169.00'],
    ])
    expect(table!.endIndex).toBe(4)
  })

  it('returns null while the table is still streaming (no separator row yet)', () => {
    expect(parseTableBlock(['| 商品 | 价格 |'], 0)).toBeNull()
  })

  it('pads short rows to the header width', () => {
    const table = parseTableBlock(['| a | b | c |', '|---|---|---|', '| 1 | 2 |'], 0)
    expect(table!.rows[0]).toEqual(['1', '2', ''])
  })
})

describe('MarkdownAnswer', () => {
  it('renders a markdown table as a real table instead of raw pipes', () => {
    const text = [
      '最多可以买 5 件，合计 ¥885。',
      '',
      '| 商品 | 价格 |',
      '|---|---|',
      '| 基础纯棉白色T恤 | ¥99.00 |',
      '| 米色针织毛衣 | ¥229.00 |',
      '',
      '第 6 件最低 ¥259，超出预算，所以 5 件是上限。',
    ].join('\n')

    render(<MarkdownAnswer text={text} />)

    expect(screen.getByRole('table')).toBeTruthy()
    expect(screen.getAllByRole('columnheader').map((cell) => cell.textContent)).toEqual(['商品', '价格'])
    expect(screen.getAllByRole('cell').map((cell) => cell.textContent)).toEqual(['基础纯棉白色T恤', '¥99.00', '米色针织毛衣', '¥229.00'])
    expect(screen.queryByText(/\|---\|/)).toBeNull()
  })

  it('still renders plain paragraphs and lists', () => {
    render(<MarkdownAnswer text={'**结论**\n- 第一条\n普通段落'} />)

    expect(screen.getByText('结论')).toBeTruthy()
    expect(screen.getByText('第一条')).toBeTruthy()
    expect(screen.getByText('普通段落')).toBeTruthy()
  })
})