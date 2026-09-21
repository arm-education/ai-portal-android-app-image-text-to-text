#!/usr/bin/env python3
"""Download and package the tested Qwen3-VL GGUF pair."""

from argparse import ArgumentParser
import hashlib
import json
from pathlib import Path
from zipfile import ZIP_STORED, ZipFile


REPOSITORY = "Arm/qwen3-vl-2b-instruct-q4-k-m-ggml-llama-cpp-vivo-x300"
MODEL_FILE = "Qwen__Qwen3-VL-2B-Instruct_llamacpp_optimized.gguf"
PROJECTOR_FILE = "Qwen__Qwen3-VL-2B-Instruct_llamacpp_optimized_mmproj.gguf"
PACKAGE_FILE = "Qwen__Qwen3-VL-2B-Instruct_llamacpp_optimized.zip"
MANIFEST_FILE = "model-package.json"
MODEL_ID = "qwen3-vl-2b-instruct-q4-k-m"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def package_model(destination: Path, remove_source_files: bool = True) -> Path:
    model = destination / MODEL_FILE
    projector = destination / PROJECTOR_FILE
    package = destination / PACKAGE_FILE
    temporary_package = package.with_suffix(".zip.tmp")

    manifest = {
        "formatVersion": 1,
        "modelId": MODEL_ID,
        "model": {
            "file": MODEL_FILE,
            "size": model.stat().st_size,
            "sha256": sha256(model),
        },
        "projector": {
            "file": PROJECTOR_FILE,
            "size": projector.stat().st_size,
            "sha256": sha256(projector),
        },
    }

    temporary_package.unlink(missing_ok=True)
    try:
        with ZipFile(temporary_package, "w", compression=ZIP_STORED) as archive:
            archive.writestr(MANIFEST_FILE, json.dumps(manifest, indent=2) + "\n")
            archive.write(model, MODEL_FILE)
            archive.write(projector, PROJECTOR_FILE)
        temporary_package.replace(package)
    except Exception:
        temporary_package.unlink(missing_ok=True)
        raise

    if remove_source_files:
        model.unlink()
        projector.unlink()
    return package


def main() -> None:
    from huggingface_hub import snapshot_download

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
    package = package_model(destination)
    if args.print_dir:
        print(destination)
    else:
        print(f"Downloaded model package to {destination}")
        print(f"  {package.name}")
        print("  sample_input.jpg")


if __name__ == "__main__":
    main()
