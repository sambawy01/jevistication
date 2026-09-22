#!/usr/bin/env python3
"""
Generates the tiny ONNX classifier used to test OnnxBackend.

This is NOT a model of anything. It is a deterministic two-label graph whose output depends on
its input, which is all that is needed to prove the loading, tensor-marshalling and softmax path
end to end without the real weights (which this environment's network policy blocks).

Regenerate with:  python3 tools/make-synthetic-onnx.py
"""
import onnx
from onnx import TensorProto, helper, numpy_helper
import numpy as np

LABELS = 2

# Weights chosen so the two logits diverge as the summed input ids grow.
weight = numpy_helper.from_array(
    np.array([[0.01, -0.01]], dtype=np.float32), name="W"
)
bias = numpy_helper.from_array(np.array([0.0, 0.5], dtype=np.float32), name="B")
axes = numpy_helper.from_array(np.array([1], dtype=np.int64), name="axes")

nodes = [
    helper.make_node("Cast", ["input_ids"], ["ids_f"], to=TensorProto.FLOAT),
    helper.make_node("ReduceSum", ["ids_f", "axes"], ["summed"], keepdims=1),
    helper.make_node("MatMul", ["summed", "W"], ["projected"]),
    helper.make_node("Add", ["projected", "B"], ["logits"]),
]

graph = helper.make_graph(
    nodes,
    "synthetic-classifier",
    inputs=[
        helper.make_tensor_value_info("input_ids", TensorProto.INT64, [1, "seq"]),
        helper.make_tensor_value_info("attention_mask", TensorProto.INT64, [1, "seq"]),
    ],
    outputs=[helper.make_tensor_value_info("logits", TensorProto.FLOAT, [1, LABELS])],
    initializer=[weight, bias, axes],
)

model = helper.make_model(
    graph,
    producer_name="loupe-tests",
    opset_imports=[helper.make_opsetid("", 13)],
)
model.ir_version = 9  # keep within what ONNX Runtime 1.20 accepts
onnx.checker.check_model(model)
onnx.save(model, "engine/src/test/resources/synthetic-classifier.onnx")
print("wrote engine/src/test/resources/synthetic-classifier.onnx")
