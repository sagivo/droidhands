// Tests scripts/app-store-connect.mjs and scripts/release-notes.mjs against a fake App Store Connect API.
//
//   node --test scripts/app-store-connect.test.mjs
import assert from 'node:assert/strict'
import { generateKeyPairSync, verify } from 'node:crypto'
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { createServer } from 'node:http'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { after, before, beforeEach, test } from 'node:test'

const dir = mkdtempSync(join(tmpdir(), 'asc-test-'))
const { privateKey, publicKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' })
const keyPath = join(dir, 'AuthKey_KEY123.p8')
writeFileSync(keyPath, privateKey.export({ type: 'pkcs8', format: 'pem' }))

// The fake API: `routes` answers "METHOD /path" (query string ignored), `calls` records every request.
let routes = {}
let calls = []
const server = createServer((req, res) => {
  let body = ''
  req.on('data', (chunk) => (body += chunk))
  req.on('end', () => {
    const url = new URL(req.url, 'http://x')
    const call = { method: req.method, path: url.pathname, query: Object.fromEntries(url.searchParams), body: body ? JSON.parse(body) : undefined, auth: req.headers.authorization }
    calls.push(call)
    const route = routes[`${req.method} ${url.pathname}`]
    const answer = typeof route === 'function' ? route(call) : route
    if (!answer) {
      res.writeHead(404).end(JSON.stringify({ errors: [{ title: 'Not found', detail: `${req.method} ${url.pathname}` }] }))
      return
    }
    const [status, json] = Array.isArray(answer) ? answer : [200, answer]
    res.writeHead(status, { 'Content-Type': 'application/json' }).end(json === undefined ? '' : JSON.stringify(json))
  })
})

let main
before(async () => {
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  process.env.ASC_API_URL = `http://127.0.0.1:${server.address().port}`
  process.env.ASC_KEY_ID = 'KEY123'
  process.env.ASC_ISSUER_ID = 'issuer-uuid'
  process.env.ASC_KEY_PATH = keyPath
  process.env.ASC_POLL_SECONDS = '0'
  ;({ main } = await import('./app-store-connect.mjs'))
})
after(() => server.close())
beforeEach(() => {
  routes = {}
  calls = []
})

const app = { data: [{ id: 'app-1', attributes: { bundleId: 'com.droidhands.ios' } }, { id: 'app-2', attributes: { bundleId: 'com.droidhands.ios.other' } }] }
const build = (state) => ({ data: [{ id: 'build-1', attributes: { version: '10203', processingState: state } }] })
const find = (method, path) => calls.filter((c) => c.method === method && c.path === path)

async function capture(fn) {
  const lines = []
  const log = console.log
  console.log = (...args) => lines.push(args.join(' '))
  try {
    await fn()
  } finally {
    console.log = log
  }
  return lines.join('\n')
}

test('signs requests with an ES256 token App Store Connect accepts', async () => {
  routes['GET /v1/apps'] = app
  routes['GET /v1/builds'] = { data: [] }
  const out = await capture(() => main(['has-build', '--bundle-id', 'com.droidhands.ios', '--version', '1.2.3', '--build', '10203']))
  assert.equal(out, 'no')
  const [header, payload, signature] = calls[0].auth.replace('Bearer ', '').split('.')
  assert.deepEqual(JSON.parse(Buffer.from(header, 'base64url')), { alg: 'ES256', kid: 'KEY123', typ: 'JWT' })
  const claims = JSON.parse(Buffer.from(payload, 'base64url'))
  assert.equal(claims.iss, 'issuer-uuid')
  assert.equal(claims.aud, 'appstoreconnect-v1')
  assert.ok(claims.exp - claims.iat <= 1200, 'Apple refuses tokens valid for more than 20 minutes')
  assert.ok(verify('sha256', Buffer.from(`${header}.${payload}`), { key: publicKey, dsaEncoding: 'ieee-p1363' }, Buffer.from(signature, 'base64url')))
  assert.equal(find('GET', '/v1/builds')[0].query['filter[app]'], 'app-1', 'the exact bundle id, not one that starts with it')
})

test('has-build says yes for an uploaded build', async () => {
  routes['GET /v1/apps'] = app
  routes['GET /v1/builds'] = build('PROCESSING')
  assert.equal(await capture(() => main(['has-build', '--bundle-id', 'com.droidhands.ios', '--version', '1.2.3', '--build', '10203'])), 'yes')
})

test('a missing app is explained', async () => {
  routes['GET /v1/apps'] = { data: [] }
  await assert.rejects(main(['has-build', '--bundle-id', 'com.droidhands.ios', '--version', '1.2.3', '--build', '1']), /no app with bundle id com.droidhands.ios/)
})

test('API errors carry Apple\'s message and a hint for permissions', async () => {
  routes['GET /v1/apps'] = [403, { errors: [{ title: 'Forbidden', detail: 'The API key in use does not allow this request' }] }]
  await assert.rejects(main(['has-build', '--bundle-id', 'x', '--version', '1.2.3', '--build', '1']), /403.*does not allow this request.*role/)
})

test('submit waits for processing, creates the version, attaches the build, sets What\'s New and submits', async () => {
  const notes = join(dir, 'notes.txt')
  writeFileSync(notes, '• Faster pairing\n')
  let polls = 0
  routes['GET /v1/apps'] = app
  routes['GET /v1/apps/app-1/appStoreVersions'] = (call) => call.query['filter[versionString]']
    ? { data: [] }
    : { data: [{ id: 'old', attributes: { versionString: '1.2.2', appVersionState: 'READY_FOR_DISTRIBUTION' } }] }
  routes['GET /v1/builds'] = () => (++polls < 2 ? { data: [] } : polls < 3 ? build('PROCESSING') : build('VALID'))
  routes['POST /v1/appStoreVersions'] = [201, { data: { id: 'ver-1', attributes: { versionString: '1.2.3' } } }]
  routes['PATCH /v1/appStoreVersions/ver-1/relationships/build'] = [204]
  routes['GET /v1/appStoreVersions/ver-1/appStoreVersionLocalizations'] = { data: [{ id: 'loc-en', attributes: { locale: 'en-US' } }, { id: 'loc-de', attributes: { locale: 'de-DE' } }] }
  routes['PATCH /v1/appStoreVersionLocalizations/loc-en'] = { data: {} }
  routes['PATCH /v1/appStoreVersionLocalizations/loc-de'] = { data: {} }
  routes['GET /v1/reviewSubmissions'] = { data: [] }
  routes['POST /v1/reviewSubmissions'] = [201, { data: { id: 'sub-1' } }]
  routes['GET /v1/reviewSubmissions/sub-1/items'] = { data: [] }
  routes['POST /v1/reviewSubmissionItems'] = [201, { data: { id: 'item-1' } }]
  routes['PATCH /v1/reviewSubmissions/sub-1'] = { data: { id: 'sub-1' } }

  const out = await capture(() => main(['submit', '--bundle-id', 'com.droidhands.ios', '--version', '1.2.3', '--build', '10203', '--notes-file', notes]))

  assert.equal(polls, 3)
  assert.match(out, /Submitted 1\.2\.3 \(10203\) for App Review/)
  const created = find('POST', '/v1/appStoreVersions')[0].body.data
  assert.deepEqual(created.attributes, { platform: 'IOS', versionString: '1.2.3', releaseType: 'AFTER_APPROVAL' })
  assert.equal(created.relationships.app.data.id, 'app-1')
  assert.deepEqual(find('PATCH', '/v1/appStoreVersions/ver-1/relationships/build')[0].body, { data: { type: 'builds', id: 'build-1' } })
  assert.equal(find('PATCH', '/v1/appStoreVersionLocalizations/loc-de')[0].body.data.attributes.whatsNew, '• Faster pairing')
  assert.equal(find('POST', '/v1/reviewSubmissionItems')[0].body.data.relationships.appStoreVersion.data.id, 'ver-1')
  assert.deepEqual(find('PATCH', '/v1/reviewSubmissions/sub-1')[0].body.data.attributes, { submitted: true })
  const order = calls.map((c) => `${c.method} ${c.path}`)
  assert.ok(order.indexOf('PATCH /v1/reviewSubmissions/sub-1') === order.length - 1, 'submitting is the last step')
})

test('submit renames the version in progress instead of creating a second one, and reuses an open submission', async () => {
  routes['GET /v1/apps'] = app
  routes['GET /v1/apps/app-1/appStoreVersions'] = (call) => call.query['filter[versionString]']
    ? { data: [] }
    : { data: [{ id: 'ver-0', attributes: { versionString: '1.2.2', appVersionState: 'PREPARE_FOR_SUBMISSION' } }] }
  routes['GET /v1/builds'] = build('VALID')
  routes['PATCH /v1/appStoreVersions/ver-0'] = { data: { id: 'ver-0', attributes: { versionString: '1.2.3' } } }
  routes['PATCH /v1/appStoreVersions/ver-0/relationships/build'] = [204]
  routes['GET /v1/reviewSubmissions'] = { data: [{ id: 'sub-9', attributes: { state: 'READY_FOR_REVIEW' } }] }
  routes['GET /v1/reviewSubmissions/sub-9/items'] = { data: [{ id: 'i', relationships: { appStoreVersion: { data: { id: 'ver-0' } } } }] }
  routes['PATCH /v1/reviewSubmissions/sub-9'] = { data: {} }

  await capture(() => main(['submit', '--bundle-id', 'com.droidhands.ios', '--version', '1.2.3', '--build', '10203']))

  assert.equal(find('PATCH', '/v1/appStoreVersions/ver-0')[0].body.data.attributes.versionString, '1.2.3')
  assert.equal(find('POST', '/v1/appStoreVersions').length, 0)
  assert.equal(find('POST', '/v1/reviewSubmissions').length, 0)
  assert.equal(find('POST', '/v1/reviewSubmissionItems').length, 0, 'the version is already in the submission')
  assert.equal(find('GET', '/v1/appStoreVersions/ver-0/appStoreVersionLocalizations').length, 0, 'no notes, no What\'s New')
  assert.equal(find('PATCH', '/v1/reviewSubmissions/sub-9').length, 1)
})

test('the first version of an app goes ahead without What\'s New', async () => {
  const notes = join(dir, 'notes.txt')
  writeFileSync(notes, 'First release')
  routes['GET /v1/apps'] = app
  routes['GET /v1/apps/app-1/appStoreVersions'] = { data: [{ id: 'ver-1', attributes: { versionString: '1.0.0', appVersionState: 'PREPARE_FOR_SUBMISSION' } }] }
  routes['GET /v1/builds'] = build('VALID')
  routes['PATCH /v1/appStoreVersions/ver-1/relationships/build'] = [204]
  routes['GET /v1/appStoreVersions/ver-1/appStoreVersionLocalizations'] = { data: [{ id: 'loc-en', attributes: { locale: 'en-US' } }] }
  routes['PATCH /v1/appStoreVersionLocalizations/loc-en'] = [409, { errors: [{ title: 'An attribute value is not acceptable for the current resource state.', detail: 'whatsNew cannot be edited at this time' }] }]
  routes['GET /v1/reviewSubmissions'] = { data: [] }
  routes['POST /v1/reviewSubmissions'] = [201, { data: { id: 'sub-1' } }]
  routes['GET /v1/reviewSubmissions/sub-1/items'] = { data: [] }
  routes['POST /v1/reviewSubmissionItems'] = [201, { data: {} }]
  routes['PATCH /v1/reviewSubmissions/sub-1'] = { data: {} }

  const out = await capture(() => main(['submit', '--bundle-id', 'com.droidhands.ios', '--version', '1.0.0', '--build', '10203', '--notes-file', notes]))
  assert.match(out, /"What's New" not set for en-US/)
  assert.match(out, /Submitted/)
})

test('submit does nothing for a version that is already with Apple', async () => {
  routes['GET /v1/apps'] = app
  routes['GET /v1/apps/app-1/appStoreVersions'] = { data: [{ id: 'ver-1', attributes: { versionString: '1.2.3', appVersionState: 'WAITING_FOR_REVIEW' } }] }
  const out = await capture(() => main(['submit', '--bundle-id', 'com.droidhands.ios', '--version', '1.2.3', '--build', '10203']))
  assert.match(out, /already waiting for review: nothing to submit/)
  assert.equal(calls.filter((c) => c.method !== 'GET').length, 0)
})

test('submit gives up on a build Apple rejected, and after the wait', async () => {
  routes['GET /v1/apps'] = app
  routes['GET /v1/apps/app-1/appStoreVersions'] = { data: [] }
  routes['GET /v1/builds'] = build('INVALID')
  await assert.rejects(capture(() => main(['submit', '--bundle-id', 'com.droidhands.ios', '--version', '1.2.3', '--build', '10203'])), /could not process build.*INVALID/)
  routes['GET /v1/builds'] = { data: [] }
  await assert.rejects(capture(() => main(['submit', '--bundle-id', 'com.droidhands.ios', '--version', '1.2.3', '--build', '10203', '--wait-minutes', '0'])), /wasn't ready after 0 minutes/)
})

test('profile reuses a valid profile for the certificate', async () => {
  const out = join(dir, 'reuse.mobileprovision')
  routes['GET /v1/certificates'] = { data: [{ id: 'cert-1', attributes: { serialNumber: '1A2B3C' } }] }
  routes['GET /v1/bundleIds'] = { data: [{ id: 'bid-1', attributes: { identifier: 'com.droidhands.ios' } }] }
  routes['GET /v1/profiles'] = { data: [{ id: 'prof-1', attributes: { name: 'com.droidhands.ios App Store (CI)', profileState: 'ACTIVE', uuid: 'UUID-1', profileContent: Buffer.from('profile-bytes').toString('base64') }, relationships: { certificates: { data: [{ id: 'cert-1' }] } } }] }
  const printed = await capture(() => main(['profile', '--bundle-id', 'com.droidhands.ios', '--certificate-serial', '001a2b3c', '--out', out]))
  assert.deepEqual(JSON.parse(printed), { name: 'com.droidhands.ios App Store (CI)', uuid: 'UUID-1' })
  assert.equal(readFileSync(out, 'utf8'), 'profile-bytes')
  assert.equal(calls.filter((c) => c.method !== 'GET').length, 0)
})

test('profile replaces one made for another certificate', async () => {
  const out = join(dir, 'new.mobileprovision')
  routes['GET /v1/certificates'] = { data: [{ id: 'cert-2', attributes: { serialNumber: 'FF01' } }] }
  routes['GET /v1/bundleIds'] = { data: [{ id: 'bid-x', attributes: { identifier: 'com.droidhands.ios.tests' } }, { id: 'bid-1', attributes: { identifier: 'com.droidhands.ios' } }] }
  routes['GET /v1/profiles'] = { data: [{ id: 'prof-1', attributes: { name: 'com.droidhands.ios App Store (CI)', profileState: 'ACTIVE' }, relationships: { certificates: { data: [{ id: 'cert-1' }] } } }] }
  routes['DELETE /v1/profiles/prof-1'] = [204]
  routes['POST /v1/profiles'] = [201, { data: { id: 'prof-2', attributes: { name: 'com.droidhands.ios App Store (CI)', uuid: 'UUID-2', profileContent: Buffer.from('new').toString('base64') } } }]
  await capture(() => main(['profile', '--bundle-id', 'com.droidhands.ios', '--certificate-serial', 'FF01', '--out', out]))
  assert.equal(find('DELETE', '/v1/profiles/prof-1').length, 1)
  const body = find('POST', '/v1/profiles')[0].body.data
  assert.equal(body.attributes.profileType, 'IOS_APP_STORE')
  assert.equal(body.relationships.bundleId.data.id, 'bid-1')
  assert.deepEqual(body.relationships.certificates.data, [{ type: 'certificates', id: 'cert-2' }])
  assert.equal(readFileSync(out, 'utf8'), 'new')
})

test('profile explains a certificate App Store Connect doesn\'t know', async () => {
  routes['GET /v1/certificates'] = { data: [] }
  await assert.rejects(main(['profile', '--bundle-id', 'com.droidhands.ios', '--certificate-serial', 'AB', '--out', join(dir, 'x')]), /no distribution certificate with serial AB/)
})

test('unknown commands and missing options are refused', async () => {
  await assert.rejects(main(['bogus']), /Usage/)
  await assert.rejects(main(['submit', '--version', '1.2.3']), /--bundle-id is required/)
})

// ------------------------------------------------------------------------------------------- release notes

test('release notes: a "What\'s new" section wins and becomes plain text', async () => {
  const { storeNotes } = await import('./release-notes.mjs')
  const body = [
    'Download `DroidHands.apk` below.',
    '',
    '## What\'s new',
    '- **Stop** a running request',
    '* See [usage](https://example.com) per agent',
    '',
    '### Fixes',
    '',
    '## Install',
    'Signing certificate: 4E:BE…',
  ].join('\r\n')
  assert.equal(storeNotes(body), '• Stop a running request\n• See usage per agent')
})

test('release notes: GitHub\'s generated notes lose links, contributors and the changelog link', async () => {
  const { storeNotes } = await import('./release-notes.mjs')
  const body = [
    '<!-- Release notes generated using configuration in .github/release.yml -->',
    '## What\'s Changed',
    '* Add agent schedules by @sagivo in https://github.com/sagivo/droidhands/pull/12',
    '',
    '## New Contributors',
    '* @someone made their first contribution in https://github.com/sagivo/droidhands/pull/11',
    '',
    '**Full Changelog**: https://github.com/sagivo/droidhands/compare/v0.1.2...v0.1.3',
  ].join('\n')
  assert.equal(storeNotes(body), '• Add agent schedules')
  assert.equal(storeNotes('**Full Changelog**: https://github.com/x/y/compare/v1...v2'), '')
})

test('release notes: long text is cut at a line break within the limit', async () => {
  const { storeNotes } = await import('./release-notes.mjs')
  const lines = Array.from({ length: 40 }, (_, i) => `- Change number ${i}`).join('\n')
  const notes = storeNotes(lines, 100)
  assert.ok(notes.length <= 100)
  assert.ok(notes.endsWith(`Change number ${notes.split('\n').length - 1}`))
  assert.equal(storeNotes('x'.repeat(30), 10), `${'x'.repeat(9)}…`)
})
