#!/usr/bin/env bash
set -euo pipefail
if [[ "$#" -ne 5 || "$5" != "false" ]]; then
  echo "FTP clean-before-download is forbidden" >&2
  exit 64
fi
SG_URI="$1"
EXPECTED_SHA="$2"
STAGING_DIR="$3"
FINAL_NAME="$4"
ROOT="${ARCHIVE_FTP_ROOT:-/srv/maas-sftp/maasData/archive}"
if [[ "$SG_URI" != oss://* || ! "$EXPECTED_SHA" =~ ^[0-9a-f]{64}$ ]]; then
  echo "Invalid OSS URI or SHA-256" >&2
  exit 64
fi
if [[ ! "$FINAL_NAME" =~ ^batch_[0-9]{5}\.tar\.gz$ || "$STAGING_DIR" != "$ROOT/.archive-mvp-staging/"* || "$STAGING_DIR" == *".."* ]]; then
  echo "Invalid staging path or package name" >&2
  exit 64
fi
FINAL_FILE="$STAGING_DIR/$FINAL_NAME"
if [[ -f "$FINAL_FILE" ]]; then
  if [[ "$(sha256sum "$FINAL_FILE" | cut -d' ' -f1)" == "$EXPECTED_SHA" ]]; then
    exit 0
  fi
  echo "Staging file conflicts with an existing different SHA" >&2
  exit 65
fi
mkdir -p -- "$STAGING_DIR"
PARTIAL="$STAGING_DIR/$FINAL_NAME.partial"
ossutil64 cp "$SG_URI" "$PARTIAL" -f
if ! printf '%s  %s\n' "$EXPECTED_SHA" "$PARTIAL" | sha256sum -c -; then
  rm -f -- "$PARTIAL"
  exit 66
fi
mv -- "$PARTIAL" "$FINAL_FILE"
