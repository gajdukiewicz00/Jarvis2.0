#!/usr/bin/env bash
# =============================================================================
# Convert HuggingFace model to GGUF format for llama.cpp
# =============================================================================
#
# Usage:
#   ./scripts/convert-to-gguf.sh /path/to/hf-model /path/to/output.gguf [quantization]
#
# Example:
#   ./scripts/convert-to-gguf.sh models/llm/h2ogpt-4096-llama2-7b-chat models/llm/h2ogpt-7b-q4.gguf Q4_K_M
#
# Quantization options:
#   Q4_K_M  - Recommended balance of speed/quality (default)
#   Q5_K_M  - Higher quality, slightly slower
#   Q8_0    - Highest quality, most VRAM
#   F16     - No quantization (largest, highest quality)
#
# =============================================================================

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Check arguments
if [ $# -lt 2 ]; then
    echo -e "${RED}Usage: $0 <hf-model-path> <output-gguf-path> [quantization]${NC}"
    echo "Example: $0 models/llm/h2ogpt-4096-llama2-7b-chat models/llm/h2ogpt-7b-q4.gguf Q4_K_M"
    exit 1
fi

HF_MODEL_PATH="$1"
OUTPUT_PATH="$2"
QUANT="${3:-Q4_K_M}"

# Check if model path exists
if [ ! -d "$HF_MODEL_PATH" ]; then
    echo -e "${RED}Error: Model path does not exist: $HF_MODEL_PATH${NC}"
    exit 1
fi

# Check for llama.cpp
LLAMA_CPP_DIR="${LLAMA_CPP_DIR:-$HOME/llama.cpp}"

if [ ! -d "$LLAMA_CPP_DIR" ]; then
    echo -e "${YELLOW}llama.cpp not found at $LLAMA_CPP_DIR${NC}"
    echo -e "Cloning llama.cpp..."
    git clone https://github.com/ggerganov/llama.cpp "$LLAMA_CPP_DIR"
    cd "$LLAMA_CPP_DIR"
    
    # Build llama.cpp with CUDA support
    echo -e "${GREEN}Building llama.cpp with CUDA support...${NC}"
    cmake -B build -DGGML_CUDA=ON
    cmake --build build --config Release -j$(nproc)
    cd -
fi

# Create temp directory for intermediate files
TEMP_DIR=$(mktemp -d)
F16_PATH="$TEMP_DIR/model-f16.gguf"

echo -e "${GREEN}=== Converting HuggingFace model to GGUF ===${NC}"
echo "Input:  $HF_MODEL_PATH"
echo "Output: $OUTPUT_PATH"
echo "Quant:  $QUANT"
echo ""

# Step 1: Convert to GGUF F16
echo -e "${YELLOW}Step 1: Converting to GGUF (F16)...${NC}"
python3 "$LLAMA_CPP_DIR/convert_hf_to_gguf.py" \
    "$HF_MODEL_PATH" \
    --outfile "$F16_PATH" \
    --outtype f16

# Step 2: Quantize
if [ "$QUANT" = "F16" ]; then
    echo -e "${YELLOW}Step 2: Skipping quantization (F16 requested)${NC}"
    mv "$F16_PATH" "$OUTPUT_PATH"
else
    echo -e "${YELLOW}Step 2: Quantizing to $QUANT...${NC}"
    "$LLAMA_CPP_DIR/build/bin/llama-quantize" \
        "$F16_PATH" \
        "$OUTPUT_PATH" \
        "$QUANT"
fi

# Cleanup
rm -rf "$TEMP_DIR"

# Show result
echo ""
echo -e "${GREEN}=== Conversion complete! ===${NC}"
ls -lh "$OUTPUT_PATH"
echo ""
echo "To use with Jarvis:"
echo "  1) Place the model in models/llm"
echo "  2) Set env if needed: LLM_BACKEND=llamacpp"
echo "  3) Ensure GGUF_MODEL_PATH points to /models/llm/$(basename $OUTPUT_PATH)"

