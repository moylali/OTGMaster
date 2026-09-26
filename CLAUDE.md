# OTG Master — working agreements

## Commit rules

These apply to every commit, without being asked.

1. **Update the Fastlane changelog with each commit.** The file is
   `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`, where
   `versionCode` is the current value in `app/build.gradle.kts`. It accumulates
   **all user-facing changes since the last tagged commit**, not just the change
   being committed — so each commit rewrites the whole file rather than appending
   one line. Check `git log <last-tag>..HEAD` to rebuild it.

   Keep the established voice: `•` bullets, `New:` / `Fixed:` prefixes, phrased
   for an end user. Internal work (CI, refactors, benchmarks, docs) does not
   appear there.

2. **Every commit message carries a summary of changes.** A subject line alone is
   not enough. State what changed, and why — including the reasoning or evidence
   that justified it, so the commit stands on its own later.

3. **A tagging commit must attach a regression test report covering at least four
   devices.** No tag ships without it. Reports live in `docs/test-reports/` as
   `<tag>.md`, and each must name the device, Android version, filesystem, and the
   measurements or checks that were run.

## Constraints

- **Do not push to mainline** unless explicitly asked. Commit to the working
  branch.
- Unless a tag is created, nothing reaches F-Droid or Google Play, so landing
  fixes on `main` is safe; tagging is the gate.

## Destructive commands

Never hand the user a destructive command bundled with a read-only one, and never
in the same code block. `scripts/prepare_test_usb.sh` repartitions a disk; keep it
visually separated from `verify`/`clean`, and prefer running it yourself over
giving it to the user to paste.

## Vendored code

`libaums/` is a vendored fork with an upstream pin, patched in-tree when needed
(see `docs/VENDOR_FIXES.md`). Patches there must be recorded in that document so a
future upstream pull knows what to preserve.
