## What this changes

<!-- One or two sentences. Link the issue it closes, e.g. "Closes #123". -->

## App plugin checklist

<!-- Only for pull requests that add or change plugins/<id>/plugin.yaml. Delete this section otherwise. -->

- [ ] `./scripts/plugin-check.sh plugins/<id>` passes (format, security rules and examples).
- [ ] `./scripts/plugin-table.sh` was run, so `plugins/README.md` lists the plugin.
- [ ] Every action has a clear `title` and a `description` that says what it does **and what it doesn't** (for the agent).
- [ ] Every action has `examples`, including one with a required parameter left out (`expect: {error: ...}`).
- [ ] Risks are right: `read` only reads, `action` opens or plays something, `sensitive` sends, posts, pays, orders,
      books, calls or deletes. `completes: false` where someone still has to finish on the screen.
- [ ] Recipes use the simplest thing that works: a built-in `tool`, then an `intent`, a `url`, and `steps` only
      when nothing else does.
- [ ] Sources: how I know the deep links / intents work (app docs, `./scripts/plugin-scaffold.sh` output, ...):
      <!-- links or a short note -->
- Tested on a device:
  - [ ] Yes: app version ______, Android ______ (consider adding a `tested` record)
  - [ ] Not yet (the plugin stays `experimental`)

## Code checklist

<!-- For changes outside plugins/. Delete what doesn't apply. -->

- [ ] Tests pass: `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug` (in `clients/android`) and/or
      `npm test` (in `relay`).
- [ ] New pure logic has unit tests.
- [ ] Security still fails closed: scopes, app access, approvals, pause / revoke stop checks and the audit log
      behave as before (docs/SECURITY.md).
- [ ] Docs updated (docs/TOOLS.md, docs/PLUGINS.md, ...) if agents or users see a difference.
