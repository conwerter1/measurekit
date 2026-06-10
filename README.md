# MeasureKit

Android measurement toolkit for field engineers and auditors. Works fully offline, no ARCore required.

Designed for **Xiaomi 12T** (MediaTek Dimensity 8100-Ultra, Android 15) but runs on any Android 8.0+ device.

## Modules

### Measurements
| Module | Description |
|---|---|
| **Ruler** | On-screen ruler calibrated to device DPI (446 ppi on 12T). Manual calibration via credit card (ISO/IEC 7810: 85.60×53.98 mm). |
| **Level** | Digital spirit level using device orientation sensors. |
| **Protractor** | Angle measurement. |
| **Rangefinder** | Trigonometric distance measurement using device tilt angle and known height. Accuracy ±5–10% at 2–20 m on flat ground. |
| **Photo measure** | Measure objects in photo using a reference of known size. Supports 4-point perspective correction. |
| **Area** | Polygon area and perimeter from photo points. |

### Audit
| Module | Description |
|---|---|
| **Rebar / stockpile** | YOLO-based detector for counting rebar bundle cross-sections and steel pipes. Supports 5 material types: rebar, steel pipe (round), steel pipe (rectangular), round bar, custom. Mass calculation per GOST 5781-82 / 34028-2016 (A240–A500С + Turkish B420C/B500C, 14 diameters), GOST 3262-75, GOST 30245-2003. |
| **RBU audit** | Concrete batching plant inspection checklist. |
| **Stockpile volume** | Aggregate stockpile volume estimation. |

### Journal
All measurements saved locally with Room database. CSV export and ZIP archive.

## ML Models

| Model | Size | mAP50 | Classes |
|---|---|---|---|
| `rebar_detector.onnx` | 9.9 MB | 98.4% | rebar cross-section |
| `multi_detector.onnx` | 9.9 MB | 73.3% | pipe / round bar / custom |

Trained with YOLOv8n on Huawei rebar_count dataset (250 images) and custom data. Runs via ONNX Runtime for Android.

## Tech Stack

- **Kotlin + Jetpack Compose** (Material 3)
- **CameraX 1.4.0**
- **Room 2.6.1**
- **ONNX Runtime 1.19.0** (Microsoft)
- **ML Kit text-recognition 16.0.1** (offline OCR)
- **Navigation Compose 2.8.4**
- minSdk 26 (Android 8.0+), targetSdk 34, JVM 17

## Build

Requirements: JDK 17, Android SDK API 34.

```bat
set JAVA_HOME=C:\path\to\jdk17
set ANDROID_HOME=C:\path\to\android\sdk
cd MeasureKit
gradlew.bat assembleRelease
adb install -r app\build\outputs\apk\release\app-release.apk
```

## Download

See [Releases](https://github.com/conwerter1/measurekit/releases) for signed APK.

## License

Apache-2.0
