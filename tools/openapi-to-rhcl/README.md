# OpenAPI → RHCL (Kuadrant) manifests generator

This tool turns an **OpenAPI 3** document into GitOps-ready manifests for:

- `HTTPRoute` (Gateway API)
- `AuthPolicy` (Kuadrant) for API key auth
- `RateLimitPolicy` (Kuadrant) for identity-aware rate limiting

It is meant for **live demos** where the OpenAPI spec is the source of truth and the output is applied via **GitOps/Kustomize**.

## Install

```bash
python3 -m venv .venv
source .venv/bin/activate
python -m pip install -r tools/openapi-to-rhcl/requirements.txt
```

## Usage

```bash
python tools/openapi-to-rhcl/generate.py \
  --spec api-specs/openapi-demo/openapi.yaml \
  --out gitops/apps/rhcl-openapi-demo/base/resources/generated
```

The generator is **idempotent**: re-running it overwrites the files in `--out`.

## `x-rhcl` contract (vendor extensions)

The OpenAPI file must include a top-level `x-rhcl` section.

### Minimal shape

```yaml
x-rhcl:
  namespace: openapi-demo
  routeName: openapi-demo-api
  labels:
    app: openapi-demo-api

  gatewayRef:
    name: rhcl-gw
    namespace: openshift-ingress

  backend:
    serviceName: openapi-demo-api
    port: 80

  auth:
    allowUnauthenticatedPaths:
      - /health
    apiKey:
      authorizationHeaderPrefix: APIKEY
      selectorLabels:
        app: openapi-demo-api

  rateLimit:
    counterExpression: auth.identity.userid
    limits:
      general-user:
        rates:
          - limit: 5
            window: 10s
        when:
          - "auth.identity.userid != 'bob'"
      bob-limit:
        rates:
          - limit: 2
            window: 10s
        when:
          - "auth.identity.userid == 'bob'"
```

### How it maps

- **`HTTPRoute`**:
  - One `match` is generated for each `(path, method)` operation in `paths`.
  - If an OpenAPI path contains params like `/pets/{id}`, the route uses a prefix up to the first `{` (e.g. `/pets/`).
  - The route is attached to `x-rhcl.gatewayRef` and forwards to `x-rhcl.backend`.

- **`AuthPolicy`**:
  - Targets the generated `HTTPRoute`.
  - Enforces API key auth, except for `x-rhcl.auth.allowUnauthenticatedPaths` (typically `/health`).
  - Extracts `userid` from the Secret annotation `secret.kuadrant.io/user-id` (same mechanism as the existing demo).

- **`RateLimitPolicy`**:
  - Targets the generated `HTTPRoute`.
  - Applies `x-rhcl.rateLimit.limits` using the counter expression.
  - Automatically excludes unauthenticated paths (e.g. `/health`) by adding `request.path != '/health'` predicates to each limit.

