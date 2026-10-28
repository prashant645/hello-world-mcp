#!/usr/bin/env bash
set -euo pipefail

# Reusable CI helper for Kyma workflows:
# - decode base64 kubeconfig
# - set KUBECONFIG for subsequent steps
# - ensure kubectl is available

KUBECONFIG_B64="${KYMA_KUBECONFIG_B64:-}"
KUBECONFIG_PATH="${KUBECONFIG_PATH:-/tmp/kubeconfig}"

if [[ -z "$KUBECONFIG_B64" ]]; then
  echo "Missing base64 kubeconfig input. Set KYMA_KUBECONFIG_B64." >&2
  exit 1
fi

echo "$KUBECONFIG_B64" | base64 -d > "$KUBECONFIG_PATH"
chmod 600 "$KUBECONFIG_PATH"

if [[ -n "${GITHUB_ENV:-}" ]]; then
  echo "KUBECONFIG=$KUBECONFIG_PATH" >> "$GITHUB_ENV"
else
  export KUBECONFIG="$KUBECONFIG_PATH"
fi

mkdir -p "$HOME/bin"
if [[ -n "${GITHUB_PATH:-}" ]]; then
  echo "$HOME/bin" >> "$GITHUB_PATH"
else
  export PATH="$HOME/bin:$PATH"
fi

if ! command -v kubectl >/dev/null 2>&1; then
  curl -LO "https://dl.k8s.io/release/v1.28.0/bin/linux/amd64/kubectl"
  chmod +x kubectl
  mv kubectl "$HOME/bin/"
fi

kubectl version --client
