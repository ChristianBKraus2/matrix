#!/usr/bin/env bash
# Start the backend for development. Skips the frontend build — run dev-frontend.sh in a second terminal.
cd "$(dirname "$0")"
./gradlew.bat run -x buildFrontend
