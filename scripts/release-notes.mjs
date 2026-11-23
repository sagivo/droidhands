#!/usr/bin/env node
// Turns a GitHub release's Markdown body into the plain "What's new" text the stores show, on stdout.
//
//   gh release view v1.2.3 --json body -q .body | node scripts/release-notes.mjs [--max 500]
//
// A "## What's new" section, when the body has one, is used on its own, so the rest of the body can talk about
// the APK download. Otherwise the whole body is used, minus what GitHub generates (contributor lists, "Full
// Changelog" links, "by @someone in <url>"). Headings are dropped, bullets become "•", links and emphasis become
// plain text. Text longer than --max (Google Play allows 500 characters) is cut at a line break.
import { readFileSync } from 'node:fs'
import { pathToFileURL } from 'node:url'

export function storeNotes(markdown, max = 500) {
  let text = markdown.replace(/\r\n?/g, '\n').replace(/<!--[\s\S]*?-->/g, '')
  const lines = text.split('\n')
  const start = lines.findIndex((line) => /^#{1,6}\s*what['’]?s\s+new\b/i.test(line.trim()))
  if (start >= 0) {
    const level = lines[start].trim().match(/^#+/)[0].length
    const end = lines.findIndex((line, i) => i > start && (line.trim().match(/^(#+)\s/)?.[1].length ?? 99) <= level)
    text = lines.slice(start + 1, end < 0 ? undefined : end).join('\n')
  } else {
    let skipping = false
    text = lines.filter((line) => {
      if (/^\s*#{1,6}\s/.test(line)) skipping = /new contributors/i.test(line)
      return !skipping
    }).join('\n')
  }
  text = text
    .split('\n')
    .filter((line) => !/^\s*\*\*Full Changelog\*\*/i.test(line) && !/^\s*#{1,6}\s/.test(line))
    .map((line) => line
      .replace(/\s+by @[\w-]+ in https?:\/\/\S+$/, '')
      .replace(/^\s*[-*+]\s+/, '• ')
      .replace(/!?\[([^\]]*)\]\([^)]*\)/g, '$1')
      .replace(/(\*\*|__|`)/g, '')
      .trimEnd())
    .join('\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim()
  if (text.length <= max) return text
  const cut = text.lastIndexOf('\n', max - 1)
  return `${text.slice(0, cut > 0 ? cut : max - 1).trimEnd()}${cut > 0 ? '' : '…'}`
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  const maxAt = process.argv.indexOf('--max')
  const max = maxAt > 0 ? Number(process.argv[maxAt + 1]) : 500
  if (!Number.isInteger(max) || max < 1) {
    console.error('--max needs a positive whole number')
    process.exit(1)
  }
  process.stdout.write(storeNotes(readFileSync(0, 'utf8'), max))
}
