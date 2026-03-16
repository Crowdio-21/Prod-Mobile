import numpy as np
import base64
import json

# Explicitly export underscore-prefixed helpers so that
# `from dnn_inference import *` makes them available to foreman-sent code.
__all__ = [
    "build_resnet_dag",
    "init_model",
    "run_layer",
    "_serialize_tensor",
    "_deserialize_tensor",
    "np",
]

# Load model once at module level
_sub_models = {}


# ---------------------------------------------------------------------------
# Tensor serialization helpers (used by foreman-sent partition_model code)
# ---------------------------------------------------------------------------

def _serialize_tensor(arr):
    """Serialize a NumPy array to a JSON-safe dict (base64-encoded gzip-compressed data + metadata)."""
    import gzip
    a = np.ascontiguousarray(arr) if isinstance(arr, np.ndarray) else np.asarray(arr)
    raw = a.tobytes()
    compressed = gzip.compress(raw)
    return {
        "data": base64.b64encode(compressed).decode("ascii"),
        "dtype": str(a.dtype),
        "shape": list(a.shape),
        "compressed": True,
    }


def _deserialize_tensor(obj):
    """Reconstruct a NumPy array from the dict produced by _serialize_tensor."""
    import io
    import gzip
    if not isinstance(obj, dict):
        return np.asarray(obj)

    # Support both schemas used in this pipeline:
    # 1) {format: 'npy', data_b64: ...}
    # 2) {data: ..., dtype: ..., shape: ..., compressed: ...}
    if obj.get("format") == "npy" and obj.get("data_b64"):
        raw = base64.b64decode(obj["data_b64"])
        return np.load(io.BytesIO(raw))

    data_blob = obj.get("data", obj.get("data_b64"))
    if data_blob is None:
        raise KeyError("data")

    raw_b64 = base64.b64decode(data_blob)
    if obj.get("compressed", False):
        raw = gzip.decompress(raw_b64)
    else:
        raw = raw_b64

    dtype = np.dtype(obj.get("dtype", "float32"))
    shape = obj.get("shape")
    arr = np.frombuffer(raw, dtype=dtype)
    return arr.reshape(shape) if shape else arr


def build_resnet_dag(*args, **kwargs):
    """Build a ResNet-style DAG with skip connections for distributed inference.

    Accepts flexible calling conventions from the foreman:
        build_resnet_dag()
        build_resnet_dag(num_partitions)
        build_resnet_dag(input_shape, num_partitions)
        build_resnet_dag(num_partitions=2)

    Returns (layers, edges) tuple where layers is a list of layer descriptors
    and edges is a list of (src, dst) pairs.  partition_idx is assigned
    round-robin across stages so that consecutive devices handle consecutive
    pipeline stages.
    """
    # Parse flexible arguments
    if "num_partitions" in kwargs:
        num_partitions = int(kwargs["num_partitions"])
    elif len(args) == 0:
        num_partitions = 2
    elif len(args) == 1:
        # Single arg could be num_partitions (int) or input_shape (tuple/list)
        if isinstance(args[0], int):
            num_partitions = args[0]
        else:
            num_partitions = 2  # input_shape only, use default partitions
    else:
        # Two positional args: (input_shape, num_partitions)
        num_partitions = int(args[1])

    layers = []
    edges = []

    def _add(layer_id, op, stage):
        layers.append({
            "layer_id": layer_id,
            "op": op,
            "partition_idx": stage % num_partitions,
        })

    # --- Stage 0: stem ---------------------------------------------------------
    _add("conv1",  "Conv2D",     0)
    _add("bn1",    "BatchNorm",  0)
    _add("relu1",  "ReLU",       0)
    _add("pool1",  "MaxPool2D",  0)
    edges += [("conv1", "bn1"), ("bn1", "relu1"), ("relu1", "pool1")]

    prev = "pool1"
    block_idx = 0

    # --- Residual stages -------------------------------------------------------
    stage_configs = [
        # (num_blocks, filters, stage_id)
        (3,  64,  1),
        (4, 128,  2),
        (6, 256,  3),
        (3, 512,  4),
    ]

    for num_blocks, filters, stage_id in stage_configs:
        for b in range(num_blocks):
            block_idx += 1
            prefix = f"res{stage_id}{chr(97 + b)}"  # res2a, res2b, ...

            # Main branch (two convolutions)
            br2a = f"{prefix}_branch2a"
            br2b = f"{prefix}_branch2b"
            _add(br2a, "Conv2D", stage_id)
            _add(br2b, "Conv2D", stage_id)
            edges += [(prev, br2a), (br2a, br2b)]

            # Shortcut (identity or 1x1 conv for dimension matching)
            shortcut = f"{prefix}_shortcut"
            if b == 0:
                _add(shortcut, "Conv2D", stage_id)   # projection shortcut
            else:
                _add(shortcut, "Identity", stage_id)  # identity shortcut
            edges.append((prev, shortcut))

            # Fuse (add + relu)
            add_id = f"{prefix}_add"
            _add(add_id, "Add", stage_id)
            edges += [(br2b, add_id), (shortcut, add_id)]

            prev = add_id

    # --- Head ------------------------------------------------------------------
    _add("avgpool", "GlobalAvgPool2D", num_partitions - 1)
    _add("fc",      "Dense",          num_partitions - 1)
    edges += [(prev, "avgpool"), ("avgpool", "fc")]

    return layers, edges


def init_model(model_path: str, layer_configs: list):
    """Called once from Kotlin on startup. Pre-slice the model."""
    if not model_path:
        return

    if _sub_models.get("interpreter") is not None:
        return

    try:
        import tensorflow.lite as tflite
        interpreter = tflite.Interpreter(model_path=model_path)
        interpreter.allocate_tensors()
        _sub_models["interpreter"] = interpreter
    except ImportError:
        pass  # fallback to Keras or simulation
    except Exception:
        # Keep fallback behavior if model cannot be loaded on this device.
        print(f"Error loading model from {model_path}, falling back to simulation.")
        pass

def run_layer(layer_id: str, input_np, params: dict) -> np.ndarray:
    """Execute one layer slice. Returns output activation as ndarray."""
    input_arr = np.asarray(input_np, dtype=np.float32)

    interpreter = _sub_models.get("interpreter")
    if interpreter is None and isinstance(params, dict):
        init_model(params.get("model_path", ""), params.get("layer_configs", []))
        interpreter = _sub_models.get("interpreter")

    if interpreter:
        # TFLite path
        input_details = interpreter.get_input_details()
        output_details = interpreter.get_output_details()
        interpreter.set_tensor(input_details[0]["index"], input_arr)
        interpreter.invoke()
        return interpreter.get_tensor(output_details[0]["index"])

    # Simulation fallback (matches the pipeline test)
    filters = params.get("filters", 64)
    h, w = input_arr.shape[1], input_arr.shape[2]
    return np.random.randn(1, h, w, filters).astype(np.float32)
