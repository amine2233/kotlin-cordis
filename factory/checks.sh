#!/usr/bin/env bash
# The repo's checks. Exit 0 only when the branch is fit to review.
set -euo pipefail

# mise.toml is not trusted locally, so its java shim refuses to run; point straight at a JDK 17 install.
if ! java -version >/dev/null 2>&1; then
  for candidate in "${JAVA_HOME:-}" "$HOME/.local/share/mise/installs/java/temurin-17" /Library/Java/JavaVirtualMachines/*17*/Contents/Home; do
    if [ -n "$candidate" ] && [ -x "$candidate/bin/java" ]; then
      export JAVA_HOME="$candidate"
      export PATH="$JAVA_HOME/bin:$PATH"
      break
    fi
  done
fi

KTLINT="$(command -v ktlint 2>/dev/null || true)"
if [ -z "$KTLINT" ] || ! "$KTLINT" --version >/dev/null 2>&1; then
  KTLINT="$(ls -d "$HOME"/.local/share/mise/installs/ktlint/*/ktlint-*/bin/ktlint 2>/dev/null | tail -1)"
fi
"$KTLINT" '**/*.kt' '**/*.kts' '!**/build/**' '!**/.worktrees/**' --reporter=plain

./gradlew jvmTest macosArm64Test apiCheck build -x check --console=plain
