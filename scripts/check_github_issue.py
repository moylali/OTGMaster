#!/usr/bin/env python3
"""Confirms a Report issue submission reached GitHub, then deletes that issue.

    python3 scripts/check_github_issue.py REPO MARKER [--wait SECONDS]

MARKER is the random string the E2E case typed into the description. Only an issue whose
body contains it is ever deleted, so real reports in the same repository are safe. Every
issue found is deleted even when a check fails, so a failed case leaves nothing public.

Prints "GITHUB ISSUE OK" on success. Needs `gh` logged in with admin rights on REPO.
"""

import argparse
import json
import re
import subprocess
import sys
import time

POLL_SECONDS = 5


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
    return [
        issue
        for issue in json.loads(out.stdout)
        if "pull_request" not in issue and marker in (issue.get("body") or "")
    ]


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

    problems = []
    if len(issues) != 1:
        numbers = ", ".join(f"#{issue['number']}" for issue in issues)
        problems.append(f"expected one issue, found {len(issues)}: {numbers}")
    for issue in issues:
        problems += [
            f"#{issue['number']}: {p}" for p in problems_in(issue["body"], args.marker)
        ]
        error = delete(args.repo, issue["number"])
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
