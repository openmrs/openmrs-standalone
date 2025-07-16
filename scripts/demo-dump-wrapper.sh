#!/bin/bash
DUMP_BIN="$1/demodatabase/bin/mariadb-dump"
FALLBACK_BIN="$1/demodatabase/bin/mysqldump"

if [ -x "$DUMP_BIN" ]; then
  exec "$DUMP_BIN" --user=root --password= --port=33328 openmrs --result-file="$1/openmrs-demo-dump.sql"
elif [ -x "$FALLBACK_BIN" ]; then
  exec "$FALLBACK_BIN" --user=root --password= --port=33328 openmrs --result-file="$1/openmrs-demo-dump.sql"
else
  echo "❌ No valid dump binary found" >&2
  exit 1
fi
