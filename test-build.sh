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