# Red Hat Connectivity Link (RHCL) Workshop Demo — GitOps-ready

This repository is a **live-demo-friendly** workshop showing how **Red Hat Connectivity Link (Kuadrant)** applies policies to **Gateway API** traffic on **OpenShift 4.20**.

The goal is simple: one UI, a few modules, and each module highlights **one policy benefit** you can explain in under a minute.

## What you get (modules, policies, benefits)

### Policies playground (API keys + rate limiting)
- **Policies**: `AuthPolicy` (API keys), `RateLimitPolicy` (identity-aware), `AuthPolicy` on the Gateway (default deny-all)
- **Benefit**: you can demonstrate **zero-trust by default** and then show how teams safely expose only what they need, with **per-identity limits** (e.g. Bob hits `429` sooner than Alice).

### Traffic shaping (A/B + canary)
- **Policies/objects**: `HTTPRoute` weighted backends
- **Benefit**: progressive delivery without changing application code.
  - **A/B**: random split **80/20** (`/ab`)
  - **Canary**: rollout split **90/10** (`/canary`)
  - **Canary opt-in**: header `x-rhcl-canary: always` → **force 100% v2** (great for “internal testers” story)

### External API (ESPN proxy)
- **Objects**: `HTTPRoute` to an in-cluster proxy
- **Benefit**: the Gateway becomes the **single policy enforcement point** even when your app consumes third-party APIs.

### Observability (Grafana + OpenShift Console graphs)
- **Objects**: OpenShift **User Workload Monitoring**, Kuadrant `ServiceMonitor`/`PodMonitor`, **Grafana** (anonymous), preloaded dashboards
- **Benefit**: show **usage, errors, and latency** for the *same endpoints* you demo in the UI:
  - **Developer**: requests by HTTP code (200/401/403/404/429/5xx) + latency percentiles (P90/P95/P99)
  - **Platform**: top services, requests-by-code, latency (simple “SRE view”)
  - **Business**: traffic summary + total requests over a selected time range
- The main UI also includes **one-click OpenShift Query Browser links** (PromQL prefilled).

### Service Mesh (Kiali)
- **Objects**: Kiali (installed via `kiali-ossm` Operator, anonymous auth)
- **Benefit**: show an easy **topology/flow** story (Gateway → services), complementing policy enforcement with a visual request graph.

### AI chatbot (ESPN tool + token budget)
- **Policies**: `TokenRateLimitPolicy`
- **Benefit**: rate limiting based on **token usage** (not just requests), which maps better to LLM cost and abuse prevention.
  - Demo budget is intentionally small (currently **400 tokens / 15s**) so you can trigger `429` reliably.

### AuthPolicy (Keycloak JWT) — token, decode, call with/without
- **Policies**: `AuthPolicy` (JWT validation)
- **Benefit**: the Gateway validates **JWTs issued by Keycloak** and enforces API access without changing backend code.
  - The UI can fetch a token, show/decode it, and call a protected endpoint **with and without** the token.

### OIDC portal (separate hostname, browser login)
- **Policies**: `OIDCPolicy` (browser login), `TLSPolicy` (TLS), `DNSPolicy` (optional Route53 record)
- **Benefit**: a clean “browser login through the Gateway” story:
  - unauthenticated → **302 to Keycloak**
  - authenticated → cookie set → portal can fetch protected content

## Architecture (high level)

- **OpenShift Route (passthrough)** → **Gateway** (`Gateway API`)
- **Connectivity Link / Kuadrant** applies policies to:
  - the **Gateway** (guardrails like deny-by-default)
  - individual **HTTPRoutes** (auth, rate limits, traffic shaping)
- TLS is handled via `TLSPolicy` + `cert-manager` (ClusterIssuer required).

## Prerequisites

- You are logged into the cluster with **cluster-admin**
- `oc` CLI available
- OpenShift GitOps installed (namespace `openshift-gitops`)
- A working `ClusterIssuer` in your cluster (the installer can auto-pick one; or set it explicitly)

## Install (recommended)

### Installer (multi-environment)

Run:

```bash
./tools/install-rhcl-workshop.sh
```

Defaults:
- **main demo host**: `rhcl-workshop.<appsDomain>`
- **OIDC portal host**: `oidc-rhcl-workshop.<appsDomain>`
- **Grafana host**: `grafana-<DEMO_HOSTNAME>` (anonymous)

Useful overrides:
- **`DEMO_HOSTNAME`**: set the main hostname
- **`OIDC_HOSTNAME`**: set the OIDC portal hostname
- **`GRAFANA_HOSTNAME`**: set the Grafana hostname (optional)
- **`CLUSTER_ISSUER`**: set the cert-manager `ClusterIssuer` name
- **`REPO_URL` / `REVISION` / `APP_PATH`**: point Argo CD to a fork/branch/path

### Optional: Route53 DNS automation (DNSPolicy)

If you want `DNSPolicy` to manage Route53 records, export:

```bash
export AWS_ACCESS_KEY_ID=...
export AWS_SECRET_ACCESS_KEY=...
export AWS_REGION=...
```

Then rerun the installer; it will create/update `Secret/route53-credentials` (type `kuadrant.io/aws`) in `openshift-ingress`.

## Post-install: enable the Connectivity Link console plugin

The Connectivity Link operator installs the OpenShift console dynamic plugin, but it can be disabled by default.

This workshop includes a GitOps job that **enables** `kuadrant-console-plugin` automatically during sync.

### If you need to enable it manually
- OpenShift console → **Administrator** → **Home → Overview**
- **Dynamic Plugins → View all**
- Enable **`kuadrant-console-plugin`**
- Refresh the console → you should see **Connectivity Link** in the left nav

For details, see [Enabling the Connectivity Link dynamic plug-in](https://docs.redhat.com/en/documentation/red_hat_connectivity_link/1.0/html/installing_connectivity_link_on_openshift/enable-openshift-dynamic-plugin_connectivity-link).

## Configure the AI bot (optional)

The AI bot uses an OpenAI-compatible endpoint (LiteLLM) and a model.

### Set the API key (GitOps-friendly)

Create a secret in `demo`:

```bash
oc -n demo create secret generic rhcl-ai-bot-llm \
  --from-literal=apiKey='YOUR_KEY' \
  --dry-run=client -o yaml | oc apply -f -
```

### Direct env var (quick for testing)

```bash
oc -n demo set env deployment/rhcl-ai-bot RHCL_AI_OPENAI_API_KEY='YOUR_KEY'
```

## URLs

- Main UI: `https://<DEMO_HOSTNAME>/`
- OIDC portal: `https://<OIDC_HOSTNAME>/`
- Grafana (anonymous): `https://<GRAFANA_HOSTNAME>/`
- Kiali: `https://kiali-istio-system.<appsDomain>/`

## Suggested live-demo flow (5–8 minutes)

### 1) Zero-trust baseline + API keys
- Call `GET /hello` with no key → expect **401/403**
- Add API key (`IAMALICE`, `IAMBOB`) → expect **200**
- Keep clicking Bob → hit **429** faster (identity-based `RateLimitPolicy`)

### 2) Traffic shaping
- Sample A/B → show observed **80/20**
- Sample canary → show observed **90/10**
- Force canary (header) → show **0/100** (v2 only)

### 3) Keycloak JWT
- Click **Get token** → show the JWT + decoded claims
- Call protected API without token → **401**
- Call with token → **200**

### 4) OIDC portal
- Open portal (separate hostname) → unauthenticated **302** to Keycloak
- Login → portal shows protected content

### 5) AI bot + token budget
- Ask a question → show `usage.total_tokens`
- Run “Hit 429” → show `429` enforced by `TokenRateLimitPolicy`

### 6) Observability (fast, audience-friendly)
- In the main UI → **Observability**:
  - Click **Generate sample traffic** (creates a short burst so graphs move immediately)
  - Open **Developer / Platform / Business** dashboards and refresh
- Optional: open the **OpenShift Query Browser** links (PromQL prefilled) for a “native console” view.

### 7) Service Mesh (Kiali)
- In the main UI → **Service Mesh** → open Kiali and focus on namespace `demo` to show Gateway → services request flow.

## GitOps layout

- Base: `gitops/apps/rhcl-demo/base`
- Overlays:
  - `gitops/apps/rhcl-demo/overlays/ephemeral` (demo app only; assumes RHCL + Service Mesh ambient are already installed)
  - `gitops/apps/rhcl-demo/overlays/full-install` (installs RHCL via OLM and deploys the demo)
- Example Argo CD Application: `gitops/argocd/application-rhcl-demo.yaml`
- **ApplicationSet (multi-app split)**: `gitops/argocd/applicationset-rhcl-workshop.yaml`
  - Creates 4 apps: `infra`, `demo`, `keycloak`, `oidc-portal`
  - Useful to show **clear ownership boundaries** (platform vs app vs identity vs portal)

## Notes (why the dashboards work)

- **Metrics source**: dashboards use Prometheus (OpenShift monitoring + user workload monitoring) and Istio request metrics like `istio_requests_total`.
- **No tracing required**: Tempo/OpenTelemetry are optional (only needed if you want distributed traces). Metrics and dashboards work without them.

## References

- Red Hat Connectivity Link install: [Installing Connectivity Link on OpenShift](https://docs.redhat.com/en/documentation/red_hat_connectivity_link/1.3/html-single/installing_on_openshift_container_platform/index)
- Policies: [Configuring and deploying Gateway policies](https://docs.redhat.com/en/documentation/red_hat_connectivity_link/1.3/html-single/configuring_and_deploying_gateway_policies/configuring_and_deploying_gateway_policies)
- Observability: [Observability and Troubleshooting](https://docs.redhat.com/en/documentation/red_hat_connectivity_link/1.3/html/observability_and_troubleshooting/index)

