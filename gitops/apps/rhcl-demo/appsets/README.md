# ApplicationSet layout (workshop)

This folder contains the `ApplicationSet` and the per-app Kustomize entrypoints used to split the workshop into multiple Argo CD Applications:

- **infra**: Gateway/Routes/TLS/DNS + cluster banner + console plugin enablement
- **demo**: main UI + demo workloads (API keys, rate limit, traffic, external, AI, JWT demo)
- **keycloak**: Keycloak + realm + theme + `/auth` HTTPRoute
- **oidc-portal**: independent portal UI + OIDC routes/policies

