#!/bin/bash
# Compiles src/cuda/*.cu to PTX in src/client/resources/assets/inertia/cuda with NVIDIA's NVRTC (the
# PTX is committed, so building the mod needs no CUDA tools; the driver compiles PTX for the GPU).
#
#   tools/compile-cuda.sh /path/to/libnvrtc.so.12
#
# NVRTC comes with the CUDA toolkit, or from PyPI: `pip install nvidia-cuda-nvrtc-cu12`.
set -e
cd "$(dirname "$0")/.."
nvrtc="${1:?usage: tools/compile-cuda.sh /path/to/libnvrtc.so.12}"
for cu in src/cuda/*.cu; do
	name=$(basename "$cu" .cu)
	python3 -I tools/nvrtc_ptx.py "$nvrtc" "$cu" "src/client/resources/assets/inertia/cuda/$name.ptx" compute_52
done
