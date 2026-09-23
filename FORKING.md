# Mihon Discover fork policy

Keep this fork straightforward to rebase onto `mihonapp/mihon`.

- Place Discover code under `app/src/main/java/mihon/discover` and keep it independent from
  upstream packages.
- Keep integrations with Mihon to small adapters or composable calls. Do not put Discover
  business logic in upstream view models, repositories, or domain modules.
- Persist Discover-only data in the feature's private store. Do not add tables or migrations to
  Mihon's SQLDelight database unless an upstream-compatible contract makes that unavoidable.
- Use existing Gradle properties for fork build policy rather than changing shared build logic.
- Before feature work, fetch `upstream/main`, rebase this branch, and resolve integration changes
  at the narrow adapter boundaries.
