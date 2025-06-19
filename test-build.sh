#!/bin/bash
set -e

echo "Testing standalone metal-standalone-client build..."
cd /Users/jpablo/proyectos/experimentos/metal-standalone-client

echo "Running sbt compile..."
sbt compile

echo "Running sbt assembly..."
sbt assembly

echo "Build test completed successfully!"