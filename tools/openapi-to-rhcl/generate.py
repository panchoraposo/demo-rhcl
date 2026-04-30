#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import os
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, Iterable, List, Mapping, Optional, Tuple


def _load_yaml_or_json(path: Path) -> Dict[str, Any]:
    text = path.read_text(encoding="utf-8")
    if path.suffix.lower() in {".json"}:
        return json.loads(text)

    try:
        import yaml  # type: ignore
    except Exception as e:  # pragma: no cover
        raise SystemExit(
            "PyYAML is required to read YAML specs. Install it with:\n"
            "  python -m pip install -r tools/openapi-to-rhcl/requirements.txt"
        ) from e

    return yaml.safe_load(text)


def _dump_yaml(path: Path, obj: Any) -> None:
    try:
        import yaml  # type: ignore
    except Exception as e:  # pragma: no cover
        raise SystemExit(
            "PyYAML is required to write YAML. Install it with:\n"
            "  python -m pip install -r tools/openapi-to-rhcl/requirements.txt"
        ) from e

    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        yaml.safe_dump(obj, sort_keys=False),
        encoding="utf-8",
    )


def _sanitize_k8s_name(s: str) -> str:
    s = s.strip().lower()
    s = re.sub(r"[^a-z0-9-]+", "-", s)
    s = re.sub(r"-{2,}", "-", s)
    s = s.strip("-")
    if not s:
        return "openapi"
    return s[:63]


def _path_to_prefix(openapi_path: str) -> str:
    # Gateway API path match does not understand OpenAPI templates.
    # For /pets/{id} we route by prefix /pets/.
    if "{" in openapi_path:
        prefix = openapi_path.split("{", 1)[0]
        return prefix if prefix.endswith("/") else prefix + "/"
    return openapi_path


def _iter_operations(paths: Mapping[str, Any]) -> Iterable[Tuple[str, str]]:
    for p, item in (paths or {}).items():
        if not isinstance(item, dict):
            continue
        for method, op in item.items():
            if method.lower() not in {"get", "put", "post", "delete", "patch", "head", "options", "trace"}:
                continue
            if not isinstance(op, dict):
                continue
            yield (p, method.upper())


def _auth_when_predicate(allow_paths: List[str]) -> str:
    # Kuadrant predicate language is expression-ish strings.
    # For demos we keep it simple: exclude exact paths with chained ANDs.
    if not allow_paths:
        return "true"
    parts = [f"request.path != '{p}'" for p in allow_paths]
    return " && ".join(parts)


def _ratelimit_when_predicates(base_when: List[str], allow_paths: List[str]) -> List[Dict[str, str]]:
    preds: List[str] = []
    for p in allow_paths:
        preds.append(f"request.path != '{p}'")
    preds.extend(base_when or [])
    return [{"predicate": p} for p in preds]


@dataclass(frozen=True)
class RhclConfig:
    namespace: str
    route_name: str
    labels: Dict[str, str]
    gateway_name: str
    gateway_namespace: str
    backend_service: str
    backend_port: int
    allow_unauth_paths: List[str]
    api_key_prefix: str
    api_key_selector_labels: Dict[str, str]
    ratelimit_counter: str
    ratelimit_limits: Dict[str, Any]


def _parse_x_rhcl(spec: Mapping[str, Any]) -> RhclConfig:
    x = spec.get("x-rhcl")
    if not isinstance(x, dict):
        raise SystemExit("OpenAPI spec must include a top-level 'x-rhcl' object.")

    namespace = str(x.get("namespace") or "openapi-demo")
    route_name = _sanitize_k8s_name(str(x.get("routeName") or x.get("name") or spec.get("info", {}).get("title") or "openapi-demo"))
    labels = dict(x.get("labels") or {"app": route_name})

    gw = x.get("gatewayRef") or {}
    gateway_name = str(getattr(gw, "get", lambda _k, _d=None: _d)("name", "rhcl-gw"))  # type: ignore
    gateway_namespace = str(getattr(gw, "get", lambda _k, _d=None: _d)("namespace", "openshift-ingress"))  # type: ignore

    backend = x.get("backend") or {}
    backend_service = str(getattr(backend, "get", lambda _k, _d=None: _d)("serviceName", route_name))  # type: ignore
    backend_port = int(getattr(backend, "get", lambda _k, _d=None: _d)("port", 80))  # type: ignore

    auth = x.get("auth") or {}
    allow_unauth_paths = list(getattr(auth, "get", lambda _k, _d=None: _d)("allowUnauthenticatedPaths", ["/health"]))  # type: ignore
    api_key = getattr(auth, "get", lambda _k, _d=None: _d)("apiKey", {})  # type: ignore
    api_key_prefix = str(getattr(api_key, "get", lambda _k, _d=None: _d)("authorizationHeaderPrefix", "APIKEY"))  # type: ignore
    api_key_selector_labels = dict(getattr(api_key, "get", lambda _k, _d=None: _d)("selectorLabels", labels))  # type: ignore

    rl = x.get("rateLimit") or {}
    ratelimit_counter = str(getattr(rl, "get", lambda _k, _d=None: _d)("counterExpression", "auth.identity.userid"))  # type: ignore
    ratelimit_limits = dict(getattr(rl, "get", lambda _k, _d=None: _d)("limits", {}))  # type: ignore
    if not ratelimit_limits:
        ratelimit_limits = {
            "general-user": {
                "rates": [{"limit": 5, "window": "10s"}],
                "when": ["auth.identity.userid != 'bob'"],
            },
            "bob-limit": {
                "rates": [{"limit": 2, "window": "10s"}],
                "when": ["auth.identity.userid == 'bob'"],
            },
        }

    return RhclConfig(
        namespace=namespace,
        route_name=route_name,
        labels=labels,
        gateway_name=gateway_name,
        gateway_namespace=gateway_namespace,
        backend_service=backend_service,
        backend_port=backend_port,
        allow_unauth_paths=allow_unauth_paths,
        api_key_prefix=api_key_prefix,
        api_key_selector_labels=api_key_selector_labels,
        ratelimit_counter=ratelimit_counter,
        ratelimit_limits=ratelimit_limits,
    )


def _make_httproute(cfg: RhclConfig, operations: List[Tuple[str, str]]) -> Dict[str, Any]:
    matches: List[Dict[str, Any]] = []
    seen: set[Tuple[str, str]] = set()
    for path, method in operations:
        prefix = _path_to_prefix(path)
        key = (prefix, method)
        if key in seen:
            continue
        seen.add(key)
        matches.append(
            {
                "method": method,
                "path": {"type": "PathPrefix", "value": prefix},
            }
        )

    return {
        "apiVersion": "gateway.networking.k8s.io/v1",
        "kind": "HTTPRoute",
        "metadata": {
            "name": cfg.route_name,
            "namespace": cfg.namespace,
            "labels": cfg.labels,
            "annotations": {"argocd.argoproj.io/sync-wave": "30"},
        },
        "spec": {
            "parentRefs": [
                {
                    "group": "gateway.networking.k8s.io",
                    "kind": "Gateway",
                    "name": cfg.gateway_name,
                    "namespace": cfg.gateway_namespace,
                }
            ],
            "rules": [
                {
                    "matches": matches,
                    "backendRefs": [
                        {
                            "group": "",
                            "kind": "Service",
                            "name": cfg.backend_service,
                            "port": cfg.backend_port,
                            "weight": 1,
                        }
                    ],
                }
            ],
        },
    }


def _make_authpolicy(cfg: RhclConfig) -> Dict[str, Any]:
    when = _auth_when_predicate(cfg.allow_unauth_paths)
    return {
        "apiVersion": "kuadrant.io/v1",
        "kind": "AuthPolicy",
        "metadata": {
            "name": f"{cfg.route_name}-auth",
            "namespace": cfg.namespace,
            "annotations": {
                "argocd.argoproj.io/sync-wave": "32",
                "argocd.argoproj.io/sync-options": "SkipDryRunOnMissingResource=true",
            },
        },
        "spec": {
            "targetRef": {
                "group": "gateway.networking.k8s.io",
                "kind": "HTTPRoute",
                "name": cfg.route_name,
            },
            "defaults": {
                "when": [{"predicate": when}],
                "rules": {
                    "authentication": {
                        "api-key-users": {
                            "apiKey": {
                                "allNamespaces": True,
                                "selector": {"matchLabels": cfg.api_key_selector_labels},
                            },
                            "credentials": {
                                "authorizationHeader": {"prefix": cfg.api_key_prefix}
                            },
                        }
                    },
                    "response": {
                        "success": {
                            "filters": {
                                "identity": {
                                    "json": {
                                        "properties": {
                                            "userid": {
                                                "selector": "auth.identity.metadata.annotations.secret\\.kuadrant\\.io/user-id"
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    },
                },
            },
        },
    }


def _make_ratelimitpolicy(cfg: RhclConfig) -> Dict[str, Any]:
    limits: Dict[str, Any] = {}
    for limit_name, limit_cfg in cfg.ratelimit_limits.items():
        if not isinstance(limit_cfg, dict):
            continue
        rates = limit_cfg.get("rates") or [{"limit": 5, "window": "10s"}]
        base_when = limit_cfg.get("when") or []
        if isinstance(base_when, str):
            base_when = [base_when]
        if not isinstance(base_when, list):
            base_when = []
        limits[limit_name] = {
            "rates": rates,
            "counters": [{"expression": cfg.ratelimit_counter}],
            "when": _ratelimit_when_predicates([str(x) for x in base_when], cfg.allow_unauth_paths),
        }

    return {
        "apiVersion": "kuadrant.io/v1",
        "kind": "RateLimitPolicy",
        "metadata": {
            "name": f"{cfg.route_name}-rlp",
            "namespace": cfg.namespace,
            "annotations": {
                "argocd.argoproj.io/sync-wave": "33",
                "argocd.argoproj.io/sync-options": "SkipDryRunOnMissingResource=true",
            },
        },
        "spec": {
            "targetRef": {
                "group": "gateway.networking.k8s.io",
                "kind": "HTTPRoute",
                "name": cfg.route_name,
            },
            "limits": limits,
        },
    }


def main() -> int:
    ap = argparse.ArgumentParser(description="Generate RHCL/Kuadrant manifests from OpenAPI.")
    ap.add_argument("--spec", required=True, help="Path to OpenAPI YAML/JSON spec.")
    ap.add_argument("--out", required=True, help="Output directory for generated YAMLs.")
    args = ap.parse_args()

    spec_path = Path(args.spec)
    out_dir = Path(args.out)
    spec = _load_yaml_or_json(spec_path)
    cfg = _parse_x_rhcl(spec)
    operations = list(_iter_operations(spec.get("paths") or {}))
    if not operations:
        raise SystemExit("OpenAPI spec contains no operations under 'paths'.")

    _dump_yaml(out_dir / "30-httproute.yaml", _make_httproute(cfg, operations))
    _dump_yaml(out_dir / "60-authpolicy.yaml", _make_authpolicy(cfg))
    _dump_yaml(out_dir / "70-ratelimitpolicy.yaml", _make_ratelimitpolicy(cfg))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

