"""Build a deliberately wrong model, so the skew guard can be seen refusing one.

    .venv-ml/Scripts/python ml/make_negative_fixture.py

`OnnxScorer` refuses to serve a model whose schema fingerprint does not match the
build's. That refusal is the single most important behaviour in phase 8, and
until it has been watched happening it is an assumption.

This writes a tiny model — five trees, a few kilobytes — carrying a fingerprint
that is deliberately not this schema's. `OnnxScorerRefusalTest` loads it and
asserts the refusal, so the guard is exercised on every build rather than
reasoned about once.

Kept small on purpose: it exists to be rejected, so its predictions are never
read and a full-sized model would be several hundred kilobytes of committed
binary nobody will ever score with.
"""

from __future__ import annotations

from pathlib import Path

import lightgbm as lgb
import numpy as np
import onnxmltools
from onnxmltools.convert.common.data_types import FloatTensorType

REPO_ROOT = Path(__file__).resolve().parent.parent
OUTPUT = REPO_ROOT / "models" / "ranker-wrong-schema.onnx"

# Not this build's fingerprint, and obviously not by accident.
WRONG_FINGERPRINT = "deadbeefdeadbeef"

FEATURES = 8


def main() -> None:
    rng = np.random.default_rng(20260806)
    x = rng.random((200, FEATURES), dtype=np.float32)
    y = x[:, 0]

    model = lgb.LGBMRegressor(n_estimators=5, num_leaves=4, min_child_samples=5, random_state=1, verbose=-1)
    model.fit(x, y)

    onnx_model = onnxmltools.convert_lightgbm(
        model.booster_,
        initial_types=[("input", FloatTensorType([None, FEATURES]))],
        target_opset=13,
        zipmap=False,
    )
    entry = onnx_model.metadata_props.add()
    entry.key = "plotted_schema_fingerprint"
    entry.value = WRONG_FINGERPRINT

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    onnxmltools.utils.save_model(onnx_model, str(OUTPUT))
    print(f"wrote {OUTPUT} ({OUTPUT.stat().st_size} bytes) with fingerprint {WRONG_FINGERPRINT}")


if __name__ == "__main__":
    main()
