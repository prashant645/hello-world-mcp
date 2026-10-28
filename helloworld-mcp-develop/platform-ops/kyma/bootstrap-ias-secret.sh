#!/usr/bin/env bash
# ==============================================================================
# One-time bootstrap: create the hello-world-mcp-ias-credentials K8s Secret.
#
# Run this ONCE before the first pipeline deployment, or whenever credentials
# need to be rotated.  The pipeline itself never touches this secret.
#
# Prerequisites:
#   - kubectl configured and pointing at the target cluster/namespace
#   - IAS_CLIENT_ID     : SAP IAS application ID for M2M auth (MCP → hello-world-service)
#                         Copy from: Applications & Resources > Applications > <app> > Application ID
#   - IAS_CLIENT_SECRET : M2M client secret (from Applications & Resources > Credentials)
#
# Note: IAS_MCP_UI_CLIENT_ID is NOT stored in this secret.
#       It is a non-sensitive public client ID managed in Helm values (values-<env>.yaml → ias.mcpUiClientId).
#       It supports a comma-separated list of client IDs (e.g. Joule + Claude Code).
#       See helm/hello-world-mcp/values-prod.yaml for the authoritative value.
#
# Usage:
#   1. Get M2M Application ID and secret from SAP IAS console:
#      Applications & Resources > Applications > <M2M app> > Application ID / Credentials
#
#   2. Run this script:
#      IAS_CLIENT_ID=<m2m-app-id> \
#      IAS_CLIENT_SECRET=<m2m-secret> \
#      NAMESPACE=dev \
#      bash platform-ops/kyma/bootstrap-ias-secret.sh
#
#   3. After deployment, register connectors in each MCP client:
#      Joule Work: Extensions > Connectors > Add Connector
#      Claude Code: claude mcp add --transport http --client-id <claude-client-id> ...
# ==============================================================================
set -euo pipefail

NAMESPACE="${NAMESPACE:-dev}"
SECRET_NAME="hello-world-mcp-ias-credentials"

: "${IAS_CLIENT_ID:?IAS_CLIENT_ID must be set}"
: "${IAS_CLIENT_SECRET:?IAS_CLIENT_SECRET must be set}"

echo "Creating/updating K8s Secret '${SECRET_NAME}' in namespace '${NAMESPACE}'..."

kubectl create secret generic "${SECRET_NAME}" \
  --from-literal=IAS_CLIENT_ID="${IAS_CLIENT_ID}" \
  --from-literal=IAS_CLIENT_SECRET="${IAS_CLIENT_SECRET}" \
  --namespace "${NAMESPACE}" \
  --dry-run=client -o yaml | kubectl apply -f -

echo "Done. Secret '${SECRET_NAME}' is ready in namespace '${NAMESPACE}'."
echo ""
echo "Keys stored:"
echo "  IAS_CLIENT_ID     – M2M client credentials (MCP → hello-world-service)"
echo "  IAS_CLIENT_SECRET – M2M client secret"
echo ""
echo "Note: MCP UI client IDs (Joule, Claude) are managed in Helm values, not this secret."
