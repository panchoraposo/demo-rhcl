# RHCL demo policies

This document summarizes the policies used in the demo, what problems they solve, and how they relate to each other.

## High-level topology

- **Application namespace**: `demo` contains the API, the UI, the `HTTPRoute`s, and route-level policies.
- **Gateway namespace**: the `Gateway` and gateway-level policies live in `openshift-ingress` (the Gateway API provider creates the corresponding Service/Deployment there).
- **Public entrypoint**: an OpenShift **passthrough** `Route` (`openshift-ingress/Route rhcl-gw`) forwards TLS directly to the Gateway listener `https`.

## Policies included

### `Certificate` — TLS for dedicated public hostnames

- **File**: `gitops/apps/rhcl-demo/base/resources/gateway/gateway-public-certificate.yaml`
- **Resource**: `openshift-ingress/Certificate rhcl-gw-public-tls`
- **Used by**: `Gateway/rhcl-gw` public listeners (`https-*-public`) via `tls.certificateRefs`
- **Issuer**: `spec.issuerRef.name` points to a `ClusterIssuer` (set per environment by the installer)

**What it does**

- Declares that the Gateway HTTPS listener must be backed by a certificate issued by `cert-manager`.

**Why it is useful**

- Ensures TLS is terminated at the **Gateway**, not at the OpenShift router.
- Enables automated certificate rotation/renewal via `cert-manager`.
- Makes HTTPS configuration **declarative and repeatable** across ephemeral environments.

Related objects:

- `openshift-ingress/Gateway rhcl-gw` (`gitops/apps/rhcl-demo/base/resources/gateway/gateway.yaml`) references the Secret created by this Certificate.
- `openshift-ingress/DNSPolicy ...` (`gitops/apps/rhcl-demo/base/resources/gateway/gateway-dnspolicy.yaml`) can publish Route53 records for those dedicated hosts.

---

### `AuthPolicy` (Gateway) — default deny-all (zero-trust)

- **File**: `gitops/apps/rhcl-demo/base/resources/policies/gateway-authpolicy.yaml`
- **Resource**: `openshift-ingress/AuthPolicy rhcl-gw-auth`
- **Target**: `Gateway/rhcl-gw`

**What it does**

- Applies a default **deny-all** posture for Gateway traffic (with an exception for `GET /health`).

**Why it is useful**

- Prevents accidental exposure: application routes must explicitly override the default policy.

---

### `AuthPolicy` (HTTPRoute) — API keys for the API

- **File**: `gitops/apps/rhcl-demo/base/resources/policies/route-authpolicy.yaml`
- **Resource**: `demo/AuthPolicy demo-api-auth`
- **Target**: `demo/HTTPRoute demo-api`

**What it does**

- Enables API key authentication using the `Authorization` header with prefix `APIKEY`.
- Extracts identity (`userid`) from a Secret annotation to enable identity-aware policies.

---

### `RateLimitPolicy` (HTTPRoute) — per-user rate limiting (Alice vs Bob)

- **File**: `gitops/apps/rhcl-demo/base/resources/policies/route-ratelimitpolicy.yaml`
- **Resource**: `demo/RateLimitPolicy demo-api-rlp`
- **Target**: `demo/HTTPRoute demo-api`

**What it does**

- Applies different limits based on `auth.identity.userid`:
  - general users (Alice): 5 req / 10s
  - Bob: 2 req / 10s

---

### `AuthPolicy` (HTTPRoute) — public UI (allow-all)

- **File**: `gitops/apps/rhcl-demo/base/resources/policies/ui-authpolicy.yaml`
- **Resource**: `demo/AuthPolicy rhcl-ui-allow`
- **Target**: `demo/HTTPRoute rhcl-ui`

**What it does**

- Explicitly allows access to the UI so you can demo policy behavior from the browser without credentials.

