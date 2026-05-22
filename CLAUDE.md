# Grimoire Extensions

Content-source extensions for the Grimoire reader. Each extension builds to its
own Android APK that the app loads dynamically and offers via an extension
browser backed by a generated `index.json`.

## Layout

- `src/{lang}/{name}/` — one extension per directory (e.g. `src/en/novelfull/`).
  `settings.gradle.kts` auto-includes any such dir with a `build.gradle.kts` as
  module `:{lang}-{name}`.
- `lib/` — shared base classes (`NovelFullThemeSource`, `WPNovelsSource`) that
  several extensions subclass.
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
