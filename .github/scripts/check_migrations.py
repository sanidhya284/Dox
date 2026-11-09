#!/usr/bin/env python3
"""Migrations are additive only (expand/contract), so a rollback still works against a newer schema (ADR-0011).

Two rules, checked in CI on every PR and push:
  1. An existing migration is never modified, deleted or renamed (Flyway checksums; CLAUDE.md rule 1).
  2. A NEW migration (version above the baseline V6) contains nothing the previous release can't live with:
     no DROP, no RENAME, no column type change, no SET NOT NULL, no TRUNCATE / DELETE, and no NOT NULL column
     without a DEFAULT (the previous release would fail to insert rows). A deliberate CONTRACT step (removing
     what no deployed or rollback-target release still uses) must say so on its own line:
         -- contract: <why it's safe: which release stopped using it>
     and then passes. Review such PRs with the runbook's contract checklist.

    check_migrations.py --dir backend/src/main/resources/db/migration --base <git-ref-or-sha>
    check_migrations.py --self-test
"""
import argparse
import os
import re
import subprocess
import sys

BASELINE = 6  # V1..V6 existed when the rule was introduced (2026-11-09); they are frozen, not re-checked.
NAME = re.compile(r"^V(\d+)__.+\.sql$")
RULES = [
    (r"\bDROP\s+(TABLE|COLUMN|SCHEMA|VIEW|MATERIALIZED\s+VIEW|TYPE|SEQUENCE|FUNCTION|TRIGGER|INDEX|CONSTRAINT)\b", "DROP"),
    (r"\bALTER\s+TABLE\b[^;]*\bDROP\b", "ALTER TABLE ... DROP"),
    (r"\bRENAME\b", "RENAME"),
    (r"\bALTER\s+COLUMN\b[^;]*\bTYPE\b", "column type change"),
    (r"\bALTER\s+COLUMN\b[^;]*\bSET\s+NOT\s+NULL\b", "SET NOT NULL on an existing column"),
    (r"\bTRUNCATE\b", "TRUNCATE"),
    (r"\bDELETE\s+FROM\b", "DELETE FROM"),
]
ADD_NOT_NULL = re.compile(r"\bADD\s+(?:COLUMN\s+)?(?:IF\s+NOT\s+EXISTS\s+)?\w+[^,;]*\bNOT\s+NULL\b[^,;]*", re.I)
CONTRACT = re.compile(r"^\s*--\s*contract:\s*\S", re.I | re.M)


def strip_comments(sql):
    sql = re.sub(r"/\*.*?\*/", " ", sql, flags=re.S)
    return re.sub(r"--[^\n]*", " ", sql)


def violations(sql):
    """Problems in one migration's SQL; empty if additive (or explicitly marked as a contract step)."""
    if CONTRACT.search(sql):
        return []
    body = strip_comments(sql)
    found = []
    for statement in body.split(";"):
        for pattern, label in RULES:
            if re.search(pattern, statement, re.I):
                found.append(f"{label}: {' '.join(statement.split())[:100]}")
        for m in ADD_NOT_NULL.finditer(statement):
            if not re.search(r"\bDEFAULT\b", m.group(0), re.I):
                found.append(f"NOT NULL column without DEFAULT: {' '.join(m.group(0).split())[:100]}")
    return found


def changed_existing(directory, base):
    """Existing migration files modified, deleted or renamed since `base` (rule 1)."""
    out = subprocess.run(["git", "diff", "--name-status", "--diff-filter=MDR", base, "HEAD", "--", directory],
                         capture_output=True, text=True, check=True).stdout
    return [line for line in out.splitlines() if line.strip()]


def self_test():
    bad = {
        "drop column": "ALTER TABLE users DROP COLUMN email;",
        "drop table": "DROP TABLE ws_tickets;",
        "rename": "ALTER TABLE messages RENAME COLUMN body TO text;",
        "type change": "ALTER TABLE messages ALTER COLUMN body TYPE varchar(10);",
        "set not null": "ALTER TABLE users ALTER COLUMN display_name SET NOT NULL;",
        "not null without default": "ALTER TABLE users ADD COLUMN locale text NOT NULL;",
        "delete": "DELETE FROM refresh_tokens;",
        "truncate": "TRUNCATE messages;",
        "second statement": "ALTER TABLE users ADD COLUMN bio text; DROP INDEX idx_x;",
    }
    good = {
        "add nullable column": "ALTER TABLE users ADD COLUMN bio text;",
        "add not null with default": "ALTER TABLE users ADD COLUMN locale text NOT NULL DEFAULT 'en';",
        "new table": "CREATE TABLE notes (id uuid PRIMARY KEY, body text NOT NULL);",
        "index": "CREATE INDEX idx_messages_created ON messages (created_at);",
        "backfill": "UPDATE users SET bio = '' WHERE bio IS NULL;",
        "keyword in a comment": "-- we never DROP anything here\nALTER TABLE users ADD COLUMN x int;",
        "marked contract step": "-- contract: release sha-abc stopped reading users.legacy (2026-12-01)\n"
                                "ALTER TABLE users DROP COLUMN legacy;",
    }
    ok = True
    for name, sql in bad.items():
        if not violations(sql):
            print(f"SELF-TEST FAILED: '{name}' was not flagged"); ok = False
    for name, sql in good.items():
        if violations(sql):
            print(f"SELF-TEST FAILED: '{name}' was flagged: {violations(sql)}"); ok = False
    print(f"self-test: {len(bad)} destructive samples flagged, {len(good)} additive samples passed" if ok else "")
    return ok


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", default="backend/src/main/resources/db/migration")
    ap.add_argument("--base", help="git ref or sha to diff against (rule 1); omit to skip rule 1")
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args()
    if a.self_test:
        sys.exit(0 if self_test() else 1)

    problems = []
    if a.base and not re.fullmatch(r"0+", a.base):
        for line in changed_existing(a.dir, a.base):
            problems.append(f"existing migration changed (never edit an applied migration): {line}")
    checked = 0
    for f in sorted(os.listdir(a.dir)):
        m = NAME.match(f)
        if not m or int(m.group(1)) <= BASELINE:
            continue
        checked += 1
        for v in violations(open(os.path.join(a.dir, f)).read()):
            problems.append(f"{f}: {v}")
    if problems:
        print("Migration rule violated (expand/contract only; see ADR-0011 Decision 10):")
        for p in problems:
            print("  - " + p)
        print("A deliberate contract step needs a line '-- contract: <why it's safe>' in that migration.")
        sys.exit(1)
    print(f"migrations OK: {checked} new migration(s) above V{BASELINE} checked; no existing migration changed")


if __name__ == "__main__":
    main()
