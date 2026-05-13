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
- **Objects**: `Gateway` listener + `DNSPolicy` (Route53) + `Certificate` SANs + `HTTPRoute` + `AuthPolicy`
- **Benefit**: publish a third‑party API behind the Gateway on a **dedicated hostname** (`external-api.<domain>`), so you can apply the same policies (auth/rate-limit/traffic) even when the data originates outside the cluster.

### Observability (Grafana + OpenShift Console graphs)
- **Objects**: OpenShift **User Workload Monitoring**, Kuadrant `ServiceMonitor`/`PodMonitor`, **Grafana** (anonymous), preloaded dashboards
- **Benefit**: show **usage, errors, and latency** for the *same endpoints* you demo in the UI:
  - **Developer**: requests by HTTP code (200/401/403/404/429/5xx) + latency percentiles (P90/P95/P99)
  - **Platform**: top services, requests-by-code, latency (simple “SRE view”)
  - **Business**: traffic summary + total requests over a selected time range
- The main UI focuses on **Grafana dashboards** for quick, audience-friendly observability.

### AI chatbot (ESPN tool + token budget)
- **Policies**: `TokenRateLimitPolicy`
- **Benefit**: rate limiting based on **token usage** (not just requests), which maps better to LLM cost and abuse prevention.
  - Demo budget is intentionally small (currently **400 tokens / 15s**) so you can trigger `429` reliably.
  - Chat completions run on a **dedicated AI hostname** (`ai.<base-domain>`) so `TokenRateLimitPolicy` (and Traffic Analysis) can be scoped to the AI Gateway.
  - When the bot needs external data, it calls tools via the **RHCL MCP Gateway** (`mcp.<base-domain>`).
  - The UI calls MCP tool helpers via a same-origin proxy (`/ai/mcp/*`) so the browser never needs to do cross-origin requests to the MCP hostname.
  - If you need to install the MCP Gateway, see [Installing the MCP Gateway](https://docs.redhat.com/en/documentation/red_hat_connectivity_link/1.3/html/installing_the_mcp_gateway/mcp-gateway-install).

### AuthPolicy (Keycloak JWT) — token, decode, call with/without
- **Policies**: `AuthPolicy` (JWT validation)
- **Benefit**: the Gateway validates **JWTs issued by Keycloak** and enforces API access without changing backend code.
  - The UI can fetch a token, show/decode it, and call a protected endpoint **with and without** the token.

### OIDC portal (separate hostname, browser login)
- **Policies**: `OIDCPolicy` (browser login), `DNSPolicy` (optional Route53 record)
- **Benefit**: a clean “browser login through the Gateway” story:
  - unauthenticated → **302 to Keycloak**
  - authenticated → cookie set → portal can fetch protected content

## Architecture (high level)

- **OpenShift Route (passthrough)** → **Gateway** (`Gateway API`)
- **Connectivity Link / Kuadrant** applies policies to:
  - the **Gateway** (guardrails like deny-by-default)
  - individual **HTTPRoutes** (auth, rate limits, traffic shaping)
- TLS is handled via `cert-manager` and Gateway listener Secrets.
  - This repo supports both:
    - **Direct `Certificate` objects** (explicit secrets referenced by listeners)
    - **Kuadrant `TLSPolicy`** (recommended) to manage cert-manager Certificates for specific listeners/hostnames.

### Connectivity Link changes in this repo (demo wiring)

- **External API published hostname**:
  - `Gateway` listener: `https-external-public` → `external-api.<base-domain>`
  - `DNSPolicy`: creates/updates the Route53 record for `external-api.<base-domain>`
  - `Certificate`: `rhcl-gw-public-tls` includes `external-api.<base-domain>` as a SAN
  - `HTTPRoute`: `demo/external-proxy-public` publishes clean paths:
    - `GET /nba`, `/epl`, `/laliga`, `/nfl`, `/nhl`
  - `AuthPolicy`: `demo/external-proxy-public-allow` (route-level allow) so the default deny-all does not block the demo

- **MCP tools + UI/bot access (stable across environments)**:
  - `Gateway`: `openshift-ingress/rhcl-mcp-gw` publishes a dedicated MCP hostname `mcp.<base-domain>`
  - `HTTPRoute`: `mcp-system/rhcl-mcp-gateway` exposes `/mcp` on that hostname
  - `AuthPolicy`: `openshift-ingress/rhcl-mcp-gw-auth` (deny-all) + `mcp-system/rhcl-mcp-allow` (allow `/mcp`)
  - The bot calls tools through `https://mcp.<base-domain>/mcp`, and tools consume ESPN via `https://external-api.<base-domain>`

> Note: NGINX is just an implementation detail for serving static UI / proxying upstream JSON. The workshop value is the **Gateway API + Kuadrant policies** wiring, not the web server choice.

## Prerequisites

- You are logged into the cluster with **cluster-admin**
- `oc` CLI available
- `python3` available
- OpenShift GitOps **will be installed automatically** if missing (by the installer)
- A working `ClusterIssuer` in your cluster (the installer can auto-pick one; or set it explicitly)

## Install (recommended)

### Installer (multi-environment)

Run:

```bash
./install.sh
```

Compatibility: `./tools/install-rhcl-workshop.sh` still exists and just delegates to `./install.sh`.

Defaults:
- **main demo host**: `rhcl-workshop.<appsDomain>`
- **OIDC portal host**: `oidc-rhcl-workshop.<appsDomain>`
- **Grafana host**: `grafana.<appsDomain>` (anonymous)

Useful overrides:
- **`APPS_DOMAIN`**: set the cluster apps domain (if autodiscovery fails)
- **`DEMO_HOSTNAME`**: set the main hostname
- **`OIDC_HOSTNAME`**: set the OIDC portal hostname
- **`GRAFANA_HOSTNAME`**: set the Grafana hostname (optional)
- **`CLUSTER_ISSUER`**: set the cert-manager `ClusterIssuer` name
- **`DEFAULT_INGRESS_CERT_SECRET`**: set the wildcard cert secret used for `*.appsDomain` listeners
- **`EXTERNAL_BASE_DOMAIN`**: base domain for dedicated public hosts (defaults to last 2–3 labels of `APPS_DOMAIN`)
- **`EXTERNAL_API_HOSTNAME`**: override the external API hostname (defaults to `external-api.<EXTERNAL_BASE_DOMAIN>`)
- **`REPO_URL` / `REVISION` / `APP_PATH`**: point Argo CD to a fork/branch/path

### Optional: Route53 DNS automation (DNSPolicy)

If you want `DNSPolicy` to manage Route53 records:
- Ensure the **AWS CLI is installed** and your environment is already configured so `aws sts get-caller-identity` works.
- Export:

```bash
export AWS_ACCESS_KEY_ID=...
export AWS_SECRET_ACCESS_KEY=...
export AWS_REGION=...
```

Then rerun the installer; it will validate AWS auth and create/update `Secret/route53-credentials` (type `kuadrant.io/aws`) in `openshift-ingress` (and `demo`).

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

Create a secret in `rhcl-ai-bot`:

```bash
oc -n rhcl-ai-bot create secret generic rhcl-ai-bot-llm \
  --from-literal=apiKey='YOUR_KEY' \
  --dry-run=client -o yaml | oc apply -f -
```

### Via the installer (recommended)

Export an environment variable before running `./install.sh`:

```bash
export RHCL_AI_OPENAI_API_KEY='YOUR_KEY'
./install.sh
```

### Direct env var (quick for testing)

```bash
oc -n rhcl-ai-bot set env deployment/rhcl-ai-bot RHCL_AI_OPENAI_API_KEY='YOUR_KEY'
```

## URLs

- Main UI: `https://<DEMO_HOSTNAME>/`
- OIDC portal: `https://<OIDC_HOSTNAME>/`
- Grafana (anonymous): `https://<GRAFANA_HOSTNAME>/`
- External API hostname: `https://external-api.<appsDomain>/epl` (also `/nba`, `/laliga`, `/nfl`, `/nhl`)

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

## GitOps layout

- Base: `gitops/apps/rhcl-demo/base`
- Overlays:
  - `gitops/apps/rhcl-demo/overlays/ephemeral` (demo app only; assumes Connectivity Link is already installed)
  - `gitops/apps/rhcl-demo/overlays/full-install` (installs RHCL via OLM and deploys the demo)
- Example Argo CD Application: `gitops/argocd/application-rhcl-demo.yaml`
- **ApplicationSet (multi-app split)**: `gitops/argocd/applicationset-rhcl-workshop.yaml`
  - Creates 4 apps: `infra`, `demo`, `keycloak`, `oidc-portal`
  - Useful to show **clear ownership boundaries** (platform vs app vs identity vs portal)

## Notes (why the dashboards work)

- **Metrics source**: dashboards use Prometheus (OpenShift monitoring + user workload monitoring) and Connectivity Link / Kuadrant metrics.
- **Tracing (optional)**: distributed tracing is not required for the core workshop, but if enabled you can also inspect
  gateway-level traces for policy-enforced responses (e.g. 401/403/429) because those responses are generated at the Gateway.

## References

- Red Hat Connectivity Link install: [Installing Connectivity Link on OpenShift](https://docs.redhat.com/en/documentation/red_hat_connectivity_link/1.3/html-single/installing_on_openshift_container_platform/index)
- MCP Gateway: [Installing the MCP Gateway](https://docs.redhat.com/en/documentation/red_hat_connectivity_link/1.3/html/installing_the_mcp_gateway/mcp-gateway-install)
- Policies: [Configuring and deploying Gateway policies](https://docs.redhat.com/en/documentation/red_hat_connectivity_link/1.3/html-single/configuring_and_deploying_gateway_policies/configuring_and_deploying_gateway_policies)
- Observability: [Observability and Troubleshooting](https://docs.redhat.com/en/documentation/red_hat_connectivity_link/1.3/html/observability_and_troubleshooting/index)

