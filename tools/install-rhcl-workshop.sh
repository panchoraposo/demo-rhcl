#!/usr/bin/env bash
set -euo pipefail

need() { command -v "$1" >/dev/null 2>&1 || { echo "Missing required command: $1" >&2; exit 1; }; }
need oc
need python3

if ! oc whoami >/dev/null 2>&1; then
  echo "You are not logged in. Run 'oc login' first." >&2
  exit 1
fi

if ! oc auth can-i '*' '*' --all-namespaces >/dev/null 2>&1; then
  echo "This installer expects cluster-admin privileges (oc auth can-i * * --all-namespaces)." >&2
  exit 1
fi

APPS_DOMAIN="${APPS_DOMAIN:-$(oc get ingresses.config/cluster -o jsonpath='{.spec.domain}' 2>/dev/null || true)}"
if [[ -z "${APPS_DOMAIN}" ]]; then
  echo "Could not detect apps domain (ingresses.config/cluster). Set APPS_DOMAIN manually." >&2
  exit 1
fi

DEMO_HOSTNAME="${DEMO_HOSTNAME:-rhcl-workshop.${APPS_DOMAIN}}"
OIDC_HOSTNAME="${OIDC_HOSTNAME:-oidc-${DEMO_HOSTNAME}}"

if [[ -z "${CLUSTER_ISSUER:-}" ]]; then
  CLUSTER_ISSUER="$(
    oc get clusterissuer -o json 2>/dev/null | python3 - <<'PY'
import json,sys,re
j=json.load(sys.stdin)
names=[x["metadata"]["name"] for x in j.get("items",[])]
def score(n):
  s=0
  n2=n.lower()
  if "letsencrypt" in n2: s+=10
  if "prod" in n2 or "production" in n2: s+=10
  if "staging" in n2: s-=20
  return s
names=sorted(names, key=lambda n:(-score(n), n))
print(names[0] if names else "")
PY
  )"
fi

if [[ -z "${CLUSTER_ISSUER}" ]]; then
  echo "No ClusterIssuer found. Set CLUSTER_ISSUER to an existing ClusterIssuer name." >&2
  exit 1
fi

echo "Installing RHCL Workshop with:"
echo "- DEMO_HOSTNAME=${DEMO_HOSTNAME}"
echo "- OIDC_HOSTNAME=${OIDC_HOSTNAME}"
echo "- CLUSTER_ISSUER=${CLUSTER_ISSUER}"

# Optional: configure Route53 credentials for Kuadrant DNSPolicy.
if [[ -n "${AWS_ACCESS_KEY_ID:-}" || -n "${AWS_SECRET_ACCESS_KEY:-}" || -n "${AWS_REGION:-}" ]]; then
  if [[ -z "${AWS_ACCESS_KEY_ID:-}" || -z "${AWS_SECRET_ACCESS_KEY:-}" || -z "${AWS_REGION:-}" ]]; then
    echo "To configure Route53 credentials, set AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY and AWS_REGION." >&2
    exit 1
  fi

  oc -n openshift-ingress create secret generic route53-credentials \
    --type=kuadrant.io/aws \
    --from-literal=AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID}" \
    --from-literal=AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY}" \
    --from-literal=AWS_REGION="${AWS_REGION}" \
    --dry-run=client -o yaml | oc apply -f -
  echo "Created/updated Secret/route53-credentials in openshift-ingress."
else
  echo "Route53 secret not configured (AWS_* env vars not set). DNSPolicy will remain inactive until you create it."
fi

REPO_URL="${REPO_URL:-https://github.com/panchoraposo/demo-rhcl.git}"
REVISION="${REVISION:-main}"
APP_PATH="${APP_PATH:-gitops/apps/rhcl-demo/overlays/full-install}"

cat <<YAML | oc apply -f -
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: rhcl-workshop
  namespace: openshift-gitops
spec:
  project: default
  source:
    repoURL: ${REPO_URL}
    targetRevision: ${REVISION}
    path: ${APP_PATH}
    kustomize:
      patches:
        - target:
            group: route.openshift.io
            version: v1
            kind: Route
            name: rhcl-gw
            namespace: openshift-ingress
          patch: |-
            apiVersion: route.openshift.io/v1
            kind: Route
            metadata:
              name: rhcl-gw
              namespace: openshift-ingress
            spec:
              host: ${DEMO_HOSTNAME}

        - target:
            group: route.openshift.io
            version: v1
            kind: Route
            name: rhcl-gw-oidc
            namespace: openshift-ingress
          patch: |-
            apiVersion: route.openshift.io/v1
            kind: Route
            metadata:
              name: rhcl-gw-oidc
              namespace: openshift-ingress
            spec:
              host: ${OIDC_HOSTNAME}

        - target:
            group: cert-manager.io
            version: v1
            kind: Certificate
            name: rhcl-gw-tls
            namespace: openshift-ingress
          patch: |-
            apiVersion: cert-manager.io/v1
            kind: Certificate
            metadata:
              name: rhcl-gw-tls
              namespace: openshift-ingress
            spec:
              dnsNames:
                - ${DEMO_HOSTNAME}
                - ${OIDC_HOSTNAME}
              issuerRef:
                name: ${CLUSTER_ISSUER}

        - target:
            group: kuadrant.io
            version: v1
            kind: TLSPolicy
            name: rhcl-gw-tls
            namespace: openshift-ingress
          patch: |-
            apiVersion: kuadrant.io/v1
            kind: TLSPolicy
            metadata:
              name: rhcl-gw-tls
              namespace: openshift-ingress
            spec:
              issuerRef:
                name: ${CLUSTER_ISSUER}

        - target:
            group: gateway.networking.k8s.io
            version: v1
            kind: HTTPRoute
            name: demo-api
            namespace: demo
          patch: |-
            apiVersion: gateway.networking.k8s.io/v1
            kind: HTTPRoute
            metadata:
              name: demo-api
              namespace: demo
            spec:
              hostnames:
                - ${DEMO_HOSTNAME}

        - target:
            group: gateway.networking.k8s.io
            version: v1
            kind: HTTPRoute
            name: rhcl-ui
            namespace: demo
          patch: |-
            apiVersion: gateway.networking.k8s.io/v1
            kind: HTTPRoute
            metadata:
              name: rhcl-ui
              namespace: demo
            spec:
              hostnames:
                - ${DEMO_HOSTNAME}

        - target:
            group: gateway.networking.k8s.io
            version: v1
            kind: HTTPRoute
            name: ab-demo
            namespace: demo
          patch: |-
            apiVersion: gateway.networking.k8s.io/v1
            kind: HTTPRoute
            metadata:
              name: ab-demo
              namespace: demo
            spec:
              hostnames:
                - ${DEMO_HOSTNAME}

        - target:
            group: gateway.networking.k8s.io
            version: v1
            kind: HTTPRoute
            name: secure-demo
            namespace: demo
          patch: |-
            apiVersion: gateway.networking.k8s.io/v1
            kind: HTTPRoute
            metadata:
              name: secure-demo
              namespace: demo
            spec:
              hostnames:
                - ${OIDC_HOSTNAME}

        - target:
            group: gateway.networking.k8s.io
            version: v1
            kind: HTTPRoute
            name: oidc-portal
            namespace: demo
          patch: |-
            apiVersion: gateway.networking.k8s.io/v1
            kind: HTTPRoute
            metadata:
              name: oidc-portal
              namespace: demo
            spec:
              hostnames:
                - ${OIDC_HOSTNAME}

        - target:
            group: extensions.kuadrant.io
            version: v1alpha1
            kind: OIDCPolicy
            name: secure-demo-oidc
            namespace: demo
          patch: |-
            apiVersion: extensions.kuadrant.io/v1alpha1
            kind: OIDCPolicy
            metadata:
              name: secure-demo-oidc
              namespace: demo
            spec:
              provider:
                issuerURL: https://${OIDC_HOSTNAME}/auth/realms/rhcl
                authorizationEndpoint: https://${OIDC_HOSTNAME}/auth/realms/rhcl/protocol/openid-connect/auth
                tokenEndpoint: https://${OIDC_HOSTNAME}/auth/realms/rhcl/protocol/openid-connect/token
                redirectURI: https://${OIDC_HOSTNAME}/auth/callback

        - target:
            group: kuadrant.io
            version: v1
            kind: AuthPolicy
            name: secure-demo-jwt
            namespace: demo
          patch: |-
            apiVersion: kuadrant.io/v1
            kind: AuthPolicy
            metadata:
              name: secure-demo-jwt
              namespace: demo
            spec:
              defaults:
                rules:
                  authentication:
                    jwt:
                      jwt:
                        issuerUrl: https://${OIDC_HOSTNAME}/auth/realms/rhcl
  destination:
    server: https://kubernetes.default.svc
    namespace: demo
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
    syncOptions:
      - CreateNamespace=true
      - ServerSideApply=true
YAML

echo "Applied Argo CD Application/rhcl-workshop."
echo "Open:"
echo "- https://${DEMO_HOSTNAME}/"
echo "- https://${OIDC_HOSTNAME}/"

