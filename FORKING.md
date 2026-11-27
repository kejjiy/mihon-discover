# Mihon Discover fork policy

Keep this fork straightforward to rebase onto `mihonapp/mihon`.

- Place Discover code under `app/src/main/java/mihon/discover` and keep it independent from
  upstream packages.
- Keep platform-free scoring in `discover/engine`; map Mihon and AniList data at the app boundary.
- Keep integrations with Mihon to small adapters or composable calls. Do not put Discover
  business logic in upstream view models, repositories, or domain modules.
- Persist Discover-only data in the feature's private store. Do not add tables or migrations to
  Mihon's SQLDelight database unless an upstream-compatible contract makes that unavoidable.
- Use existing Gradle properties for fork build policy rather than changing shared build logic.
- Integrate stable tags from `mihonapp/mihon` into the published
  `feature/local-recommendations` branch with a merge. Preserve its history and
  resolve integration changes at the narrow adapter boundaries.
- Keep the application ID and signing certificate across Android updates, and
  advance the Android version code. Verify Android builds, Discover tests, and
  protocol compatibility with Windows before publishing an APK.
- The Codex maintenance task watches stable upstream releases and prepares an
  integration PR after checks pass. Review data migrations and approve releases
  separately.
