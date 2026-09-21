# Vision Chat Android application

This example application accompanies the [Arm Learning Path for running an
optimized vision-language model from the Arm AI Portal on
Android](https://learn.arm.com/learning-paths/mobile-graphics-and-gaming/ai-portal-mobile-vision-language).
It is intended for learning how a multimodal model runs on an Android device and
is not a reference production application.

Vision Chat runs the Arm-optimized Qwen3-VL 2B Instruct model locally on an
Arm64 Android phone. Select an image, enter a question, and generate a response
without sending the image or prompt to a service. The application has no Android
network permission.

The model package remains outside the Android application package (APK). Vision
Chat imports the language-model and vision-projector GGUF files through the
Android document picker and copies them to application-private storage.

## Application views

<p align="center">
  <img src="docs/images/vision-chat-startup.png" width="45%" alt="Vision Chat before the model files and an image have been selected">
  <img src="docs/images/vision-chat-result.png" width="45%" alt="Vision Chat displaying a Qwen3-VL response and local inference measurements">
</p>

The application accepts an image and text prompt, generates a response locally,
and reports model-load time, image-and-prompt evaluation time, output-token
count, and decode speed.

## Requirements

- Android Studio with Android SDK Platform 35 and Build-Tools 35.0.0
- Java Development Kit (JDK) 17, supplied by Android Studio or available on
  your `PATH`
- Android NDK 27.2.12479018
- CMake 3.22.1
- An Arm64 Android phone running Android 9 (API level 28) or later with
  `asimddp` and `i8mm` CPU features
- At least 4 GB of free storage while downloading and importing the model
- Python 3.9 or later with `venv` and `pip` support
- A Hugging Face account
- A data-capable USB cable

Check the phone's CPU features before building the application:

```console
adb shell cat /proc/cpuinfo
```

Find the `Features` line and confirm that it contains `asimddp` and `i8mm`.
Vision Chat uses native libraries built for Armv8.6-A with Dot Product and Int8
Matrix Multiplication instructions, so both features are required.

## Supported model

| Model | Runtime | Hugging Face repository | Import these files |
| --- | --- | --- | --- |
| Qwen3-VL 2B Instruct | llama.cpp with `libmtmd` | [`Arm/qwen3-vl-2b-instruct-q4-k-m-ggml-llama-cpp-vivo-x300`](https://huggingface.co/Arm/qwen3-vl-2b-instruct-q4-k-m-ggml-llama-cpp-vivo-x300) | `Qwen__Qwen3-VL-2B-Instruct_llamacpp_optimized.gguf` and `Qwen__Qwen3-VL-2B-Instruct_llamacpp_optimized_mmproj.gguf` |

The first GGUF contains the Q4_K_M language model. The matching `mmproj` GGUF
contains the Q8_0 vision encoder and projector. Vision Chat requires both exact
filenames and imports them together as one package.

## Clone the repository

```console
git clone https://github.com/arm-education/ai-portal-android-app-image-text-to-text.git
cd ai-portal-android-app-image-text-to-text
```

Run the remaining commands from the repository root.

## Download the model package

Create a Python virtual environment and install the Hugging Face Hub package.

On macOS or Linux:

```bash
python3 -m venv .hf-venv
.hf-venv/bin/python -m pip install --upgrade pip huggingface_hub
```

On Windows PowerShell:

```powershell
python -m venv .hf-venv
.\.hf-venv\Scripts\python.exe -m pip install --upgrade pip huggingface_hub
```

Run the downloader from the repository root.

On macOS or Linux:

```bash
.hf-venv/bin/python download_model.py
```

On Windows PowerShell:

```powershell
.\.hf-venv\Scripts\python.exe download_model.py
```

If Hugging Face requires authentication, sign in and repeat the download.

On macOS or Linux:

```console
.hf-venv/bin/hf auth login
```

On Windows PowerShell:

```powershell
.\.hf-venv\Scripts\hf.exe auth login
```

The downloader creates `models/qwen3-vl-2b/` and retrieves these files:

| File | Application role |
| --- | --- |
| `Qwen__Qwen3-VL-2B-Instruct_llamacpp_optimized.gguf` | Q4_K_M language model |
| `Qwen__Qwen3-VL-2B-Instruct_llamacpp_optimized_mmproj.gguf` | Q8_0 vision encoder and projector |
| `sample_input.jpg` | Sample image for the first inference run |

Copy the files to the Android **Downloads** directory:

```console
adb push models/qwen3-vl-2b/Qwen__Qwen3-VL-2B-Instruct_llamacpp_optimized.gguf /sdcard/Download/
adb push models/qwen3-vl-2b/Qwen__Qwen3-VL-2B-Instruct_llamacpp_optimized_mmproj.gguf /sdcard/Download/
adb push models/qwen3-vl-2b/sample_input.jpg /sdcard/Download/
```

You can instead use Android Studio's **Device Explorer** to upload the files to
`/sdcard/Download/`.

## Build the application

Vision Chat pins a llama.cpp revision that includes Qwen3-VL support in
`libmtmd`. Fetch and verify the pinned source before running Gradle.

On macOS or Linux:

```bash
chmod +x scripts/fetch_llama_cpp.sh
./scripts/fetch_llama_cpp.sh
```

On Windows PowerShell:

```powershell
$llamaCommit = "56381e407c0ccfb3a6f71e668a27a901001d22ce"
$llamaSha256 = "7fd19c03e7d7bb02c07e64c47e0539aeabe54f366a59be822f550b5f012a8751"
$llamaArchive = Join-Path $env:TEMP "llama-cpp-$llamaCommit.tar.gz"
$llamaStaging = Join-Path $env:TEMP "llama-cpp-$([guid]::NewGuid())"

Invoke-WebRequest `
    -Uri "https://github.com/ggml-org/llama.cpp/archive/$llamaCommit.tar.gz" `
    -OutFile $llamaArchive

if ((Get-FileHash $llamaArchive -Algorithm SHA256).Hash -ne $llamaSha256) {
    throw "Checksum verification failed for the llama.cpp archive."
}

New-Item -ItemType Directory -Path $llamaStaging | Out-Null
tar -xzf $llamaArchive -C $llamaStaging
New-Item -ItemType Directory -Force -Path third_party | Out-Null
Move-Item `
    "$llamaStaging\llama.cpp-$llamaCommit" `
    "third_party\llama.cpp"
```

Build and lint the debug APK.

On macOS or Linux:

```bash
chmod +x gradlew
./gradlew :app:assembleDebug :app:lintDebug
```

On Windows PowerShell:

```powershell
.\gradlew.bat :app:assembleDebug :app:lintDebug
```

You can also open the repository root in Android Studio after fetching
llama.cpp, wait for Gradle sync to finish, and run the `app` configuration.

Install the APK on a connected phone and start Vision Chat:

```console
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n org.arm.learningpath.visionchat/.MainActivity
```

## Run Qwen3-VL

In Vision Chat:

1. Select **Add or change model files**.
2. Open **Downloads**, select both GGUF files, and confirm the selection.
3. Select **Choose a photo**, then choose `sample_input.jpg`.
4. Enter `Describe this image in three sentences.` in the prompt field.
5. Select **Ask Qwen3-VL**.

The first run loads both GGUF files, encodes the image, evaluates the prompt,
and generates up to 128 tokens. The result card displays the generated response
and local inference measurements.

The importer copies the model pair to application-private storage and activates
it only after both copies complete. You can delete the originals from
**Downloads** after import. Clearing the application data or uninstalling the
application removes the imported copies.

To verify local operation, enable airplane mode and ask another question about
the same image. The application can generate another response because the
image, prompt, model, and runtime remain on the phone.

## Application structure

`MainActivity.java` connects the Android document pickers, selected image,
prompt, model package, and result views. It moves model import and inference off
the Android user-interface thread so the screen remains responsive.

The main files are:

- `app/src/main/assets/model_catalog.json`: registers the supported model and
  projector filenames, context size, image limit, generation limit, thread
  count, and sampling settings.
- `ModelCatalog.java`: loads the model descriptors from the JSON catalog.
- `ModelDescriptor.java`: defines the model package and generation settings.
- `ModelPackageImporter.java`: matches the selected filenames, checks the GGUF
  file signatures, and replaces the installed package through a staging
  directory.
- `LlamaCppVisionRunner.java`: resizes the image, keeps the model loaded between
  prompts, calls the native bridge, and returns the response and measurements.
- `NativeVisionBridge.java`: defines the Java Native Interface (JNI) calls used
  to load, run, cancel, and close the model.
- `app/src/main/cpp/vision_chat.cpp`: prepares the image and prompt with
  `libmtmd`, evaluates them with llama.cpp, and samples output tokens.
- `app/src/main/cpp/CMakeLists.txt`: builds llama.cpp for Android and enables
  the Arm instruction target and KleidiAI CPU kernels.
- `download_model.py`: downloads the registered model pair and sample image.
- `scripts/fetch_llama_cpp.sh`: downloads and verifies the pinned llama.cpp
  source archive.

## Arm CPU dispatch

The APK includes KleidiAI SME2, SME, I8MM, and Dot Product microkernels. At
runtime, llama.cpp reads the CPU features exposed by Android and selects the
most capable compatible KleidiAI kernel for supported operations. The Q8_0
vision encoder and projector can use SME2 on an SME2-enabled phone. A phone with
SME but not SME2 uses SME, and other supported phones use I8MM.

The Q4_K_M language model contains Q4_K and Q6_K matrices. These use llama.cpp's
optimized Arm repack kernels rather than the KleidiAI Q8_0 path. SME2 therefore
primarily accelerates image encoding and projection for this package; it does
not replace every kernel used during token generation.

The native log reports the detected features and selected KleidiAI path. After
running the model, display the relevant messages:

```console
adb logcat -d -s VisionChatNative:I llama.cpp:I '*:S'
```

## Use another model package

Another package can use the existing runner only when llama.cpp and `libmtmd`
can load its language-model and projector GGUF files, the projector matches the
language model, the embedded chat template produces the expected prompt, and
the application's image and generation controls fit the model.

To register a compatible package, update `model_catalog.json` and
`download_model.py`, then rebuild and test the APK on a supported Arm64 Android
phone. Changing the catalog does not add executable code or make an incompatible
package work. A model that needs different native processing, prompt handling,
controls, or result types also needs application code changes.

## Source versions

The build pins llama.cpp commit
`56381e407c0ccfb3a6f71e668a27a901001d22ce`. `libmtmd` is an evolving API, so
update the pin and JNI integration together.

## License

This project is provided under the [Arm Education End User License
Agreement](LICENSE.md).
