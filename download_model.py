#!/usr/bin/env python3
"""Download the tested Qwen3-VL GGUF pair from Hugging Face."""

from argparse import ArgumentParser
from pathlib import Path

from huggingface_hub import snapshot_download


REPOSITORY = "Arm/qwen3-vl-2b-instruct-q4-k-m-ggml-llama-cpp-vivo-x300"
MODEL_FILE = "Qwen__Qwen3-VL-2B-Instruct_llamacpp_optimized.gguf"
PROJECTOR_FILE = "Qwen__Qwen3-VL-2B-Instruct_llamacpp_optimized_mmproj.gguf"


def main() -> None:
    parser = ArgumentParser()
    parser.add_argument("--output-dir", type=Path, default=Path("models/qwen3-vl-2b"))
    parser.add_argument("--print-dir", action="store_true")
    args = parser.parse_args()

    destination = args.output_dir.resolve()
    snapshot_download(
        repo_id=REPOSITORY,
        local_dir=destination,
        allow_patterns=[MODEL_FILE, PROJECTOR_FILE, "sample_input.jpg"],
    )
    if args.print_dir:
        print(destination)
    else:
        print(f"Downloaded model package to {destination}")
        print(f"  {MODEL_FILE}")
        print(f"  {PROJECTOR_FILE}")


if __name__ == "__main__":
    main()
