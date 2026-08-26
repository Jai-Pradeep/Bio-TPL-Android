# Biometric-Triggered Targeted Payload Delivery

### Android Red-Team Proof of Concept using Face Recognition and Dynamic Code Loading

> **Academic Security Research Project**
> A controlled proof-of-concept demonstrating how an Android application could use biometric identity recognition as a trigger for a targeted secondary payload.

---

## Overview

This project explores a security scenario in which a seemingly benign Android face-authentication application behaves differently depending on the identity detected by its on-device face-recognition pipeline.

The prototype has three possible outcomes:

* **Known User** → normal authentication and welcome screen
* **Designated Target** → controlled payload-trigger demonstration
* **Unknown User** → continue scanning

The system combines:

1. Synthetic face-data generation
2. Face detection and recognition
3. Template-based identity verification
4. Temporal consistency checking
5. Dynamic Android code loading
6. Controlled file-encryption demonstration
7. Recovery/decryption functionality

The complete experiment was conducted inside a **sandboxed Android emulator**. No real users, devices, credentials, or third-party infrastructure were targeted.

---

## System Architecture

```text
                    ┌─────────────────┐
                    │  Android Camera │
                    └────────┬────────┘
                             │
                             ▼
                    ┌─────────────────┐
                    │  ML Kit Face    │
                    │    Detection    │
                    └────────┬────────┘
                             │
                             ▼
                    ┌─────────────────┐
                    │ Face Crop &     │
                    │ Preprocessing   │
                    └────────┬────────┘
                             │
                             ▼
                    ┌─────────────────┐
                    │ InsightFace     │
                    │ buffalo_l       │
                    │   ONNX Model    │
                    └────────┬────────┘
                             │
                             ▼
                    ┌─────────────────┐
                    │ Cosine          │
                    │ Similarity      │
                    │ Template Match  │
                    └────────┬────────┘
                             │
                      3-sec confirmation
                             │
                ┌────────────┼────────────┐
                ▼            ▼            ▼
          Known User     Target       No Match
                │            │            │
                ▼            ▼            ▼
            WELCOME       HACKED       SCANNING
                           │
                           ▼
                    Controlled Payload
                       Demonstration
```

---

## Face Recognition Pipeline

The recognition system uses the pretrained **InsightFace `buffalo_l`** model with ONNX Runtime rather than training a face-recognition network from scratch.

The pipeline is:

```text
Camera Frame
    ↓
ML Kit Face Detection
    ↓
Select Largest Face
    ↓
20% Bounding-Box Margin
    ↓
Resize → 112 × 112
    ↓
Normalize Pixel Values
    ↓
InsightFace / ArcFace Embedding
    ↓
512-D L2-Normalized Embedding
    ↓
Cosine Similarity
    ↓
Template Matching
```

The system maintains class templates generated from training embeddings and uses independently calibrated cosine-similarity thresholds for the two identity classes.

### Temporal Verification

A single frame is not sufficient to trigger either path.

The detected identity must remain consistent for **three continuous seconds**.

If the detected identity changes during this period, the timer is reset.

This reduces accidental triggers caused by brief detections or transient recognition errors.

---

## Synthetic Dataset

The project starts with extremely limited identity data.

A reference photograph is used to construct a larger synthetic dataset using:

* Stable Diffusion 1.5
* IP-Adapter-FaceID
* InsightFace embeddings
* Conventional image augmentation

Approximately **500+ images per class** were generated.

### Data Split

Source images were divided **before augmentation**:

| Identity     | Class | Train | Validation | Test |
| ------------ | ----- | ----: | ---------: | ---: |
| Known User 1 | A     |    23 |          3 |    3 |
| Known User 2 | A     |    24 |          3 |    3 |
| Target       | B     |    24 |          3 |    3 |

Augmented images inherit the partition of their original source image, preventing source-image leakage between train, validation, and test sets.

### Generation

The synthetic generation pipeline uses:

* `Realistic_Vision_V4.0_noVAE`
* `stabilityai/sd-vae-ft-mse`
* `IP-Adapter-FaceID`
* DDIM scheduler
* 30 inference steps
* 512×512 output resolution
* Identity filtering using face-embedding similarity

Three prompt templates are cycled during generation. Failed generations are discarded.

---

## Authentication

Face embeddings are compared against class templates using cosine similarity.

The project uses two independently calibrated thresholds:

```text
Known User threshold  ≈ 0.2560
Target threshold      ≈ 0.2266
```

These thresholds were selected using the validation set by minimizing the combined false-acceptance and false-rejection rates.

### Decision Logic

```text
                    Face Embedding
                          │
                          ▼
                 Compare with templates
                          │
             ┌────────────┼────────────┐
             ▼            ▼            ▼
         Class A       Class B      Neither
             │            │            │
             ▼            ▼            ▼
        Known User     Target       Unknown
```

If both class thresholds are satisfied, the class with the higher similarity is selected.

---

## Android Application

The mobile application is implemented using Kotlin and Jetpack Compose.

### Technology Stack

| Component        | Technology               |
| ---------------- | ------------------------ |
| UI               | Jetpack Compose          |
| Camera           | CameraX                  |
| Face Detection   | Google ML Kit            |
| Face Recognition | InsightFace `buffalo_l`  |
| Inference        | ONNX Runtime             |
| Networking       | OkHttp                   |
| Async Processing | Kotlin Coroutines        |
| Dynamic Loading  | Android `DexClassLoader` |
| Language         | Kotlin                   |
| Minimum SDK      | 26                       |
| Target SDK       | 35                       |

The project configuration uses Java 11, Kotlin 2.2.10 and Android Gradle Plugin 9.3.2.

---

## Application States

The application has three primary UI states:

### `SCANNING`

The camera is active and the recognition pipeline continuously processes frames.

### `WELCOME`

Activated after successful Known User recognition.

```text
Authentication Successful
Welcome, <user>
```

### `HACKED`

Activated after sustained target recognition.

This screen represents the controlled security-experiment trigger and reports the result of the payload demonstration.

---

## Dynamic Payload Architecture

The project demonstrates a separation between the Android application and a separately compiled payload module.

Conceptually:

```text
Android Application
       │
       │ biometric trigger
       ▼
 Payload Manager
       │
       │ controlled download
       ▼
 Separate DEX Module
       │
       ▼
 DexClassLoader
       │
       ▼
 IPayload implementation
       │
       ▼
 Controlled File Operation
```

The application depends only on an `IPayload` interface, while the concrete implementation is supplied separately.

This demonstrates the security implications of Android's dynamic code-loading mechanism and why applications that download executable code at runtime require careful security analysis.

> **Note:** Operational payload implementation and deployment details are intentionally omitted from this README. The repository is intended for supervised academic analysis inside the provided sandbox environment.

---

## Experimental Results

### Face Recognition

The held-out test set produced:

| Metric                           | Result |
| -------------------------------- | -----: |
| Known User acceptance            |   100% |
| Target acceptance                |   100% |
| Target → Known misclassification |     0% |
| Known → Target misclassification |     0% |
| FAR                              |     0% |
| FRR                              |     0% |

These results demonstrate perfect separation **on the project's small held-out dataset**. They should not be interpreted as evidence of production-grade biometric accuracy.

### Payload Demonstration

The controlled emulator experiment demonstrated the following sequence:

```text
Target detected
      ↓
3-second confirmation
      ↓
HACKED state
      ↓
Payload retrieval
      ↓
Dynamic loading
      ↓
Controlled file-encryption demonstration
      ↓
Recovery / decryption
```

The recovery mechanism successfully restored the files after the demonstration.

---

## Important Limitations

This prototype has several significant limitations.

### 1. No Liveness Detection

The three-second gate is **not** a liveness mechanism.

A sufficiently good photograph could potentially satisfy the recognition pipeline if held in front of the camera for the required duration.

### 2. Synthetic-to-Real Domain Gap

The recognition thresholds were calibrated using synthetic data.

Synthetic faces can differ from live camera images in:

* Skin texture
* Lighting
* Facial boundaries
* Image artifacts
* Camera characteristics

Therefore, the reported test performance may not generalize to real-world camera conditions.

### 3. Limited Identity Data

The project begins with very few source photographs, making the dataset relatively small despite synthetic expansion.

### 4. No Deliberate Age Variation

The implemented generation prompts do not explicitly condition on age. Consequently, robustness to significant age-related appearance changes was not evaluated.

### 5. Aggregate Known-User Template

Multiple Known Users share a class-level aggregate template rather than having fully independent identity templates.

### 6. Emulator-Only Evaluation

The complete attack-chain demonstration was performed inside a controlled Android emulator rather than on real-world devices.

---

## Repository Structure

A reproducible repository is expected to contain:

```text
.
├── android/
│   ├── app/
│   ├── build.gradle.kts
│   └── AndroidManifest.xml
│
├── dataset/
│   ├── class_A/
│   └── class_B/
│
├── notebooks/
│   └── dataset_generation.ipynb
│
├── evaluation/
│   └── face_auth_eval.py
│
├── manifests/
│   ├── source_split_manifest.csv
│   └── final_manifest.csv
│
├── models/
│   └── face_auth_model.json
│
├── screenshots/
│   ├── scanning.png
│   ├── welcome.png
│   └── hacked.png
│
└── README.md
```

The project report identifies the dataset-generation notebook, split manifests, evaluation script, identity model, Android source, payload components, screenshots, and README as the intended reproducibility artifacts.

---

## Reproducing the Experiment

The experiment is intended to run entirely inside a sandboxed Android emulator.

At a high level:

```text
1. Generate / prepare the synthetic dataset
2. Verify train/validation/test separation
3. Extract face embeddings
4. Construct identity templates
5. Calibrate verification thresholds
6. Build the Android application
7. Configure the emulator camera
8. Start the controlled local experiment environment
9. Run the face-authentication demonstration
10. Verify Known User and Unknown User behaviour
11. Verify the controlled target-trigger demonstration
12. Restore the emulator/files after the experiment
```

The original development process also addressed practical issues including ONNX model memory usage, emulator camera configuration, JSON template loading, and dynamic-class instantiation.

---

## Security & Ethical Scope

This project is a **controlled academic red-team proof of concept**.

The experiment was explicitly designed with the following constraints:

* Sandbox/emulator execution
* No real users
* No real target devices
* No real credentials
* No third-party infrastructure
* Reversible file operations
* Recovery after demonstrations
* No permanent destruction of data

The project's report explicitly identifies exfiltration, network propagation, and persistence as out of scope.

The purpose is to study the security boundary created by combining:

```text
Biometric Recognition
        +
Dynamic Code Loading
        +
Broad File Access
        +
Payload Delivery
```

Understanding this combination helps defenders identify and mitigate suspicious application behaviour.

---

## Research Questions

The project investigates several security questions:

1. Can a pretrained face-recognition model reliably distinguish designated identities from a small synthetic dataset?
2. Can biometric recognition act as a reliable trigger for a secondary application behaviour?
3. What security implications arise when executable Android code is loaded dynamically?
4. How effective is temporal consistency in reducing accidental biometric triggers?
5. What are the limitations of synthetic face data when deployed against live camera input?
6. Which defensive controls can detect or prevent this class of behaviour?

---

## Future Work

Potential extensions include:

* Age-conditioned synthetic data generation
* Per-identity templates
* Live-camera threshold calibration
* Presentation-attack / liveness detection
* Better domain adaptation between synthetic and real camera images
* Stronger payload integrity verification
* Detection of suspicious dynamic code loading
* Runtime monitoring of unexpected executable downloads
* Least-privilege storage access
* Defensive Android instrumentation and malware-analysis tooling

---

## Team

### Jai Pradeep

* Synthetic dataset generation
* IP-Adapter-FaceID pipeline
* Dataset split and leakage verification
* Android application development
* CameraX integration
* ML Kit integration
* Face-recognition pipeline
* Template generation and threshold calibration
* Dynamic payload architecture
* Payload management
* Temporal detection gate
* UI implementation
* Evaluation and documentation

### Lokesh Yadav

* Reference photograph contribution

The detailed contribution breakdown is documented in the accompanying research report.

---

## Citation

If you use this project for academic work, please cite the accompanying report:

```text
Jai Pradeep and Lokesh Yadav.
"Biometric-Triggered Targeted Payload Delivery:
A Red Team Proof-of-Concept on Android Using
Synthetic Face Authentication and Dynamic Code Loading."
NeurIPS 2026 Project Report.
```

---

## Disclaimer

This repository is intended **solely for authorized academic security research and controlled experimentation**.

Do not deploy the system against devices, users, networks, or infrastructure without explicit authorization.

The project was evaluated exclusively within a controlled Android emulator environment, with recovery performed after demonstrations.

## Images
Scanning phase : 
<br>
<img width="492" height="1020" alt="Screenshot 2026-08-26 230908" src="https://github.com/user-attachments/assets/00700ce1-09dd-4538-8d95-f68eabe79709" />
<br>
Page of Verified User : 
<br>
<img width="480" height="1020" alt="Screenshot 2026-08-26 230925" src="https://github.com/user-attachments/assets/1664862f-34a5-4b4e-8197-e8c2c5e7a645" />
<br>
After Seeing Targetted User :
<br>
<img width="477" height="1016" alt="Screenshot 2026-08-26 231006" src="https://github.com/user-attachments/assets/53314fcf-e026-4038-a2ad-8da12fec1ea0" />
<br>
