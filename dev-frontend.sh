#!/usr/bin/env bash
# Start the Vite HMR dev server. Requires the backend (dev-backend.sh) to be running.
# Open http://localhost:5173 in your browser.
cd "$(dirname "$0")/frontend"
npm run dev
