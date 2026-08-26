from pathlib import Path
import json

import cv2
import numpy as np
from insightface.app import FaceAnalysis
from tqdm import tqdm


# ============================================================
# CONFIGURATION
# ============================================================

DATASET = Path("face_auth_project_v2\\dataset")

PROFESSOR = "class_B"
KNOWN_USERS = "class_A"

TRAIN = "train"
VAL = "val"
TEST = "test"


# ============================================================
# ARC FACE / INSIGHTFACE
# ============================================================

def build_embedder():

    app = FaceAnalysis(
        name="buffalo_l",
        providers=[
            "CUDAExecutionProvider",
            "CPUExecutionProvider",
        ],
    )

    app.prepare(
        ctx_id=0,
        det_size=(640, 640),
    )

    return app


# ============================================================
# EXTRACT ONE FACE EMBEDDING
# ============================================================

def get_embedding(app, path):

    img = cv2.imread(str(path))

    if img is None:
        return None

    faces = app.get(img)

    if not faces:
        return None

    # Use largest detected face
    faces.sort(
        key=lambda f:
        (f.bbox[2] - f.bbox[0]) *
        (f.bbox[3] - f.bbox[1]),
        reverse=True
    )

    emb = faces[0].normed_embedding

    # Explicit L2 normalization
    emb = emb / np.linalg.norm(emb)

    return emb


# ============================================================
# LOAD EMBEDDINGS
# ============================================================

def load_embeddings(app, cls, split):

    folder = DATASET / cls / split

    files = sorted(
        list(folder.glob("*.jpg")) +
        list(folder.glob("*.jpeg")) +
        list(folder.glob("*.png"))
    )

    embeddings = []

    for path in tqdm(
        files,
        desc=f"{cls}/{split}"
    ):

        emb = get_embedding(app, path)

        if emb is not None:
            embeddings.append(emb)

    if not embeddings:

        raise RuntimeError(
            f"No usable faces in {folder}"
        )

    embeddings = np.stack(embeddings)

    print(
        f"{cls}/{split}: "
        f"{len(embeddings)}/{len(files)} usable"
    )

    return embeddings


# ============================================================
# BUILD TEMPLATE
# ============================================================

def build_template(embeddings):

    # Mean embedding
    template = embeddings.mean(axis=0)

    # Normalize again
    template = template / np.linalg.norm(template)

    return template


# ============================================================
# COSINE SIMILARITY
# ============================================================

def similarity(a, b):

    return float(np.dot(a, b))


# ============================================================
# GET SIMILARITY SCORES
# ============================================================

def get_scores(embeddings, template):

    return np.array([
        similarity(e, template)
        for e in embeddings
    ])


# ============================================================
# FIND THRESHOLD
#
# positive_scores = genuine identity
# negative_scores = other identity
# ============================================================

def find_threshold(
    positive_scores,
    negative_scores
):

    lo = min(
        positive_scores.min(),
        negative_scores.min()
    )

    hi = max(
        positive_scores.max(),
        negative_scores.max()
    )

    thresholds = np.linspace(
        lo,
        hi,
        1000
    )

    best = None

    for t in thresholds:

        # Genuine person incorrectly rejected
        frr = np.mean(
            positive_scores < t
        )

        # Other person incorrectly accepted
        far = np.mean(
            negative_scores >= t
        )

        error = far + frr

        if best is None or error < best["error"]:

            best = {
                "threshold": float(t),
                "far": float(far),
                "frr": float(frr),
                "error": float(error),
            }

    return best


# ============================================================
# PRINT SCORE DISTRIBUTION
# ============================================================

def print_distribution(name, scores):

    print(f"\n{name}")

    print(
        f"min  = {scores.min():.4f}"
    )

    print(
        f"mean = {scores.mean():.4f}"
    )

    print(
        f"max  = {scores.max():.4f}"
    )


# ============================================================
# MAIN
# ============================================================

def main():

    print("=" * 70)
    print("3-WAY FACE AUTHENTICATION")
    print("=" * 70)

    print()
    print("Class B -> PROFESSOR")
    print("Class A -> KNOWN USER")
    print("Neither  -> UNKNOWN USER")
    print()

    app = build_embedder()

    # ========================================================
    # 1. TRAINING EMBEDDINGS
    # ========================================================

    print("\n[1] Loading training embeddings")

    professor_train = load_embeddings(
        app,
        PROFESSOR,
        TRAIN
    )

    known_train = load_embeddings(
        app,
        KNOWN_USERS,
        TRAIN
    )

    # ========================================================
    # 2. BUILD TEMPLATES
    # ========================================================

    print("\n[2] Building identity templates")

    professor_template = build_template(
        professor_train
    )

    known_template = build_template(
        known_train
    )

    print(
        "Professor template:",
        professor_template.shape
    )

    print(
        "Known-user template:",
        known_template.shape
    )

    # ========================================================
    # 3. VALIDATION EMBEDDINGS
    # ========================================================

    print("\n[3] Loading validation embeddings")

    professor_val = load_embeddings(
        app,
        PROFESSOR,
        VAL
    )

    known_val = load_embeddings(
        app,
        KNOWN_USERS,
        VAL
    )

    # ========================================================
    # 4. PROFESSOR VALIDATION SCORES
    # ========================================================

    professor_genuine_val = get_scores(
        professor_val,
        professor_template
    )

    known_as_professor_val = get_scores(
        known_val,
        professor_template
    )

    print_distribution(
        "Professor -> Professor template",
        professor_genuine_val
    )

    print_distribution(
        "Known User -> Professor template",
        known_as_professor_val
    )

    # ========================================================
    # 5. KNOWN USER VALIDATION SCORES
    # ========================================================

    known_genuine_val = get_scores(
        known_val,
        known_template
    )

    professor_as_known_val = get_scores(
        professor_val,
        known_template
    )

    print_distribution(
        "Known User -> Known-user template",
        known_genuine_val
    )

    print_distribution(
        "Professor -> Known-user template",
        professor_as_known_val
    )

    # ========================================================
    # 6. PROFESSOR THRESHOLD
    #
    # Professor should be accepted.
    # Known users should NOT be classified as professor.
    # ========================================================

    professor_threshold_result = find_threshold(
        professor_genuine_val,
        known_as_professor_val
    )

    professor_threshold = (
        professor_threshold_result["threshold"]
    )

    # ========================================================
    # 7. KNOWN USER THRESHOLD
    #
    # Known users should be accepted.
    # Professor should NOT be classified as known user.
    # ========================================================

    known_threshold_result = find_threshold(
        known_genuine_val,
        professor_as_known_val
    )

    known_threshold = (
        known_threshold_result["threshold"]
    )

    # ========================================================
    # 8. PRINT THRESHOLDS
    # ========================================================

    print("\n" + "=" * 70)
    print("VALIDATION THRESHOLDS")
    print("=" * 70)

    print(
        f"Professor threshold : "
        f"{professor_threshold:.6f}"
    )

    print(
        f"Known-user threshold: "
        f"{known_threshold:.6f}"
    )

    print("\nProfessor verification:")
    print(
        f"FAR = "
        f"{professor_threshold_result['far']:.4f}"
    )

    print(
        f"FRR = "
        f"{professor_threshold_result['frr']:.4f}"
    )

    print("\nKnown-user verification:")
    print(
        f"FAR = "
        f"{known_threshold_result['far']:.4f}"
    )

    print(
        f"FRR = "
        f"{known_threshold_result['frr']:.4f}"
    )

    # ========================================================
    # 9. TEST EMBEDDINGS
    # ========================================================

    print("\n[4] Loading test embeddings")

    professor_test = load_embeddings(
        app,
        PROFESSOR,
        TEST
    )

    known_test = load_embeddings(
        app,
        KNOWN_USERS,
        TEST
    )

    # ========================================================
    # 10. TEST SCORES
    # ========================================================

    professor_as_professor_test = get_scores(
        professor_test,
        professor_template
    )

    professor_as_known_test = get_scores(
        professor_test,
        known_template
    )

    known_as_known_test = get_scores(
        known_test,
        known_template
    )

    known_as_professor_test = get_scores(
        known_test,
        professor_template
    )

    # ========================================================
    # 11. PROFESSOR TEST METRICS
    # ========================================================

    professor_frr = np.mean(
        professor_as_professor_test
        < professor_threshold
    )

    known_as_professor_far = np.mean(
        known_as_professor_test
        >= professor_threshold
    )

    professor_accept_rate = (
        1.0 - professor_frr
    )

    # ========================================================
    # 12. KNOWN USER TEST METRICS
    # ========================================================

    known_frr = np.mean(
        known_as_known_test
        < known_threshold
    )

    professor_as_known_far = np.mean(
        professor_as_known_test
        >= known_threshold
    )

    known_accept_rate = (
        1.0 - known_frr
    )

    # ========================================================
    # 13. PRINT FINAL RESULTS
    # ========================================================

    print("\n" + "=" * 70)
    print("FINAL TEST RESULTS")
    print("=" * 70)

    print(
        f"\nProfessor threshold : "
        f"{professor_threshold:.6f}"
    )

    print(
        f"Known-user threshold: "
        f"{known_threshold:.6f}"
    )

    print("\n--- PROFESSOR ---")

    print(
        f"Professor accepted : "
        f"{professor_accept_rate:.2%}"
    )

    print(
        f"Professor rejected : "
        f"{professor_frr:.2%}"
    )

    print(
        f"Known users accepted as professor: "
        f"{known_as_professor_far:.2%}"
    )

    print("\n--- KNOWN USER ---")

    print(
        f"Known user accepted : "
        f"{known_accept_rate:.2%}"
    )

    print(
        f"Known user rejected : "
        f"{known_frr:.2%}"
    )

    print(
        f"Professor accepted as known user: "
        f"{professor_as_known_far:.2%}"
    )

    # ========================================================
    # 14. SAVE MODEL
    # ========================================================

    model = {

        "model":
            "InsightFace buffalo_l",

        "method":
            "ArcFace embedding + cosine similarity",

        "classification":
            "Professor / Known User / Unknown User",

        "classes": {

            "class_A":
                "Known Users",

            "class_B":
                "Professor",

            "unknown":
                "Anyone not sufficiently similar to either template"
        },

        "professor_threshold":
            float(professor_threshold),

        "known_user_threshold":
            float(known_threshold),

        "professor_template":
            professor_template.tolist(),

        "known_user_template":
            known_template.tolist(),

        "validation": {

            "professor": {

                "FAR":
                    float(
                        professor_threshold_result["far"]
                    ),

                "FRR":
                    float(
                        professor_threshold_result["frr"]
                    ),
            },

            "known_user": {

                "FAR":
                    float(
                        known_threshold_result["far"]
                    ),

                "FRR":
                    float(
                        known_threshold_result["frr"]
                    ),
            },
        },

        "test": {

            "professor_accept_rate":
                float(professor_accept_rate),

            "professor_reject_rate":
                float(professor_frr),

            "known_user_as_professor_rate":
                float(known_as_professor_far),

            "known_user_accept_rate":
                float(known_accept_rate),

            "known_user_reject_rate":
                float(known_frr),

            "professor_as_known_user_rate":
                float(professor_as_known_far),
        },
    }

    output = Path(
        "face_auth_model.json"
    )

    output.write_text(
        json.dumps(
            model,
            indent=2
        )
    )

    print(
        f"\nSaved model -> {output}"
    )

    print("\n" + "=" * 70)
    print("3-WAY AUTHENTICATION LOGIC")
    print("=" * 70)

    print(
        "\n1. similarity >= professor_threshold"
    )

    print(
        "   -> PROFESSOR"
    )

    print(
        "\n2. otherwise similarity >= known_user_threshold"
    )

    print(
        "   -> KNOWN USER"
    )

    print(
        "\n3. otherwise"
    )

    print(
        "   -> UNKNOWN USER"
    )


# ============================================================
# ENTRY POINT
# ============================================================

if __name__ == "__main__":
    main()