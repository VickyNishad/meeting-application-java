#!/usr/bin/env sh
# Media-plane load: real WebRTC publishers/subscribers against the SFU using the official LiveKit CLI.
# Target scenario: 100 rooms x 10 participants (1,000 participants), with screen share + recording sampled.
#
# Requires: lk (livekit-cli >= 2.x) or docker; LIVEKIT_URL (wss://...), LIVEKIT_API_KEY, LIVEKIT_API_SECRET.
# Run from load-generator machines OUTSIDE the SFU host; one generator typically handles ~150-300 simulated
# participants depending on CPU - split ROOMS across several generators and run them in parallel.
set -eu

ROOMS="${ROOMS:-100}"
PER_ROOM="${PER_ROOM:-10}"
DURATION="${DURATION:-10m}"
OUT="${OUT:-loadtest/results}"
mkdir -p "$OUT"

i=1
while [ "$i" -le "$ROOMS" ]; do
  # Each room: 3 video publishers (720p simulcast), all 10 subscribe; audio from every participant.
  lk load-test \
    --url "$LIVEKIT_URL" --api-key "$LIVEKIT_API_KEY" --api-secret "$LIVEKIT_API_SECRET" \
    --room "load-room-$i" \
    --video-publishers 3 --audio-publishers "$PER_ROOM" --subscribers "$PER_ROOM" \
    --video-resolution medium \
    --duration "$DURATION" > "$OUT/room-$i.log" 2>&1 &
  i=$((i + 1))
done
wait
echo "Done. Summaries per room in $OUT/ (packet loss, bitrate, jitter). Aggregate with: grep -h 'Total' $OUT/*.log"
