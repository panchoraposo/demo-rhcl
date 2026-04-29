# RHCL (Red Hat Connectivity Link) Demo — GitOps-ready

This repository contains a small application that showcases RHCL (Kuadrant) policies on OpenShift 4.20 using **Gateway API**.

The demo includes:

- **AuthPolicy (Gateway)**: default deny-all (zero-trust)
- **AuthPolicy (HTTPRoute)**: API key authentication for the API
- **RateLimitPolicy (HTTPRoute)**: identity-aware rate limiting (Alice vs Bob)
- **TLSPolicy (Gateway)**: TLS termination on the Gateway (integrated with `cert-manager`)
- **OIDCPolicy (HTTPRoute)**: browser login flow against Keycloak (separate portal)
- **DNSPolicy (Gateway)**: Route53 record management (OIDC portal hostname)

## GitOps layout

Use the Kustomize overlays under:

- `gitops/apps/rhcl-demo/overlays/ephemeral` (demo app only; assumes RHCL is already installed)
- `gitops/apps/rhcl-demo/overlays/full-install` (installs RHCL via OLM and deploys the demo)

There is also an Argo CD `Application` manifest:

- `gitops/argocd/application-rhcl-demo.yaml`

## How to deploy (Argo CD)

### Recommended: installer (multi-environment)

Run the installer while logged in as a cluster-admin:

```bash
./tools/install-rhcl-workshop.sh
```

Defaults:

- **main demo host**: `rhcl-workshop.<appsDomain>`
- **OIDC portal host**: `oidc-rhcl-workshop.<appsDomain>`

Optional (for Route53 DNS via `DNSPolicy`):

- Provide `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, and `AWS_REGION` as env vars so the installer can create `Secret/route53-credentials` in `openshift-ingress` with type `kuadrant.io/aws`.

### Manual: ephemeral overlay

1) Update `gitops/apps/rhcl-demo/overlays/ephemeral/kustomization.yaml` (or use Argo CD kustomize patches):

- `DEMO_HOSTNAME`: the hostname you want to use for the passthrough Route + Gateway certificate
- `OIDC_HOSTNAME`: the hostname for the OIDC portal (and the protected `/secure` route)
- `CLUSTER_ISSUER`: an existing `ClusterIssuer` name in your cluster (for example, `letsencrypt-production`)

2) Apply the Argo CD application:

```bash
oc apply -f gitops/argocd/application-rhcl-demo.yaml
```

3) Open the demo UI:

- `https://<DEMO_HOSTNAME>/`
- `https://<OIDC_HOSTNAME>/` (OIDC portal)

## What you can show live

- **No API key** on `GET /hello` → `401`
- **Valid API key** (`IAMALICE` / `IAMBOB`) → `200`
- **Rate limit** → `429` (Bob hits it sooner than Alice)

## References

- Installation: `https://docs.redhat.com/en/documentation/red_hat_connectivity_link/1.3/html-single/installing_on_openshift_container_platform/index`
- Policies: `https://docs.redhat.com/en/documentation/red_hat_connectivity_link/1.3/html-single/configuring_and_deploying_gateway_policies/configuring_and_deploying_gateway_policies`

