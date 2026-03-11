# LocalAI 🤖
**Local GGUF AI runner for Android — Realme C25Y (Unisoc T610 · ARM64 · 4GB RAM)**

[![Build APK](https://github.com/Dacraezy1/LocalAI/actions/workflows/build.yml/badge.svg)](https://github.com/Dacraezy1/LocalAI/actions/workflows/build.yml)

No internet required for inference. Everything runs on-device using [llama.cpp](https://github.com/ggerganov/llama.cpp).

---

## 📥 Download the APK (no build needed)

1. Go to **[Actions](https://github.com/Dacraezy1/LocalAI/actions)**
2. Click the latest **Build LocalAI APK** run ✅
3. Scroll down to **Artifacts** → download **LocalAI-APK-...**
4. Unzip → install `.apk` on your phone (allow unknown sources)

> The APK is built automatically by GitHub Actions every time code is pushed. No Android Studio, no local build needed.

---

## Features
- 📥 Download GGUF models directly from HuggingFace inside the app
- 💬 Streaming chat — token by token, like ChatGPT
- 🧠 Markdown rendering (code blocks, bold, lists)
- ⚡ ARM64 optimized: 4 threads + NEON SIMD for Unisoc T610
- 🎨 Dark AMOLED theme (saves battery)
- 📊 Live tokens/sec speed meter
- 💾 Model manager — install, switch, delete models
- ⏹ Stop generation button

## Recommended Models (best for 4GB RAM)

| Model | File Size | RAM Needed | Speed on T610 |
|---|---|---|---|
| ⭐ Llama 3.2 1B Q4 | 0.77 GB | ~1.0 GB | ~8-10 tok/s |
| ⭐ Qwen 1.5 1.8B Chat Q4 | 1.1 GB | ~1.4 GB | ~6-8 tok/s (multilingual) |
| ⭐ Phi-2 Q4 | 1.48 GB | ~1.8 GB | ~4-6 tok/s (smart) |
| Gemma 2 2B Q4 | 1.6 GB | ~2.0 GB | ~3-5 tok/s |
| Mistral 7B Q2 | 2.7 GB | ~3.2 GB | ~1-2 tok/s (slow!) |

---

## 🔧 How the build works (GitHub Actions)

Every push to `main` triggers `.github/workflows/build.yml` which:
1. Sets up Ubuntu + JDK 17
2. Installs Android SDK 34 + NDK 25 + CMake 3.22
3. Clones llama.cpp (pinned tag `b3447`) into `app/src/main/cpp/`
4. Compiles llama.cpp C++ → ARM64 `.so` via CMake
5. Builds the Kotlin app with Gradle → `app-debug.apk`
6. Uploads the APK as a downloadable artifact

Build takes **~15-25 minutes** on first run (compiling llama.cpp C++ is slow). Subsequent runs with cached NDK take **~5-8 minutes**.

---

## Project Structure

```
LocalAI/
├── .github/workflows/build.yml   ← GitHub Actions CI
├── app/src/main/
│   ├── cpp/
│   │   ├── CMakeLists.txt        ← CMake: builds llama.cpp + JNI
│   │   ├── llama_jni.cpp         ← C++ JNI bridge
│   │   └── llama.cpp/            ← cloned by CI (not in repo)
│   ├── java/com/localai/
│   │   ├── llm/                  ← LlamaWrapper, PromptFormatter, Params
│   │   ├── model/                ← Catalog, DB, DownloadService
│   │   └── ui/                   ← Chat, ModelManager, Settings
│   └── res/                      ← Layouts, colors, icons
```

---

## Performance Tips
- Keep context size ≤ 2048 in Settings (saves RAM)
- Use Q4_K_M models (best quality/speed ratio)
- Close other apps before running large models
- Plug in charger to prevent CPU throttling
