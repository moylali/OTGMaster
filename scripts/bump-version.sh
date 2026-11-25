#!/usr/bin/env bash
# Bumps the app version ahead of cutting a release.
#
# versionCode is auto-incremented by 1 (it only needs to be unique and increasing,
# never reused or recomputed — see app/build.gradle.kts for why this is a static
# literal rather than computed at build time).
# versionName is a semantic version string you choose, since the major/minor/patch
# decision is a judgment call this script shouldn't make for you.
#
# Usage: scripts/bump-version.sh 0.2.3
# Then:  git tag v0.2.3 && git push origin main v0.2.3

set -euo pipefail

# --container bumps Container Master (container-app/, tags container-vX.Y.Z) instead of
# OTG Master (app/, tags vX.Y.Z); each app keeps its own versionCode sequence.
module=app; tag_prefix=v
if [ "${1:-}" = "--container" ]; then module=container-app; tag_prefix=container-v; shift; fi

if [ $# -ne 1 ]; then
    echo "Usage: $0 [--container] <new-version-name>" >&2
    echo "Example: $0 0.2.3" >&2
    exit 1
fi

new_version_name="$1"
gradle_file="$(dirname "$0")/../${module}/build.gradle.kts"

current_code=$(grep -oP 'versionCode = \K\d+' "$gradle_file")
new_code=$((current_code + 1))

sed -i \
    -e "s/versionCode = ${current_code}/versionCode = ${new_code}/" \
    -e "s/versionName = \"[^\"]*\"/versionName = \"${new_version_name}\"/" \
    "$gradle_file"

echo "Bumped versionCode ${current_code} -> ${new_code}, versionName -> ${new_version_name}"
echo "Review the diff, then:"
echo "  git add ${module}/build.gradle.kts"
echo "  git commit -m \"chore: bump ${module} to ${new_version_name} (${new_code})\""
echo "  git tag ${tag_prefix}${new_version_name}"
echo "  git push origin main ${tag_prefix}${new_version_name}"
