#!/usr/bin/env bash
#
# Simulate a moving device by posting a series of GPS points spread over the
# last couple of hours. Useful to test the Viewer's 12h trail without walking.
#
# Usage:
#   BASE=https://g-tracker.d42n.online \
#   KEY=your-api-key \
#   DEVICE=sim-andi \
#   POINTS=20 \
#   ./seed-track.sh
#
set -euo pipefail

BASE="${BASE:-https://g-tracker.d42n.online}"
KEY="${KEY:?set KEY=<api key>}"
DEVICE="${DEVICE:-sim-device}"
POINTS="${POINTS:-20}"

# Start near Monas, Jakarta, then drift north-east a little each step.
lat=-6.175400
lng=106.827200

now_ms=$(( $(date +%s) * 1000 ))
# Spread the points across the last 2 hours.
span_ms=$(( 2 * 3600 * 1000 ))

echo "Seeding $POINTS points for device '$DEVICE' -> $BASE"
for i in $(seq 0 $((POINTS - 1))); do
  # Move ~11m per step in each axis (0.0001 deg ~= 11m).
  lat=$(awk "BEGIN{printf \"%.6f\", $lat + 0.00012}")
  lng=$(awk "BEGIN{printf \"%.6f\", $lng + 0.00010}")

  # Oldest point first, newest last.
  ts=$(( now_ms - span_ms + (i * span_ms / (POINTS - 1)) ))

  code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/api/positions" \
    -H "Content-Type: application/json" \
    -H "X-API-Key: $KEY" \
    -d "{\"deviceId\":\"$DEVICE\",\"lat\":$lat,\"lng\":$lng,\"accuracy\":8.0,\"speed\":1.5,\"timestamp\":$ts}")
  echo "  [$((i + 1))/$POINTS] $lat,$lng  ts=$ts  -> HTTP $code"
done

echo "Done. Open the Viewer (or GET /api/positions/tracks?hours=12) to see the trail."
