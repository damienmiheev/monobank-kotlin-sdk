#!/usr/bin/env python3
"""Generates JSON test fixtures from an OpenAPI spec's per-field examples.

Usage: python3 -I tools/fixtures_from_openapi.py <spec.json> <out-dir>

For every operation it writes <out-dir>/<METHOD>_<path>.request.json and .response.json
(200 answer) built from field `example` values. Fields without an example get a value from
their type: first enum value, a fixed date-time, 0, true, "string". The output is a
starting point; tests then edit fixtures to cover edge cases.
"""
import json
import re
import sys
from pathlib import Path

DATE_TIME = "2024-05-01T12:30:00+03:00"


def main() -> None:
    spec = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    out = Path(sys.argv[2])
    out.mkdir(parents=True, exist_ok=True)
    schemas = spec.get("components", {}).get("schemas", {})

    def resolve(node):
        seen = 0
        while isinstance(node, dict) and "$ref" in node and seen < 20:
            node = schemas[node["$ref"].split("/")[-1]]
            seen += 1
        return node

    def sample(node, depth=0):
        node = resolve(node)
        if depth > 8 or not isinstance(node, dict):
            return None
        if "example" in node:
            return node["example"]
        if "enum" in node and node["enum"]:
            return node["enum"][0]
        kind = node.get("type")
        if kind == "object" or "properties" in node:
            return {k: sample(v, depth + 1) for k, v in node.get("properties", {}).items()}
        if kind == "array":
            return [sample(node.get("items", {}), depth + 1)]
        if kind == "string":
            return DATE_TIME if node.get("format") == "date-time" else "string"
        if kind == "integer":
            return 0
        if kind == "number":
            return 0.0
        if kind == "boolean":
            return True
        return None

    for path, ops in spec.get("paths", {}).items():
        for method, op in ops.items():
            if method.lower() not in ("get", "post", "put", "delete", "patch"):
                continue
            name = method.upper() + "_" + re.sub(r"[^A-Za-z0-9]+", "_", path.split("?")[0]).strip("_")
            body = op.get("requestBody", {}).get("content", {})
            if body:
                schema = next(iter(body.values())).get("schema", {})
                (out / f"{name}.request.json").write_text(
                    json.dumps(sample(schema), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            ok = op.get("responses", {}).get("200", {}).get("content", {})
            if ok:
                schema = next(iter(ok.values())).get("schema", {})
                (out / f"{name}.response.json").write_text(
                    json.dumps(sample(schema), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
