#!/bin/bash
set -e

echo "Testing standalone metal-standalone-client build..."
cd /Users/jpablo/proyectos/experimentos/metal-standalone-client

echo "Running scala-cli compile..."
scala-cli compile .

echo "Running scala-cli test..."
scala-cli test .

echo "Running scala-cli package..."
scala-cli --power package . --assembly -f -o metals-standalone-client

echo "Build test completed successfully!"

# Optional: auto-commit a build checkpoint if requested
if [[ "${AUTO_COMMIT:-0}" == "1" ]]; then
  echo "Auto-committing build checkpoint..."
  ./scripts/checkpoint-commit.sh build-ok || true
fi
