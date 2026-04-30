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

# Use the cluster's default ingress wildcard cert for Gateway TLS.
DEFAULT_INGRESS_CERT_SECRET="$(
  oc -n openshift-ingress-operator get ingresscontroller default -o jsonpath='{.spec.defaultCertificate.name}' 2>/dev/null || true
)"
if [[ -z "${DEFAULT_INGRESS_CERT_SECRET}" ]]; then
  echo "Could not detect default ingress certificate secret (ingresscontroller default). " >&2
  echo "Set DEFAULT_INGRESS_CERT_SECRET manually." >&2
  exit 1
fi
DEFAULT_INGRESS_CERT_SECRET="${DEFAULT_INGRESS_CERT_SECRET:-cert-manager-ingress-cert}"

DEMO_HOSTNAME="${DEMO_HOSTNAME:-rhcl-workshop.${APPS_DOMAIN}}"
# Default OIDC hostname lives under the cluster apps domain.
# Most OpenShift clusters already expose a wildcard record for `*.${APPS_DOMAIN}`,
# so this hostname resolves without needing Route53 automation.
# Example:
#   rhcl-workshop.apps.cluster-xxxx.yyyy.sandbox1529.opentlc.com
# -> oidc-rhcl-workshop.apps.cluster-xxxx.yyyy.sandbox1529.opentlc.com
if [[ -z "${OIDC_HOSTNAME:-}" ]]; then
  DEMO_NAME="${DEMO_HOSTNAME%%.*}"
  OIDC_HOSTNAME="oidc-${DEMO_NAME}.${APPS_DOMAIN}"
fi
GRAFANA_HOSTNAME="${GRAFANA_HOSTNAME:-grafana-${DEMO_HOSTNAME}}"

if [[ -z "${CLUSTER_ISSUER:-}" ]]; then
  CLUSTER_ISSUER="$(
    oc get clusterissuer -o json 2>/dev/null | python3 -c 'import json,sys; j=json.load(sys.stdin); names=[x["metadata"]["name"] for x in j.get("items",[])]; score=lambda n: (10 if "letsencrypt" in n.lower() else 0)+(10 if ("prod" in n.lower() or "production" in n.lower()) else 0)+(-20 if "staging" in n.lower() else 0); names=sorted(names, key=lambda n:(-score(n), n)); print(names[0] if names else "")'
  )"
fi

if [[ -z "${CLUSTER_ISSUER}" ]]; then
  echo "No ClusterIssuer found. Set CLUSTER_ISSUER to an existing ClusterIssuer name." >&2
  exit 1
fi

echo "Installing RHCL Workshop with:"
echo "- DEMO_HOSTNAME=${DEMO_HOSTNAME}"
echo "- OIDC_HOSTNAME=${OIDC_HOSTNAME}"
echo "- GRAFANA_HOSTNAME=${GRAFANA_HOSTNAME}"
echo "- CLUSTER_ISSUER=${CLUSTER_ISSUER}"
echo "- DEFAULT_INGRESS_CERT_SECRET=${DEFAULT_INGRESS_CERT_SECRET}"

# Optional: configure Route53 credentials for Kuadrant DNSPolicy.
if [[ -n "${AWS_ACCESS_KEY_ID:-}" || -n "${AWS_SECRET_ACCESS_KEY:-}" || -n "${AWS_REGION:-}" ]]; then
  if [[ -z "${AWS_ACCESS_KEY_ID:-}" || -z "${AWS_SECRET_ACCESS_KEY:-}" || -z "${AWS_REGION:-}" ]]; then
    echo "To configure Route53 credentials, set AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY and AWS_REGION." >&2
    exit 1
  fi

  for ns in demo openshift-ingress; do
    oc -n "$ns" create secret generic route53-credentials \
    --type=kuadrant.io/aws \
    --from-literal=AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID}" \
    --from-literal=AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY}" \
    --from-literal=AWS_REGION="${AWS_REGION}" \
    --dry-run=client -o yaml | oc apply -f -
    echo "Created/updated Secret/route53-credentials in ${ns}."
  done
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
            group: gateway.networking.k8s.io
            version: v1
            kind: Gateway
            name: rhcl-gw
            namespace: openshift-ingress
          patch: |-
            apiVersion: gateway.networking.k8s.io/v1
            kind: Gateway
            metadata:
              name: rhcl-gw
              namespace: openshift-ingress
            spec:
              listeners:
                - name: http
                  port: 80
                  protocol: HTTP
                  allowedRoutes:
                    namespaces:
                      from: All
                - name: https-main
                  port: 443
                  protocol: HTTPS
                  hostname: ${DEMO_HOSTNAME}
                  tls:
                    mode: Terminate
                    certificateRefs:
                      - group: ""
                        kind: Secret
                        name: ${DEFAULT_INGRESS_CERT_SECRET}
                  allowedRoutes:
                    namespaces:
                      from: All
                - name: https-oidc
                  port: 443
                  protocol: HTTPS
                  hostname: ${OIDC_HOSTNAME}
                  tls:
                    mode: Terminate
                    certificateRefs:
                      - group: ""
                        kind: Secret
                        name: ${DEFAULT_INGRESS_CERT_SECRET}
                  allowedRoutes:
                    namespaces:
                      from: All

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
            group: grafana.integreatly.org
            version: v1beta1
            kind: Grafana
            name: rhcl-grafana
            namespace: monitoring
          patch: |-
            apiVersion: grafana.integreatly.org/v1beta1
            kind: Grafana
            metadata:
              name: rhcl-grafana
              namespace: monitoring
            spec:
              route:
                spec:
                  host: ${GRAFANA_HOSTNAME}

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
            name: ai-bot
            namespace: demo
          patch: |-
            apiVersion: gateway.networking.k8s.io/v1
            kind: HTTPRoute
            metadata:
              name: ai-bot
              namespace: demo
            spec:
              hostnames:
                - ${DEMO_HOSTNAME}

        - target:
            group: gateway.networking.k8s.io
            version: v1
            kind: HTTPRoute
            name: jwt-demo
            namespace: demo
          patch: |-
            apiVersion: gateway.networking.k8s.io/v1
            kind: HTTPRoute
            metadata:
              name: jwt-demo
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
            namespace: rhcl-oidc-portal
          patch: |-
            apiVersion: gateway.networking.k8s.io/v1
            kind: HTTPRoute
            metadata:
              name: secure-demo
              namespace: rhcl-oidc-portal
            spec:
              hostnames:
                - ${OIDC_HOSTNAME}

        - target:
            group: gateway.networking.k8s.io
            version: v1
            kind: HTTPRoute
            name: oidc-portal
            namespace: rhcl-oidc-portal
          patch: |-
            apiVersion: gateway.networking.k8s.io/v1
            kind: HTTPRoute
            metadata:
              name: oidc-portal
              namespace: rhcl-oidc-portal
            spec:
              hostnames:
                - ${OIDC_HOSTNAME}

        - target:
            group: extensions.kuadrant.io
            version: v1alpha1
            kind: OIDCPolicy
            name: secure-demo-oidc
            namespace: rhcl-oidc-portal
          patch: |-
            apiVersion: extensions.kuadrant.io/v1alpha1
            kind: OIDCPolicy
            metadata:
              name: secure-demo-oidc
              namespace: rhcl-oidc-portal
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
            namespace: rhcl-oidc-portal
          patch: |-
            apiVersion: kuadrant.io/v1
            kind: AuthPolicy
            metadata:
              name: secure-demo-jwt
              namespace: rhcl-oidc-portal
            spec:
              defaults:
                rules:
                  authentication:
                    jwt:
                      jwt:
                        issuerUrl: https://${OIDC_HOSTNAME}/auth/realms/rhcl

        - target:
            group: kuadrant.io
            version: v1
            kind: AuthPolicy
            name: jwt-demo-jwt
            namespace: demo
          patch: |-
            apiVersion: kuadrant.io/v1
            kind: AuthPolicy
            metadata:
              name: jwt-demo-jwt
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

