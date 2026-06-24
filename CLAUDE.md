# Grimoire Extensions

Content-source extensions for the Grimoire reader. Each extension builds to its
own Android APK that the app loads dynamically and offers via an extension
browser backed by a generated `index.json`.

## Layout

- `src/{lang}/{name}/` — one extension per directory (e.g. `src/en/novelfull/`).
  `settings.gradle.kts` auto-includes any such dir with a `build.gradle.kts` as
  module `:{lang}-{name}`.
- `lib/` — shared base classes (`NovelFullThemeSource`, `WPNovelsSource`) that
  several extensions subclass. Also **published** to GitHub Packages as
  `io.grimoire:extensions-lib` for sibling extension repos (e.g. the private
  R18 repo); see "Publishing `lib/`" below. The extensions in *this* repo
  consume it as `project(":lib")` — only sibling repos consume the artifact.
- Model/network types come from the `grimoire-extensions-api` dependency.

## Versioning — bump on every change to an extension

When you change an extension's behaviour (parsing, fetching — anything that
should ship), you MUST bump its version. Each extension has two independent
version fields:

1. **`build.gradle.kts` → `versionCode` + `versionName`** — the critical one.
   `scripts/generate_index.py` reads these into `index.json`, so this is what
   the app's extension browser uses to detect and offer updates. It is also the
   APK `versionCode`, which Android requires to be higher to install over an
   existing install. Skip this and the change never reaches users.
2. **`@SourceInfo(versionCode = N)`** in the extension's Kotlin source — the
   source's declared version. Keep it bumped in step.

Rules:

- The two counters are independent and NOT equal across extensions (e.g.
  `novelfull` is gradle `versionCode` 10, `@SourceInfo` 5). Increment each by 1
  from its own current value.
- `versionName` always follows `1.0.(versionCode - 1)` — gradle `versionCode` 4
  means `versionName` `"1.0.3"`.
- Changing a shared base class in `lib/` affects every subclass: bump the
  version of every extension that subclasses it, or the change won't ship to
  them.

## Publishing `lib/`

`lib/` is published to GitHub Packages as `io.grimoire:extensions-lib` so
sibling extension repos can consume it as a Maven dependency. The contract
mirrors `grimoire-extensions-api`: a `lib-vX.Y.Z` git tag publishes the
immutable release; every other build publishes `<next>-SNAPSHOT`.

The published version is computed in `lib/build.gradle.kts` (`publishVersion`):

- No `LIB_RELEASE_TAG` env → `<base>-SNAPSHOT`. Every push to `main` publishes
  this mutable SNAPSHOT, for developing sibling repos against unreleased
  changes.
- `LIB_RELEASE_TAG` set → the concrete `X.Y.Z`. The **Publish lib/ to GitHub
  Packages** workflow sets it from a pushed `lib-vX.Y.Z` git tag.

### SemVer rules

Sibling repos build their extensions against this artifact, so the version
signals what they need to do on bump:

- **PATCH** — bug fix in an existing base class, no API surface change.
- **MINOR** — additive (new helper, new method on a base class). Existing
  subclasses keep compiling untouched.
- **MAJOR** — removed/renamed/retyped API on a shared base class, or any
  behavior change that requires subclasses to update. Sibling repos must
  rebuild their extensions and bump each extension's `versionCode`.

A `lib/` change does NOT need to bump this repo's extensions' versions for
*publishing* — they consume `project(":lib")` and rebuild from source. The
bump-every-subclass rule above still applies for *behavioral* changes that
should ship to users.

### To cut a release

1. Push a `lib-vX.Y.Z` tag on `main`:
   `git tag -a lib-vX.Y.Z <main-sha> -m "Release lib X.Y.Z" && git push origin lib-vX.Y.Z`
   This triggers the Publish workflow → immutable
   `io.grimoire:extensions-lib:X.Y.Z` on GitHub Packages.
2. Bump the `-SNAPSHOT` base (`publishVersion` in `lib/build.gradle.kts`) to
   the next version.
3. Pin sibling repos to the new concrete version in their
   `gradle/libs.versions.toml`.

## CI

PR builds and releases run via reusable workflows hosted in
[`operation-grimoire/extensions-ci`](https://github.com/Operation-Grimoire/extensions-ci),
pinned to `@v2`. `.github/workflows/pr-build.yml` and `release.yml` are thin
stubs that delegate; the Python scripts that detect changed extensions and
generate `index.json` live in extensions-ci. To change build behavior shared
across all sibling repos, edit extensions-ci and move the `v2` tag (or cut a new
`v2.x.y`); to change repo-specific behavior, edit the stubs here. `index.json`
carries each source's `adultContent` rating and `novelUpdatesGroups`, emitted by
the generator from `@SourceInfo`.
