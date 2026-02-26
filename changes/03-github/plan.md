# GitHub Actions CI/CD — Plan

## Overview

Two GitHub Actions workflows will be added:

1. **`ci.yml`** — Triggered on every pull request targeting `main`. Runs unit tests always;
   runs integration tests when Sentry secrets are available (same-repo PRs).
2. **`release.yml`** — Triggered when a GitHub Release is published (via a `v*.*.*` tag).
   Sets the Maven version from the tag, runs the full test suite (unit + integration), then
   uploads the built ZIP as a release asset.

Additionally, one small bug fix is required in `SentryReporterIT.java`: the Sentry release
string sent to the gateway container is hardcoded as `"1.0.0-SNAPSHOT"` but the `pluginVersion`
variable (already resolved from `System.getProperty("project.version")` on the same line) is
available and must be used instead.

---

## Files to Create / Modify

### New files
- `.github/workflows/ci.yml`
- `.github/workflows/release.yml`

### Modified files
- `src/test/java/io/gravitee/reporter/sentry/integration/SentryReporterIT.java`
  — line 183: replace hardcoded `"1.0.0-SNAPSHOT"` with `pluginVersion`

---

## Required GitHub Secrets

All four must be configured as repository secrets
(`Settings → Secrets and variables → Actions → New repository secret`):

| Secret name           | Value                                   |
| --------------------- | --------------------------------------- |
| `SENTRY_DSN`          | Sentry project DSN (ingest key)         |
| `SENTRY_TEST_TOKEN`   | Sentry auth token (with `project:read`) |
| `SENTRY_TEST_ORG`     | Sentry organisation slug                |
| `SENTRY_TEST_PROJECT` | Sentry project slug                     |

---

## Version Management Strategy

Maven version is derived from the Git tag at release time. The convention is:

- Git tag: `v1.2.0`
- Maven version: `1.2.0` (strip the leading `v`)
- ZIP artefact: `gravitee-reporter-sentry-1.2.0.zip`

In the release workflow, before building:
```
mvn --batch-mode versions:set -DnewVersion=${VERSION} -DgenerateBackupPoms=false
```
This updates `pom.xml` in the ephemeral CI workspace only (no commit is made).
The failsafe plugin already forwards `${project.version}` to the integration test JVM as the
`project.version` system property, so `pluginVersion` in `SentryReporterIT` will automatically
resolve to the release version.

---

## Workflow 1 — Pull Request CI (`.github/workflows/ci.yml`)

### Trigger
```yaml
on:
  pull_request:
    branches: [main]
```

### Jobs

#### Job: `unit-tests`
- Runner: `ubuntu-latest`
- Steps:
  1. `actions/checkout@v4`
  2. `actions/setup-java@v4` — distribution `temurin`, Java 17, cache `maven`
  3. `mvn --batch-mode verify` — runs surefire unit tests; failsafe is not activated (no
     `-Pintegration-test`); assembly ZIP is produced as a side-effect of `package`

#### Job: `integration-tests`
- Runner: `ubuntu-latest`
- `needs: [unit-tests]` — only runs if unit tests pass
- `if` condition: check whether `SENTRY_DSN` secret is set.
  GitHub Actions does not allow secrets in `if` expressions directly, so the standard
  workaround is used: assign the secret to an env var in the step and check it there.
  The job itself uses:
  ```yaml
  env:
    HAS_SENTRY: ${{ secrets.SENTRY_DSN != '' && 'true' || 'false' }}
  ```
  Then the integration-test step is guarded with `if: env.HAS_SENTRY == 'true'`.
  This means PRs from forks (where secrets are unavailable) skip the integration step
  without failing the workflow.
- Steps:
  1. `actions/checkout@v4`
  2. `actions/setup-java@v4` — temurin 17, cache maven
  3. Run integration tests (guarded by secret check):
     ```
     mvn --batch-mode verify -Pintegration-test
     ```
     Environment variables passed to the step:
     - `SENTRY_DSN: ${{ secrets.SENTRY_DSN }}`
     - `SENTRY_TEST_TOKEN: ${{ secrets.SENTRY_TEST_TOKEN }}`
     - `SENTRY_TEST_ORG: ${{ secrets.SENTRY_TEST_ORG }}`
     - `SENTRY_TEST_PROJECT: ${{ secrets.SENTRY_TEST_PROJECT }}`

  The pom.xml profile already maps `${env.SENTRY_*}` to Maven properties and forwards them
  as JVM system properties to failsafe, so no additional configuration is needed.

---

## Workflow 2 — Release (`.github/workflows/release.yml`)

### Trigger
```yaml
on:
  release:
    types: [published]
```
Using the `release` event (rather than `push: tags`) ensures the GitHub Release object exists
before the workflow runs, making it straightforward to upload the ZIP as a release asset with
`gh release upload`.

### Version extraction
```yaml
- name: Extract version
  id: version
  run: |
    TAG="${{ github.event.release.tag_name }}"
    VERSION="${TAG#v}"   # strip leading 'v'
    echo "version=${VERSION}" >> $GITHUB_OUTPUT
```

### Jobs

#### Job: `release`
- Runner: `ubuntu-latest`
- Steps:
  1. `actions/checkout@v4`
  2. `actions/setup-java@v4` — temurin 17, cache maven
  3. Extract version (as above)
  4. Set Maven version:
     ```
     mvn --batch-mode versions:set -DnewVersion=${{ steps.version.outputs.version }} \
         -DgenerateBackupPoms=false
     ```
  5. Run unit tests + build ZIP:
     ```
     mvn --batch-mode verify
     ```
     This runs surefire (unit tests) and produces the plugin ZIP via the assembly plugin.
  6. Run integration tests:
     ```
     mvn --batch-mode failsafe:integration-test failsafe:verify -Pintegration-test
     ```
     Using the failsafe goals directly avoids re-running surefire and re-packaging.
     Environment variables:
     - `SENTRY_DSN: ${{ secrets.SENTRY_DSN }}`
     - `SENTRY_TEST_TOKEN: ${{ secrets.SENTRY_TEST_TOKEN }}`
     - `SENTRY_TEST_ORG: ${{ secrets.SENTRY_TEST_ORG }}`
     - `SENTRY_TEST_PROJECT: ${{ secrets.SENTRY_TEST_PROJECT }}`
  7. Upload ZIP to the GitHub Release:
     ```
     gh release upload ${{ github.event.release.tag_name }} \
       target/gravitee-reporter-sentry-${{ steps.version.outputs.version }}.zip \
       --clobber
     ```
     `--clobber` overwrites if the asset already exists (safe for re-runs).
     Requires `GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}` to be set on the job
     (or the step's `env`).

---

## Integration Test Bug Fix

**File**: `SentryReporterIT.java`, line 183

Current:
```java
.withEnv("gravitee_reporters_sentry_release", "1.0.0-SNAPSHOT")
```

Fix:
```java
.withEnv("gravitee_reporters_sentry_release", pluginVersion)
```

`pluginVersion` is already defined at line 116 as
`System.getProperty("project.version", "1.0.0-SNAPSHOT")`, which the failsafe plugin populates
from `${project.version}` — resolving to the release version in CI and to the local snapshot
version during development.

---

## Notes

- **Docker availability**: GitHub-hosted `ubuntu-latest` runners include Docker and have the
  Docker socket available; Testcontainers works without any extra setup.
- **Maven wrapper**: The project does not currently include a Maven wrapper (`mvnw`). The
  workflows will invoke `mvn` directly using the Maven version bundled with the runner image.
  If `mvnw` is added in the future the workflow commands should be updated accordingly.
- **No version commit**: The `mvn versions:set` step in the release workflow mutates `pom.xml`
  only in the ephemeral workspace. A version commit is not needed; the tag itself is the
  authoritative version record.
- **Re-runnable releases**: Using `--clobber` on the upload step and the per-run API UUID
  in integration tests means the release workflow is safely re-runnable if it fails mid-way.
- **Branch protection**: To enforce CI on PRs, configure a branch protection rule on `main`
  requiring the `unit-tests` job to pass.
