#!/usr/bin/env bash
set -euo pipefail

CTX="${1:-rhcl2}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OPS="${ROOT}/gitops/apps/rhcl-demo/overlays/tracing-operators"
STACK="${ROOT}/gitops/apps/rhcl-demo/overlays/tracing-stack"

kustomize build --load-restrictor LoadRestrictionsNone "${OPS}" | kubectl --context="${CTX}" apply -f -

echo "Waiting for tracing CRDs to be established..."
for crd in \
  tempostacks.tempo.grafana.com \
  uiplugins.observability.openshift.io \
  opentelemetrycollectors.opentelemetry.io
do
  for i in $(seq 1 120); do
    if kubectl --context="${CTX}" get crd "${crd}" >/dev/null 2>&1; then
      break
    fi
    sleep 2
  done
  kubectl --context="${CTX}" get crd "${crd}" >/dev/null
done

kustomize build --load-restrictor LoadRestrictionsNone "${STACK}" | kubectl --context="${CTX}" apply -f -

echo "Done. Check:"
echo "- TempoStack: kubectl --context=${CTX} -n rhcl-observability get tempostack"
echo "- OTel Collector: kubectl --context=${CTX} -n openshift-opentelemetry get opentelemetrycollector"
echo "- Jaeger UI route: kubectl --context=${CTX} -n rhcl-observability get route tempo-jaeger -o jsonpath='{.spec.host}{\"\\n\"}'"

