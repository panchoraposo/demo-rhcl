This overlay includes the RHCL installation (OLM Subscription + Kuadrant CR) and the demo app.

Use it only in clusters where you want GitOps to install RHCL components.

Prerequisite: `GatewayClass/openshift-default` must already exist (cluster-scoped Gateway API provider configuration).

This repo’s demo Gateways use `gatewayClassName: istio`, so the cluster must provide **`GatewayClass/istio`**
(for example via OpenShift Service Mesh 3.x / Istio controller with Gateway API enabled).

