#!/usr/bin/env bash
set -euo pipefail

# Build GeoVelis SOI v0.3
# Ensure ArcGIS Enterprise SDK Maven artifacts are installed first:
#   cd "$ENTDEVKITJAVA"
#   ./install-maven-artifacts.sh

mvn clean package

echo "Build completed. Check target/*.soe"
