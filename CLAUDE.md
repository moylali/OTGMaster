# OTG Master — working agreements

## Commit rules

These apply to every commit, without being asked.

1. **Update the Fastlane changelog with each commit.** The file is
   `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`, where
   `versionCode` is the current value in `app/build.gradle.kts`. It accumulates
   **all user-facing changes since the last tagged commit**, not just the change
   being committed — so each commit rewrites the whole file rather than appending
   one line. Check `git log <last-tag>..HEAD` to rebuild it.

   **The version must be bumped immediately after a tag**, so that value always
   names an *unreleased* build. This rule was followed literally while the version
   still said 44 after `v0.3.11` had shipped at 44, and a changelog describing
   unreleased work was written into the released version's file — where F-Droid and
   Play would have shown it against a build that did not contain any of it. Before
   editing a changelog, confirm `git describe --tags --abbrev=0` does **not** match
   the current `versionName`.

   Keep the established voice: `•` bullets, `New:` / `Fixed:` prefixes, phrased
   for an end user. Internal work (CI, refactors, benchmarks, docs) does not
   appear there.

   **Two files, two audiences.** F-Droid reads the Fastlane changelog above and has
   no length limit. Google Play caps release notes at **500 characters per
   language**, so it gets a separate condensed file at
   `distribution/whatsnew/whatsnew-en-US`, wired into `release.yml` via
   `whatsNewDirectory`. Update both, and check the Play one's length —
   `wc -c distribution/whatsnew/whatsnew-en-US` must be ≤ 500, or the upload is
   rejected.

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

`docs/VENDOR_FIXES.md` is the registry of every vendored dependency. Nothing is
vendored without an entry there.

**Adding a library.** Before the vendoring commit lands, add a section recording:

- the upstream repository URL;
- the exact upstream commit pinned, full SHA, not a tag or a branch;
- the date pinned, and the upstream version if it has one;
- **why** it is vendored rather than consumed as a dependency — this is the part
  that gets lost, and without it a later maintainer cannot judge whether the reason
  still holds;
- the licence, and whether it is compatible with this project's GPL-2.0-or-later
  grant (the licence table in `README.md` is the authoritative list — add the new
  entry there, and reference it from the registry rather than restating it);
- an empty patch table, ready for the first local change.

The commit that brings the code in states the same pin in its message, so the
pairing is visible from `git log` alone without opening the doc.

**Patching a vendored library.** Record it in that library's patch table (what,
why, and the commit), and mark the code itself:

```kotlin
// LOCAL PATCH (docs/VENDOR_FIXES.md V3): one line on what upstream does wrong.
```

The in-file marker matters more than it looks: it is what stops a future upstream
merge from silently reverting a fix. A patch with no marker will be lost.

**Pulling upstream.** Follow the shape of `b03705d`: name the old and new commits,
summarise the source changes that actually affect this project, and state explicitly
for each local patch whether it still applies, was absorbed upstream, or had to be
re-applied. Update the pin in the registry in the same commit.
