"""Refuse a NOT NULL column added to a table that already has rows somewhere.

## The bug this exists for

V20 added `candidate_source` to `recommendation_items` as `NOT NULL` with no
default. Against a clean database that is always fine -- there are no rows to
violate the constraint -- so it passed every check available. Against the
development database, which had 69 rows from ordinary use, it failed instantly
with `23502`.

**CI could not have caught it.** The migrations job applies every file to a fresh
Postgres, which is exactly the condition under which this mistake is invisible.
So the shape passes forever and fails the first time it meets a populated
database, which on any normal trajectory is production, shortly after the first
real users.

## The rule

`ALTER TABLE ... ADD COLUMN ... NOT NULL` with no `DEFAULT` is refused **unless
the table is created in the same migration file**, in which case it is empty by
construction and the constraint is trivially satisfiable. Several early
migrations do exactly that -- create a table, then add a Postgres-only column
type behind a `[jooq ignore]` fence -- and those are correct.

The safe pattern for an existing table is three statements: add the column
nullable, backfill it with something that is true rather than convenient, then
`SET NOT NULL`. Whether to add a `DEFAULT` afterwards is a separate decision;
leaving it off forces every writer to state a value, which is usually what you
want for a column that exists to be measured.
"""

from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
MIGRATIONS = ROOT / "plotted-api" / "src" / "main" / "resources" / "db" / "migration"

# `ALTER TABLE <name> ADD COLUMN`, allowing the statement to wrap across lines.
ALTER_ADD = re.compile(
    r"ALTER\s+TABLE\s+(?:ONLY\s+)?([a-z_][a-z0-9_]*)\s+ADD\s+COLUMN\s+(.*?);",
    re.IGNORECASE | re.DOTALL,
)
CREATE_TABLE = re.compile(r"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?([a-z_][a-z0-9_]*)", re.IGNORECASE)


def strip_comments(sql: str) -> str:
    """Drop `--` lines and `/* */` blocks so commentary cannot trip the patterns."""
    sql = re.sub(r"/\*.*?\*/", " ", sql, flags=re.DOTALL)
    return "\n".join(line.split("--")[0] for line in sql.splitlines())


def main() -> int:
    problems: list[str] = []

    for path in sorted(MIGRATIONS.glob("V*.sql")):
        sql = strip_comments(path.read_text(encoding="utf-8"))
        created_here = {name.lower() for name in CREATE_TABLE.findall(sql)}

        for table, definition in ALTER_ADD.findall(sql):
            if table.lower() in created_here:
                # Empty by construction at this point in the same file.
                continue
            flat = " ".join(definition.split())
            if not re.search(r"\bNOT\s+NULL\b", flat, re.IGNORECASE):
                continue
            if re.search(r"\bDEFAULT\b", flat, re.IGNORECASE):
                continue
            problems.append(f"{path.name}: ALTER TABLE {table} ADD COLUMN {flat}")

    if problems:
        print("Migrations that would fail against a database with rows in it:\n")
        for problem in problems:
            print(f"  {problem}")
        print(
            "\nThis passes CI because the migrations job runs against a clean database,"
            "\nwhere a NOT NULL column with no default is always satisfiable. It fails the"
            "\nfirst time it meets a populated one.\n"
            "\nAdd the column nullable, backfill it with something true, then SET NOT NULL."
            "\nIf the table really is empty everywhere, verify that rather than assume it —"
            "\nthe assumption is what produced this check.",
        )
        return 1

    print(f"Checked {len(list(MIGRATIONS.glob('V*.sql')))} migrations: no NOT NULL column added to a populated table.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
