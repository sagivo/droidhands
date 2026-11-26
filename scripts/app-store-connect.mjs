#!/usr/bin/env node
// App Store Connect steps for scripts/store-release.sh that altool and xcodebuild don't do: the distribution
// certificate and provisioning profile for signing on a fresh machine (CI), and putting an uploaded build up
// for App Review. Uses the App Store Connect API with the same API key as the upload. No dependencies.
//
//   node scripts/app-store-connect.mjs create-certificate --out FILE.p12 --password-file FILE
//       One-time setup: creates an Apple Distribution certificate and saves it with its private key as a .p12
//       (store it as the IOS_DISTRIBUTION_P12_BASE64 secret, docs/RELEASING.md).
//   node scripts/app-store-connect.mjs profile --bundle-id ID --certificate-serial HEX --out FILE
//       Finds or creates the App Store provisioning profile for the bundle id and that certificate, saves it,
//       and prints {"name","uuid"}.
//   node scripts/app-store-connect.mjs has-build --bundle-id ID --version 1.2.3 --build 10203
//       Prints "yes" when App Store Connect already has that build (uploaded earlier), otherwise "no".
//   node scripts/app-store-connect.mjs submit --bundle-id ID --version 1.2.3 --build 10203 [--notes-file FILE]
//       [--wait-minutes 60]
//       Waits until Apple has processed the build, creates (or reuses) the App Store version, attaches the build,
//       sets "What's New" and submits the version for review. It is released when Apple approves it. Does
//       nothing when the version is already submitted or released.
//   node scripts/app-store-connect.mjs latest-version --bundle-id ID [--source app-store|uploads]
//       Prints the newest version Apple already has, or nothing: app-store (default) counts App Store versions
//       that are with Apple or released (not ones still being prepared, removed from review or rejected);
//       uploads counts every uploaded build (TestFlight). store-release.sh --only-if-changed compares with it.
//
// Environment: ASC_KEY_ID, ASC_ISSUER_ID, ASC_KEY_PATH (the AuthKey_<id>.p8 file). ASC_API_URL overrides the API
// address (tests); ASC_POLL_SECONDS the wait between checks of the build (default 30).
import { execFileSync } from 'node:child_process'
import { sign } from 'node:crypto'
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { pathToFileURL } from 'node:url'

const API = (process.env.ASC_API_URL || 'https://api.appstoreconnect.apple.com').replace(/\/$/, '')
const POLL_SECONDS = Number(process.env.ASC_POLL_SECONDS ?? 30)

// App Store version states in which the version can still be changed and submitted. Any other state means it is
// already with Apple (waiting, in review) or released.
const EDITABLE = new Set([
  'PREPARE_FOR_SUBMISSION', 'DEVELOPER_REJECTED', 'REJECTED', 'METADATA_REJECTED', 'INVALID_BINARY', 'READY_FOR_REVIEW',
])
// Review submissions that haven't gone to Apple yet, or came back with issues to resolve.
const OPEN_SUBMISSION = ['READY_FOR_REVIEW', 'UNRESOLVED_ISSUES']

class Fail extends Error {}

function die(message) {
  throw new Fail(message)
}

function parseArgs(argv) {
  const [command, ...rest] = argv
  const options = {}
  for (let i = 0; i < rest.length; i++) {
    const arg = rest[i]
    if (!arg.startsWith('--')) die(`Unexpected argument: ${arg}`)
    const value = rest[i + 1]
    if (value === undefined || value.startsWith('--')) die(`${arg} needs a value`)
    options[arg.slice(2)] = value
    i++
  }
  return { command, options }
}

function need(options, name) {
  const value = options[name]
  if (!value) die(`--${name} is required`)
  return value
}

const b64url = (data) => Buffer.from(data).toString('base64url')

// A 20-minute ES256 token (Apple's maximum), as App Store Connect wants.
export function token({ keyId, issuerId, keyPem, now = Math.floor(Date.now() / 1000) }) {
  const header = b64url(JSON.stringify({ alg: 'ES256', kid: keyId, typ: 'JWT' }))
  const payload = b64url(JSON.stringify({ iss: issuerId, iat: now, exp: now + 1140, aud: 'appstoreconnect-v1' }))
  const signature = sign('sha256', Buffer.from(`${header}.${payload}`), { key: keyPem, dsaEncoding: 'ieee-p1363' })
  return `${header}.${payload}.${b64url(signature)}`
}

function client() {
  const keyId = process.env.ASC_KEY_ID
  const issuerId = process.env.ASC_ISSUER_ID
  const keyPath = process.env.ASC_KEY_PATH
  if (!keyId || !issuerId || !keyPath) die('Set ASC_KEY_ID, ASC_ISSUER_ID and ASC_KEY_PATH (the AuthKey_<id>.p8 file).')
  let keyPem
  try {
    keyPem = readFileSync(keyPath, 'utf8')
  } catch {
    die(`Can't read the API key file ${keyPath}.`)
  }
  let jwt = ''
  let expires = 0
  return async function call(method, path, body) {
    const now = Math.floor(Date.now() / 1000)
    if (now > expires - 60) {
      try {
        jwt = token({ keyId, issuerId, keyPem, now })
      } catch {
        die(`${keyPath} isn't an App Store Connect API key (the AuthKey_${keyId}.p8 file Apple let you download).`)
      }
      expires = now + 1140
    }
    let response
    try {
      response = await fetch(`${API}${path}`, {
        method,
        headers: { Authorization: `Bearer ${jwt}`, ...(body ? { 'Content-Type': 'application/json' } : {}) },
        body: body ? JSON.stringify(body) : undefined,
      })
    } catch (error) {
      die(`Could not reach App Store Connect (${method} ${path}): ${error.message}`)
    }
    const text = await response.text()
    const json = text ? JSON.parse(text) : {}
    if (!response.ok) {
      const details = (json.errors ?? []).map((e) => [e.title, e.detail].filter(Boolean).join(': ')).join('; ')
      const hint = response.status === 401 || response.status === 403
        ? ' (check the API key, its issuer id, and that its role allows this: Admin, or App Manager with access to certificates and profiles)'
        : ''
      const error = new Fail(`App Store Connect answered ${response.status} to ${method} ${path}: ${details || text.slice(0, 300)}${hint}`)
      error.status = response.status
      throw error
    }
    return json
  }
}

const query = (params) => new URLSearchParams(params).toString()

async function findApp(call, bundleId) {
  const apps = await call('GET', `/v1/apps?${query({ 'filter[bundleId]': bundleId, limit: '200' })}`)
  const app = apps.data.find((a) => a.attributes.bundleId === bundleId)
  if (!app) die(`App Store Connect has no app with bundle id ${bundleId}. Create it first (Apps › +), see docs/RELEASING.md.`)
  return app
}

async function findBuild(call, appId, version, build) {
  const builds = await call('GET', `/v1/builds?${query({
    'filter[app]': appId,
    'filter[version]': build,
    'filter[preReleaseVersion.version]': version,
    'filter[preReleaseVersion.platform]': 'IOS',
    limit: '10',
  })}`)
  return builds.data.find((b) => b.attributes.version === build)
}

const sleep = (seconds) => new Promise((resolve) => setTimeout(resolve, seconds * 1000))

async function waitForBuild(call, appId, version, build, minutes) {
  const deadline = Date.now() + minutes * 60_000
  let said = ''
  for (;;) {
    const found = await findBuild(call, appId, version, build)
    const state = found?.attributes.processingState ?? 'NOT_THERE_YET'
    if (state === 'VALID') return found
    if (state === 'FAILED' || state === 'INVALID') die(`Apple could not process build ${version} (${build}): ${state}. Check the e-mail Apple sent.`)
    if (state !== said) {
      console.log(found ? `Build ${version} (${build}) is ${state.toLowerCase()} at Apple…` : `Waiting for build ${version} (${build}) to show up in App Store Connect…`)
      said = state
    }
    if (Date.now() >= deadline) die(`Build ${version} (${build}) wasn't ready after ${minutes} minutes (last state: ${state}). Run this again later to submit it.`)
    await sleep(POLL_SECONDS)
  }
}

const versionState = (v) => v.attributes.appVersionState ?? v.attributes.appStoreState

async function prepareVersion(call, appId, version) {
  const versions = await call('GET', `/v1/apps/${appId}/appStoreVersions?${query({ 'filter[platform]': 'IOS', limit: '200' })}`)
  const same = versions.data.find((v) => v.attributes.versionString === version)
  if (same) return same
  // App Store Connect allows one version in progress at a time: rename it instead of creating a second one.
  const editable = versions.data.find((v) => EDITABLE.has(versionState(v)))
  if (editable) {
    console.log(`Renaming the App Store version in progress (${editable.attributes.versionString}) to ${version}.`)
    const renamed = await call('PATCH', `/v1/appStoreVersions/${editable.id}`, {
      data: { type: 'appStoreVersions', id: editable.id, attributes: { versionString: version } },
    })
    return renamed.data
  }
  const created = await call('POST', '/v1/appStoreVersions', {
    data: {
      type: 'appStoreVersions',
      attributes: { platform: 'IOS', versionString: version, releaseType: 'AFTER_APPROVAL' },
      relationships: { app: { data: { type: 'apps', id: appId } } },
    },
  })
  console.log(`Created App Store version ${version}.`)
  return created.data
}

async function setWhatsNew(call, versionId, notes) {
  const localizations = await call('GET', `/v1/appStoreVersions/${versionId}/appStoreVersionLocalizations?limit=50`)
  for (const localization of localizations.data) {
    try {
      await call('PATCH', `/v1/appStoreVersionLocalizations/${localization.id}`, {
        data: { type: 'appStoreVersionLocalizations', id: localization.id, attributes: { whatsNew: notes } },
      })
    } catch (error) {
      // The first version of an app has no "What's New"; Apple refuses the field (409).
      if (error.status !== 409) throw error
      console.log(`"What's New" not set for ${localization.attributes.locale}: ${error.message}`)
      return
    }
  }
  if (localizations.data.length) console.log(`Set "What's New" for ${localizations.data.map((l) => l.attributes.locale).join(', ')}.`)
}

async function submitForReview(call, appId, versionId) {
  const open = await call('GET', `/v1/reviewSubmissions?${query({
    'filter[app]': appId,
    'filter[platform]': 'IOS',
    'filter[state]': OPEN_SUBMISSION.join(','),
    limit: '10',
  })}`)
  let submission = open.data[0]
  if (!submission) {
    submission = (await call('POST', '/v1/reviewSubmissions', {
      data: {
        type: 'reviewSubmissions',
        attributes: { platform: 'IOS' },
        relationships: { app: { data: { type: 'apps', id: appId } } },
      },
    })).data
  }
  const items = await call('GET', `/v1/reviewSubmissions/${submission.id}/items?include=appStoreVersion&limit=50`)
  const hasVersion = items.data.some((item) => item.relationships?.appStoreVersion?.data?.id === versionId)
  if (!hasVersion) {
    await call('POST', '/v1/reviewSubmissionItems', {
      data: {
        type: 'reviewSubmissionItems',
        relationships: {
          reviewSubmission: { data: { type: 'reviewSubmissions', id: submission.id } },
          appStoreVersion: { data: { type: 'appStoreVersions', id: versionId } },
        },
      },
    })
  }
  await call('PATCH', `/v1/reviewSubmissions/${submission.id}`, {
    data: { type: 'reviewSubmissions', id: submission.id, attributes: { submitted: true } },
  })
}

async function submit(options) {
  const bundleId = need(options, 'bundle-id')
  const version = need(options, 'version')
  const build = need(options, 'build')
  const minutes = Number(options['wait-minutes'] ?? 60)
  const notes = options['notes-file'] ? readFileSync(options['notes-file'], 'utf8').trim() : ''
  const call = client()

  const app = await findApp(call, bundleId)
  const existing = (await call('GET', `/v1/apps/${app.id}/appStoreVersions?${query({
    'filter[platform]': 'IOS',
    'filter[versionString]': version,
    limit: '5',
  })}`)).data.find((v) => v.attributes.versionString === version)
  if (existing && !EDITABLE.has(versionState(existing))) {
    console.log(`App Store version ${version} is already ${versionState(existing).toLowerCase().replaceAll('_', ' ')}: nothing to submit.`)
    return
  }

  const ready = await waitForBuild(call, app.id, version, build, minutes)
  const appStoreVersion = existing ?? await prepareVersion(call, app.id, version)
  await call('PATCH', `/v1/appStoreVersions/${appStoreVersion.id}/relationships/build`, { data: { type: 'builds', id: ready.id } })
  console.log(`Attached build ${build} to App Store version ${version}.`)
  if (notes) await setWhatsNew(call, appStoreVersion.id, notes)
  await submitForReview(call, app.id, appStoreVersion.id)
  console.log(`Submitted ${version} (${build}) for App Review. Apple releases it on the App Store once it is approved.`)
}

async function hasBuild(options) {
  const call = client()
  const app = await findApp(call, need(options, 'bundle-id'))
  const found = await findBuild(call, app.id, need(options, 'version'), need(options, 'build'))
  console.log(found ? 'yes' : 'no')
}

const sameSerial = (a, b) => a.replace(/^0+/, '').toUpperCase() === b.replace(/^0+/, '').toUpperCase()

async function profile(options) {
  const bundleIdentifier = need(options, 'bundle-id')
  const serial = need(options, 'certificate-serial')
  const out = need(options, 'out')
  const call = client()

  const certificates = await call('GET', `/v1/certificates?${query({ 'filter[certificateType]': 'DISTRIBUTION,IOS_DISTRIBUTION', limit: '200' })}`)
  const certificate = certificates.data.find((c) => sameSerial(c.attributes.serialNumber, serial))
  if (!certificate) die(`App Store Connect has no distribution certificate with serial ${serial}. Was it revoked? Create a new one with: node scripts/app-store-connect.mjs create-certificate`)

  const bundleIds = await call('GET', `/v1/bundleIds?${query({ 'filter[identifier]': bundleIdentifier, limit: '200' })}`)
  const bundle = bundleIds.data.find((b) => b.attributes.identifier === bundleIdentifier)
  if (!bundle) die(`The bundle id ${bundleIdentifier} isn't registered (Certificates, Identifiers & Profiles › Identifiers).`)

  // One profile per bundle id, kept up to date with the certificate CI signs with.
  const name = `${bundleIdentifier} App Store (CI)`
  const profiles = await call('GET', `/v1/profiles?${query({ 'filter[name]': name, include: 'certificates', limit: '50' })}`)
  let chosen = profiles.data.find((p) =>
    p.attributes.name === name &&
    p.attributes.profileState === 'ACTIVE' &&
    p.relationships?.certificates?.data?.some((c) => c.id === certificate.id))
  for (const stale of profiles.data.filter((p) => p.attributes.name === name && p !== chosen)) {
    await call('DELETE', `/v1/profiles/${stale.id}`)
  }
  if (!chosen) {
    chosen = (await call('POST', '/v1/profiles', {
      data: {
        type: 'profiles',
        attributes: { name, profileType: 'IOS_APP_STORE' },
        relationships: {
          bundleId: { data: { type: 'bundleIds', id: bundle.id } },
          certificates: { data: [{ type: 'certificates', id: certificate.id }] },
        },
      },
    })).data
  }
  writeFileSync(out, Buffer.from(chosen.attributes.profileContent, 'base64'))
  console.log(JSON.stringify({ name: chosen.attributes.name, uuid: chosen.attributes.uuid }))
}

async function createCertificate(options) {
  const out = need(options, 'out')
  const password = readFileSync(need(options, 'password-file'), 'utf8').trim()
  if (!password) die('The password file is empty.')
  const call = client()
  const dir = mkdtempSync(join(tmpdir(), 'asc-cert-'))
  try {
    const openssl = (...args) => execFileSync('openssl', args, { stdio: ['ignore', 'pipe', 'pipe'] })
    openssl('genrsa', '-out', join(dir, 'key.pem'), '2048')
    openssl('req', '-new', '-key', join(dir, 'key.pem'), '-subj', '/CN=DroidHands CI/C=US', '-out', join(dir, 'csr.pem'))
    const created = await call('POST', '/v1/certificates', {
      data: {
        type: 'certificates',
        attributes: { certificateType: 'DISTRIBUTION', csrContent: readFileSync(join(dir, 'csr.pem'), 'utf8') },
      },
    })
    writeFileSync(join(dir, 'cert.der'), Buffer.from(created.data.attributes.certificateContent, 'base64'))
    openssl('x509', '-inform', 'DER', '-in', join(dir, 'cert.der'), '-out', join(dir, 'cert.pem'))
    writeFileSync(join(dir, 'pass'), password, { mode: 0o600 })
    // 3DES and SHA-1 are what macOS's `security import` reads; OpenSSL 3's defaults aren't.
    openssl('pkcs12', '-export', '-inkey', join(dir, 'key.pem'), '-in', join(dir, 'cert.pem'), '-name', created.data.attributes.name,
      '-keypbe', 'PBE-SHA1-3DES', '-certpbe', 'PBE-SHA1-3DES', '-macalg', 'sha1', '-passout', `file:${join(dir, 'pass')}`, '-out', out)
    console.log(`Created "${created.data.attributes.name}" (serial ${created.data.attributes.serialNumber}, expires ${created.data.attributes.expirationDate}) and saved it to ${out}.`)
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
}

// Compares "1.2.3" version strings by number.
const semver = (v) => v.split('.').map(Number)
const newer = (a, b) => {
  const [x, y] = [semver(a), semver(b)]
  for (let i = 0; i < Math.max(x.length, y.length); i++) if ((x[i] ?? 0) !== (y[i] ?? 0)) return (x[i] ?? 0) > (y[i] ?? 0)
  return false
}
const newest = (versions) => versions.filter((v) => /^\d+(\.\d+)*$/.test(v)).reduce((best, v) => (!best || newer(v, best) ? v : best), '')

async function latestVersion(options) {
  const source = options.source ?? 'app-store'
  if (source !== 'app-store' && source !== 'uploads') die('--source must be app-store or uploads')
  const call = client()
  const app = await findApp(call, need(options, 'bundle-id'))
  let versions
  if (source === 'app-store') {
    const all = await call('GET', `/v1/apps/${app.id}/appStoreVersions?${query({ 'filter[platform]': 'IOS', limit: '200' })}`)
    versions = all.data.filter((v) => !EDITABLE.has(versionState(v))).map((v) => v.attributes.versionString)
  } else {
    const all = await call('GET', `/v1/preReleaseVersions?${query({ 'filter[app]': app.id, 'filter[platform]': 'IOS', limit: '200' })}`)
    versions = all.data.map((v) => v.attributes.version)
  }
  const found = newest(versions)
  if (found) console.log(found)
}

const COMMANDS = { submit, 'has-build': hasBuild, 'latest-version': latestVersion, profile, 'create-certificate': createCertificate }

export async function main(argv) {
  const { command, options } = parseArgs(argv)
  const run = COMMANDS[command]
  if (!run) die(`Usage: app-store-connect.mjs (${Object.keys(COMMANDS).join(' | ')}) [options]; see the top of this file.`)
  await run(options)
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  main(process.argv.slice(2)).catch((error) => {
    console.error(`✗ ${error instanceof Fail ? error.message : error.stack}`)
    process.exit(1)
  })
}
