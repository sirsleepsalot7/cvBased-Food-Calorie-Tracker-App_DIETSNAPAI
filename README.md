# DietSnapAI 🥗📸

DietSnapAI is an intelligent, privacy-first Android application that uses on-device computer vision and edge deep learning to automatically detect food items, estimate their 3D volume, and calculate nutritional values (calories and macros) in real time.

---

## ✨ Key Features

- **Real-Time Camera Scanning**: High-performance camera preview using Android Jetpack CameraX.
- **Edge AI & Privacy**: All inference runs locally on-device using ONNX Runtime Mobile—no cloud upload of your food photos required.
- **YOLOv8 Instance Segmentation**: Detects food items and segments precise boundaries (`yolov8n_seg.onnx`).
- **Depth Estimation & 3D Volume**: Generates metric depth maps using Depth Anything V2 (`depth_anything_v2_518.onnx`) combined with reference calibration to calculate food volume in $\text{cm}^3$.
- **Automated Macro & Calorie Estimation**: Maps detected items and calculated volumes to nutritional profiles (Calories, Protein, Carbs, Fats) via a local Room database.
- **Meal History & Logging**: Track daily intake with an interactive history bottom sheet and logging workflow.
- **Modern UI**: Built 100% with Jetpack Compose and Material 3 design principles.

---

## 🛠️ Tech Stack & Architecture

- **Language**: Kotlin
- **UI Framework**: Jetpack Compose & Material 3
- **Camera**: AndroidX CameraX (Core, Camera2, Lifecycle, View)
- **Machine Learning**: 
  - [ONNX Runtime Android](https://onnxruntime.ai/) (`com.microsoft.onnxruntime:onnxruntime-android:1.17.1`)
  - YOLOv8 Nano Segmentation
  - Depth Anything V2
- **Persistence**: Room Database with Kotlin Symbol Processing (KSP)
- **Concurrency**: Kotlin Coroutines & Flow
- **Asset Versioning**: Git LFS (Large File Storage) for `.onnx` models

---

## 📁 Project Structure

```
DietSnapAI/
├── app/
│   ├── src/main/
│   │   ├── assets/                # ONNX models (depth_anything_v2_518.onnx, yolov8n_seg.onnx)
│   │   ├── java/com/example/dietsnapai/
│   │   │   ├── db/                # Room DB entities, DAOs, and nutrition repositories
│   │   │   ├── ml/                # ONNX ModelEngine, Calibrator, VolumeEstimator, ImageUtils
│   │   │   ├── ui/                # Jetpack Compose UI themes and components
│   │   │   ├── HistoryBottomSheet.kt
│   │   │   └── MainActivity.kt    # Main camera, detection overlays & bottom sheets
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── gradle/
└── README.md
```

---

## 🚀 Getting Started

### Prerequisites

1. **Android Studio**: Android Studio Iguana / Jellyfish / Koala or newer.
2. **JDK**: Java Development Kit 17.
3. **Android Device / Emulator**: Running Android 8.0 (API 26) or higher with camera support.
4. **Git LFS**: Ensure Git Large File Storage is installed on your machine (`git lfs version`).

### Installation & Build

1. **Clone the repository**:
   ```bash
   git clone <REPO_URL>
   cd DietSnapAI
   ```

2. **Pull Large Files (Git LFS)**:
   ```bash
   git lfs pull
   ```

3. **Open in Android Studio**:
   - Open Android Studio and select **Open** -> Navigate to the `DietSnapAI` folder.
   - Let Gradle sync dependencies.

4. **Run the App**:
   - Connect an Android device with USB debugging enabled (or use an emulator with camera enabled).
   - Click **Run** (`Shift + F10`).

---

## 📄 License

This project is licensed under the [MIT License](LICENSE) (or applicable open-source license).
