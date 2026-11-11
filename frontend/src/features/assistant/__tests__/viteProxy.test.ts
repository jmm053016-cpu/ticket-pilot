import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * 开发代理前缀的守卫（2026-11-10 加的，因为真踩过）。
 *
 * 现象：后端加了 `/auth/**` 与 `/admin/**`，但 `vite.config.ts` 的 proxy 没跟着加 →
 * 浏览器请求 `/auth/login` 拿到 **404**（`/admin/...` 更隐蔽，拿到 index.html 的 200），
 * 页面表现就是"购物车和客服工作台都显示登录失败"。
 *
 * 这条用例把"后端有哪些前缀"和"代理有没有覆盖"钉在一起，纯文本解析，不启动 Vite。
 * 读取路径基于 vitest 的工作目录（在前端根目录跑），所以不依赖 import.meta.url 的 scheme。
 */
const BACKEND_PREFIXES = ['/assistant', '/auth', '/admin', '/tickets', '/orders', '/knowledge', '/products']

describe('vite 开发代理覆盖后端所有接口前缀', () => {
  const config = readFileSync(resolve(process.cwd(), 'vite.config.ts'), 'utf8')

  it.each(BACKEND_PREFIXES)('proxy 里有 %s 规则', (prefix) => {
    expect(config).toContain(`'${prefix}':`)
  })

  it('代理目标指向后端 8080', () => {
    expect(config).toContain('http://localhost:8080')
  })
})
