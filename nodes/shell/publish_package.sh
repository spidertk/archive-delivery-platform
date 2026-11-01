#!/usr/bin/env bash
set -euo pipefail
if [[ "$#" -ne 4 ]]; then
  echo "Usage: publish_package.sh stagingDir finalName finalDir expectedSha" >&2
  exit 64
fi
STAGING_DIR="$1"
FINAL_NAME="$2"
FINAL_DIR="$3"
EXPECTED_SHA="$4"
ROOT="${ARCHIVE_FTP_ROOT:-/srv/maas-sftp/maasData/archive}"
if [[ ! "$FINAL_NAME" =~ ^batch_[0-9]{5}\.tar\.gz$ || "$STAGING_DIR" != "$ROOT/.archive-mvp-staging/"* ||
      "$FINAL_DIR" != "$ROOT/"* || "$STAGING_DIR" == *".."* || "$FINAL_DIR" == *".."* ||
      ! "$EXPECTED_SHA" =~ ^[0-9a-f]{64}$ ]]; then
  echo "Invalid publish paths or SHA-256" >&2
  exit 64
fi
SOURCE="$STAGING_DIR/$FINAL_NAME"
TARGET="$FINAL_DIR/$FINAL_NAME"
if [[ -f "$TARGET" ]]; then
  if [[ "$(sha256sum "$TARGET" | cut -d' ' -f1)" == "$EXPECTED_SHA" ]]; then
    exit 0
  fi
  echo "Final file conflicts with an existing different SHA" >&2
  exit 65
fi
if [[ ! -f "$SOURCE" ]] || ! printf '%s  %s\n' "$EXPECTED_SHA" "$SOURCE" | sha256sum -c -; then
  echo "Verified staging file is missing or has a different SHA" >&2
  exit 66
fi
mkdir -p -- "$FINAL_DIR"
mv -n -- "$SOURCE" "$TARGET"
if [[ "$(sha256sum "$TARGET" | cut -d' ' -f1)" != "$EXPECTED_SHA" ]]; then
  echo "Published file SHA mismatch" >&2
  exit 67
fi
