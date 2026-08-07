"""Train the ranking model and export it for in-process serving.

    ./gradlew :plotted-api:exportTrainingData
    .venv-ml/Scripts/python ml/train_ranker.py

This script deliberately **does not compute a single feature**. It reads columns
produced by `FeatureSchema` on the JVM side, which is the only implementation of
feature extraction that exists anywhere in this project.

That is the opposite of the usual arrangement, where features are computed in
Python for training and reimplemented in the serving language for inference.
Those two implementations start identical and then drift -- a bug fixed on one
side, a unit change, a different missing-value convention -- and the model gets
quietly worse with nothing throwing. Here there is nothing to drift.

What this script *can* still get wrong is the export: column order, the
missing-value convention, or the LightGBM -> ONNX conversion mishandling NaN. So
it writes golden vectors, and `GoldenVectorTest` replays them through the JVM
scorer and fails the build if any score differs.

See docs/MODEL.md for what this model is and, more importantly, is not.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from datetime import date
from pathlib import Path

import lightgbm as lgb
import numpy as np
import onnxmltools
from onnxmltools.convert.common.data_types import FloatTensorType

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_DATASET = REPO_ROOT / "plotted-api" / "build" / "training" / "dataset.csv"
DEFAULT_MODEL = REPO_ROOT / "models" / "ranker.onnx"
DEFAULT_GOLDEN = REPO_ROOT / "models" / "golden-vectors.json"

LABEL_COLUMN = "label"

# How many rows the golden-vector file carries. Enough to cover every branch the
# trees actually take; small enough that a failure names a specific row rather
# than reporting a percentage.
GOLDEN_ROWS = 200

# Metadata keys. These are read by OnnxScorer.load -- change one and the model
# stops loading, which is the intended blast radius.
FINGERPRINT_KEY = "plotted_schema_fingerprint"
SCHEMA_VERSION_KEY = "plotted_schema_version"
MODEL_VERSION_KEY = "plotted_model_version"
TRAINED_ON_KEY = "plotted_trained_on"


def schema_from_jvm() -> tuple[str, str, list[str]]:
    """Ask the JVM for the schema rather than restating it here.

    A constant in this file saying which features exist would be a second
    declaration of the contract, and therefore a second thing that can be wrong.
    The export task prints the authoritative values; this parses them.
    """
    gradlew = REPO_ROOT / ("gradlew.bat" if sys.platform == "win32" else "gradlew")
    result = subprocess.run(
        [str(gradlew), ":plotted-api:exportTrainingData", "--console=plain", "-q"],
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
        check=True,
    )
    values = {}
    for line in result.stdout.splitlines():
        if "=" in line:
            key, _, value = line.partition("=")
            values[key.strip()] = value.strip()
    return (
        values["schema_fingerprint"],
        values["schema_version"],
        values["features"].split(","),
    )


def load_dataset(path: Path, features: list[str]) -> tuple[np.ndarray, np.ndarray]:
    """Read the CSV the JVM wrote, and refuse it if the columns disagree.

    Checked rather than assumed: a silently reordered column is the exact bug
    this whole design exists to prevent, and reading positionally without
    verifying the header would reintroduce it inside the guard.
    """
    with path.open(encoding="utf-8") as handle:
        header = handle.readline().strip().split(",")

    expected = features + [LABEL_COLUMN]
    if header != expected:
        raise SystemExit(
            f"Dataset columns do not match the schema.\n"
            f"  dataset: {header}\n  schema:  {expected}\n"
            f"Re-run ./gradlew :plotted-api:exportTrainingData"
        )

    # Empty string is missing; genfromtxt turns it into NaN, which is what
    # LightGBM treats as absent. Any other convention -- 0, -1, a sentinel --
    # would teach the model that "unknown" is a specific, wrong value.
    raw = np.genfromtxt(path, delimiter=",", skip_header=1, dtype=np.float32)
    return raw[:, : len(features)], raw[:, len(features)]


def train(x: np.ndarray, y: np.ndarray) -> lgb.LGBMRegressor:
    """Fit, holding out a slice to report honest error.

    A model reported on its training rows is a model reported on rows it has
    memorised. The split is by position rather than at random because the rows
    come out of the exporter grouped by query, so a random split would put
    candidates from the same query on both sides and leak.
    """
    split = int(len(x) * 0.8)
    model = lgb.LGBMRegressor(
        n_estimators=200,
        learning_rate=0.05,
        num_leaves=31,
        min_child_samples=40,
        random_state=20260806,
        verbose=-1,
    )
    model.fit(
        x[:split],
        y[:split],
        eval_set=[(x[split:], y[split:])],
        eval_metric="l2",
        callbacks=[lgb.early_stopping(20, verbose=False)],
    )
    held_out = model.predict(x[split:])
    rmse = float(np.sqrt(np.mean((held_out - y[split:]) ** 2)))
    print(f"held-out RMSE: {rmse:.6f} over {len(x) - split} rows")
    return model


def export(model: lgb.LGBMRegressor, features: list[str], fingerprint: str, schema_version: str, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    onnx_model = onnxmltools.convert_lightgbm(
        model.booster_,
        initial_types=[("input", FloatTensorType([None, len(features)]))],
        # Pinned rather than left to default, so a converter upgrade cannot
        # silently change the exported graph under a model that still loads.
        target_opset=13,
        zipmap=False,
    )
    # Worth knowing when reading GoldenVectorTest: the output is float32.
    # Adjacent representable values near a score of 0.9 are about 6e-8 apart, so
    # any equivalence tolerance below that is asserting something finer than the
    # type can express. The first version of the JVM-side batch test demanded
    # 1e-9 and failed for exactly that reason.

    # The metadata OnnxScorer checks before it will serve anything.
    for key, value in (
        (FINGERPRINT_KEY, fingerprint),
        (SCHEMA_VERSION_KEY, schema_version),
        (MODEL_VERSION_KEY, f"distill-{date.today().isoformat()}"),
        (TRAINED_ON_KEY, "synthetic distillation of linear-v1; see docs/MODEL.md"),
    ):
        entry = onnx_model.metadata_props.add()
        entry.key = key
        entry.value = value

    onnxmltools.utils.save_model(onnx_model, str(path))
    print(f"wrote {path} ({path.stat().st_size} bytes)")


def write_golden_vectors(model: lgb.LGBMRegressor, x: np.ndarray, path: Path, fingerprint: str, features: list[str]) -> None:
    """Sample inputs and the scores this model gave them.

    Sampled across the dataset rather than taken from the head, so the rows
    exercise different branches of the trees. Rows containing NaN are
    *deliberately* included: whether missing values survive the LightGBM -> ONNX
    conversion is a real risk, and a golden set of complete rows would prove
    nothing about the case most likely to be wrong.
    """
    step = max(1, len(x) // GOLDEN_ROWS)
    sample = x[::step][:GOLDEN_ROWS]
    scores = model.predict(sample)

    with_missing = int(np.isnan(sample).any(axis=1).sum())
    print(f"golden vectors: {len(sample)} rows, {with_missing} containing missing values")
    if with_missing == 0:
        raise SystemExit(
            "No golden vector contains a missing value, so the set cannot detect "
            "a NaN-handling regression. Widen the sample."
        )

    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(
            {
                "schemaFingerprint": fingerprint,
                "features": features,
                # null rather than NaN: JSON has no NaN literal, and emitting a
                # bare NaN produces a file that most parsers reject and some
                # accept as a string.
                "vectors": [[None if np.isnan(v) else float(v) for v in row] for row in sample],
                "scores": [float(s) for s in scores],
            },
            indent=2,
        ),
        encoding="utf-8",
    )
    print(f"wrote {path}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--model", type=Path, default=DEFAULT_MODEL)
    parser.add_argument("--golden", type=Path, default=DEFAULT_GOLDEN)
    parser.add_argument("--skip-export", action="store_true", help="use the dataset already on disk")
    args = parser.parse_args()

    if args.skip_export:
        raise SystemExit("--skip-export still needs the schema; run without it at least once")

    fingerprint, schema_version, features = schema_from_jvm()
    print(f"schema {schema_version} ({fingerprint}): {', '.join(features)}")

    x, y = load_dataset(args.dataset, features)
    print(f"dataset: {x.shape[0]} rows, {x.shape[1]} features")

    model = train(x, y)
    export(model, features, fingerprint, schema_version, args.model)
    write_golden_vectors(model, x, args.golden, fingerprint, features)


if __name__ == "__main__":
    main()
