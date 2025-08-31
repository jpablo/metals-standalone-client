#!/usr/bin/env bash
set -euo pipefail

# Simple helper to auto-commit changes as a named checkpoint.
# Usage: scripts/checkpoint-commit.sh <name> [--tag]

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <checkpoint-name> [--tag]" >&2
  exit 2
fi

CKPT_NAME="$1"; shift || true
CREATE_TAG=0
if [[ "${1:-}" == "--tag" ]]; then
  CREATE_TAG=1
  shift || true
fi

# Ensure we are in repo root
REPO_ROOT=$(git rev-parse --show-toplevel)
cd "$REPO_ROOT"

# Stage everything and commit only if there are changes
git add -A
if git diff --cached --quiet; then
  echo "No staged changes to commit for checkpoint '$CKPT_NAME'." >&2
  exit 0
fi

BRANCH=$(git rev-parse --abbrev-ref HEAD)
TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

MSG="chore(checkpoint): ${CKPT_NAME} on ${BRANCH} @ ${TIMESTAMP}"
git commit -m "$MSG"

if [[ $CREATE_TAG -eq 1 ]]; then
  TAG="ckpt/${CKPT_NAME}"
  # Use lightweight tag to avoid prompting for editor
  git tag "$TAG"
  echo "Created tag: $TAG"
fi

echo "Checkpoint committed: $CKPT_NAME"

