#!/usr/bin/env python3
"""Confirms a Report issue submission reached GitHub, then deletes that issue.

    python3 scripts/check_github_issue.py REPO MARKER [--wait SECONDS]

MARKER is the random string the E2E case typed into the description: "otg-e2e-" and 32
hex digits, as run_e2e_tests.sh makes it. It fails closed (review of #141): a marker of any
other shape is refused before anything is read or deleted; an issue matches only if its
Description section holds the marker as a whole word and it was created in the last
MAX_AGE_MINUTES; and nothing is deleted unless exactly one issue matches, so an empty or
reused marker can never sweep up real reports. A single matching issue is deleted even when
another check fails, so a failed case leaves nothing public.

Prints "GITHUB ISSUE OK" on success. Needs `gh` logged in with admin rights on REPO.
"""

import argparse
import calendar
import json
import re
import subprocess
import sys
import time

POLL_SECONDS = 5
MAX_AGE_MINUTES = 30
MARKER_RE = re.compile(r"^otg-e2e-[0-9a-f]{32}$")


def gh(*args):
    return subprocess.run(
        ["gh", *args], capture_output=True, text=True, timeout=60, check=False
    )


def find_issues(repo, marker):
    # The issues list rather than the search API: search is indexed late and can miss a
    # new issue for minutes.
    out = gh(
        "api", f"repos/{repo}/issues?state=all&sort=created&direction=desc&per_page=50"
    )
    if out.returncode != 0:
        print("gh api failed:", out.stderr.strip())
        return []
    return matching(json.loads(out.stdout), marker, time.time())


def matching(issues, marker, now):
    """The issues (not PRs) created in the last MAX_AGE_MINUTES whose Description section
    holds [marker] as a whole word."""
    newest = now - MAX_AGE_MINUTES * 60
    token = re.compile(r"(?<![\w-])" + re.escape(marker) + r"(?![\w-])")
    found = []
    for issue in issues:
        if "pull_request" in issue:
            continue
        section = re.search(r"### Description\r?\n(.*?)(?=\r?\n### |\Z)", issue.get("body") or "", re.DOTALL)
        if section is None or not token.search(section.group(1)):
            continue
        created = calendar.timegm(time.strptime(issue["created_at"], "%Y-%m-%dT%H:%M:%SZ"))
        if created >= newest:
            found.append(issue)
    return found


def problems_in(body, marker):
    found = []
    section = re.search(r"### Description\r?\n(.*?)(?=\r?\n### |\Z)", body, re.DOTALL)
    if section is None:
        found.append("no Description section")
    elif marker not in section.group(1):
        found.append("the marker is not in the Description section")
    if not re.search(r"\*\*App Version:\*\* \S", body):
        found.append("no app version from the app")
    if not re.search(r"\*\*Device:\*\* \S", body):
        found.append("no device from the app")
    if "### Logs" not in body:
        found.append("no logs from the app")
    return found


def delete(repo, number):
    out = gh("issue", "delete", str(number), "-R", repo, "--yes")
    if out.returncode != 0:
        return f"delete of #{number} failed: {out.stderr.strip()}"
    after = gh("api", f"repos/{repo}/issues/{number}")
    if after.returncode == 0 or not re.search(r"HTTP 4(04|10)", after.stderr):
        return f"#{number} is still there after the delete"
    return None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("repo")
    parser.add_argument("marker")
    parser.add_argument("--wait", type=int, default=120)
    args = parser.parse_args()
    if not MARKER_RE.match(args.marker):
        print(f"GITHUB ISSUE FAILED: refusing marker {args.marker!r}: not otg-e2e- and 32 hex digits")
        return 2

    deadline = time.monotonic() + args.wait
    while True:
        issues = find_issues(args.repo, args.marker)
        if issues or time.monotonic() >= deadline:
            break
        time.sleep(POLL_SECONDS)

    if not issues:
        print(
            f"GITHUB ISSUE FAILED: no issue contains {args.marker} after {args.wait} s"
        )
        return 1

    if len(issues) != 1:
        numbers = ", ".join(f"#{issue['number']}" for issue in issues)
        print(f"GITHUB ISSUE FAILED: expected one issue, found {len(issues)} ({numbers}); "
              "none deleted - check them by hand")
        return 1
    problems = [f"#{issues[0]['number']}: {p}" for p in problems_in(issues[0]["body"], args.marker)]
    error = delete(args.repo, issues[0]["number"])
    if error:
        problems.append(error)

    if problems:
        print("GITHUB ISSUE FAILED: " + "; ".join(problems))
        return 1
    print(
        f"GITHUB ISSUE OK: #{issues[0]['number']} had {args.marker}, and it is deleted"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
