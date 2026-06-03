#!/bin/bash
# Downloads the MediaPipe face landmarker model into the Android assets folder.
# Run this once before building in Android Studio.

MODEL_URL="https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task"
DEST="app/src/main/assets/face_landmarker.task"

mkdir -p app/src/main/assets

if [ -f "$DEST" ] && [ "$(wc -c < "$DEST")" -gt 100000 ]; then
    echo "Model already present at $DEST"
    exit 0
fi

echo "Downloading face landmarker model (~5MB)..."
curl -L --progress-bar "$MODEL_URL" -o "$DEST"

if [ $? -eq 0 ]; then
    echo "Done — model saved to $DEST"
else
    echo "Download failed. Check your network and try again."
    exit 1
fi
