#!/usr/bin/env bash
set -e

MODEL_DIR="models"
MODEL_NAME="vosk-model-small-ru-0.22"
MODEL_URL="https://alphacephei.com/vosk/models/${MODEL_NAME}.zip"

echo "================================================"
echo "Vosk Model Download Script"
echo "================================================"

# Create models directory
mkdir -p "$MODEL_DIR"
cd "$MODEL_DIR"

# Check if model already exists
if [ -d "$MODEL_NAME" ]; then
    echo "✓ Model already exists at: $MODEL_DIR/$MODEL_NAME"
    echo "  To re-download, delete the directory first:"
    echo "  rm -rf $MODEL_DIR/$MODEL_NAME"
    exit 0
fi

echo "Downloading Vosk model: $MODEL_NAME"
echo "URL: $MODEL_URL"
echo "This may take several minutes (~500MB)..."
echo ""

# Download model
wget -q --show-progress "$MODEL_URL" || {
    echo "✗ Download failed!"
    exit 1
}

echo ""
echo "Extracting model..."
unzip -q "${MODEL_NAME}.zip"

echo "Cleaning up zip file..."
rm "${MODEL_NAME}.zip"

echo ""
echo "================================================"
echo "✓ Model downloaded successfully!"
echo "  Location: $MODEL_DIR/$MODEL_NAME"
echo "================================================"
