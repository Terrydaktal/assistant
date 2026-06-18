#!/bin/bash
# start_server.sh

# Get the directory where this script is located
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

# Activate virtual environment
source "${SCRIPT_DIR}/venv/bin/activate"

# Set LD_LIBRARY_PATH to include CUDA/cuDNN libraries from virtual environment
# This is critical for loading the necessary GPU libraries
export LD_LIBRARY_PATH="${VIRTUAL_ENV}/lib/python3.12/site-packages/nvidia/cudnn/lib:${VIRTUAL_ENV}/lib/python3.12/site-packages/nvidia/cublas/lib:$LD_LIBRARY_PATH"

# Optional: Verify CUDA/cuDNN paths exist
# This helps with debugging if libraries are missing
echo "Checking CUDA/cuDNN library paths..."
if [ -d "${VIRTUAL_ENV}/lib/python3.12/site-packages/nvidia/cudnn/lib" ]; then
    echo "✅ Found cuDNN libraries"
else
    echo "⚠️  cuDNN library path not found: ${VIRTUAL_ENV}/lib/python3.12/site-packages/nvidia/cudnn/lib"
fi

if [ -d "${VIRTUAL_ENV}/lib/python3.12/site-packages/nvidia/cublas/lib" ]; then
    echo "✅ Found cuBLAS libraries"
else
    echo "⚠️  cuBLAS library path not found: ${VIRTUAL_ENV}/lib/python3.12/site-packages/nvidia/cublas/lib"
fi

echo "Starting Swiftsay server with GPU support..."

# Start server with positional arguments (host, port)
# swiftsay_server.py expects: python swiftsay_server.py <host> <port>
python "${SCRIPT_DIR}/swiftsay_server.py" 0.0.0.0 5000