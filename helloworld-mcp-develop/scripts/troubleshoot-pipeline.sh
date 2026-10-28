#!/bin/bash
# Kyma Dev Pipeline Troubleshooting Guide
# Auto-run when issues occur

set -e

echo "════════════════════════════════════════════════════════════════"
echo "Kyma Dev Pipeline - Monitoring & Auto-Fix Guide"
echo "════════════════════════════════════════════════════════════════"
echo ""

# Stage 1: Check for common issues before pipeline runs
echo "📋 PRE-FLIGHT CHECKS"
echo "────────────────────────────────────────────────────────────────"

# Check 1: GitHub Secret exists
echo "✓ Check 1: GitHub Secret DOCKER_CONFIG_JSON"
echo "  Status: Check manually in GitHub UI"
echo "  Path: Settings → Secrets and variables → Actions"
echo ""

# Check 2: Helm chart exists
echo "✓ Check 2: Helm Chart"
if [ -f "helm/hello-world-mcp/Chart.yaml" ]; then
    echo "  ✅ Chart found at helm/hello-world-mcp/Chart.yaml"
else
    echo "  ❌ ERROR: Chart not found!"
    exit 1
fi
echo ""

# Check 3: Dockerfile exists
echo "✓ Check 3: Dockerfile"
if [ -f "Dockerfile" ]; then
    echo "  ✅ Dockerfile found"
else
    echo "  ❌ ERROR: Dockerfile not found!"
    exit 1
fi
echo ""

# Check 4: VERSION file exists
echo "✓ Check 4: VERSION file"
if [ -f "VERSION" ]; then
    VERSION=$(cat VERSION)
    echo "  ✅ VERSION = $VERSION"
else
    echo "  ❌ ERROR: VERSION file not found!"
    exit 1
fi
echo ""

echo "════════════════════════════════════════════════════════════════"
echo "EXPECTED PIPELINE STAGES"
echo "════════════════════════════════════════════════════════════════"
echo ""

echo "1. PREFLIGHT_KYMA_DEV (2-3 min)"
echo "   - Setup kubeconfig"
echo "   - Display kubectl context"
echo "   - Verify APIRule CRD exists"
echo "   - Verify Istio gateway CRD exists"
echo ""

echo "2. BUILD_KYMA_DEV (10-15 min)"
echo "   - Setup Java"
echo "   - Gradle build (clean build -x test)"
echo "   - Extract version tag"
echo "   - Kubeconfig setup"
echo "   - Docker login (using DOCKER_CONFIG_JSON secret)"
echo "   - Docker build"
echo "   - Docker push to gtlc-docker.common.repositories.cloud.sap"
echo "   - Create namespace"
echo "   - Create docker-registry-secret (from DOCKER_CONFIG_JSON)"
echo "   - Helm deploy"
echo ""

echo "3. VERIFY_KYMA_DEV (2-3 min)"
echo "   - Wait for APIRule ready (max 30 retries, 10s interval)"
echo "   - Health check: GET /actuator/health → expect 200"
echo "   - Auth check: GET /sse (no token) → expect 401"
echo ""

echo "════════════════════════════════════════════════════════════════"
echo "COMMON ISSUES & FIXES"
echo "════════════════════════════════════════════════════════════════"
echo ""

echo "❌ ISSUE 1: Docker login fails (401 Unauthorized)"
echo "────────────────────────────────────────────────────────────────"
echo "Error: 'failed to fetch anonymous token: 401 Unauthorized'"
echo ""
echo "CAUSE: DOCKER_CONFIG_JSON secret missing or malformed"
echo ""
echo "FIX:"
echo "  1. Go to: Settings → Secrets and variables → Actions"
echo "  2. Check if DOCKER_CONFIG_JSON exists"
echo "  3. Value MUST be escaped JSON string (not nested object):"
echo ""
echo '     {"auths":{"gtlc-docker.common.repositories.cloud.sap":{"auth":"BASE64_CREDS"}}}'
echo ""
echo "  4. NOT like this:"
echo '     { "auths": { "gtlc-docker...": { "auth": "..." } } }'
echo ""
echo "  5. Recreate the secret with correct format"
echo ""

echo "❌ ISSUE 2: Gradle build fails"
echo "────────────────────────────────────────────────────────────────"
echo "Error: 'BUILD FAILED' or 'Compilation error'"
echo ""
echo "CAUSE: Code error or dependency issue"
echo ""
echo "FIX:"
echo "  1. Local test: ./gradlew clean build"
echo "  2. Fix errors in code"
echo "  3. Commit and push to develop"
echo "  4. Pipeline will auto-retry"
echo ""

echo "❌ ISSUE 3: Helm deployment fails"
echo "────────────────────────────────────────────────────────────────"
echo "Error: 'Helm install failed' or 'release hello-world-mcp failed'"
echo ""
echo "CAUSE: Namespace missing, permission issue, or bad values"
echo ""
echo "FIX (manual):"
echo "  1. Setup kubeconfig:"
echo '     export KYMA_KUBECONFIG_B64=<secret>'
echo "     bash scripts/setup-kubeconfig-kubectl.sh"
echo ""
echo "  2. Check namespace:"
echo "     kubectl create namespace dev --dry-run=client -o yaml | kubectl apply -f -"
echo ""
echo "  3. Check image pull secret:"
echo "     kubectl get secret docker-registry-secret -n dev"
echo ""
echo "  4. Manual Helm deploy:"
echo "     helm upgrade --install hello-world-mcp ./helm/hello-world-mcp \\"
echo "       -n dev -f helm/hello-world-mcp/values.yaml -f helm/hello-world-mcp/values-dev.yaml \\"
echo "       --set image.tag=YOUR_TAG --wait --timeout 5m"
echo ""

echo "❌ ISSUE 4: APIRule not ready (timeout)"
echo "────────────────────────────────────────────────────────────────"
echo "Error: 'APIRule not ready after 30 retries' or 'APIRule not found'"
echo ""
echo "CAUSE: Kyma gateway issue or APIRule not deployed by Helm"
echo ""
echo "FIX (manual):"
echo "  1. Check APIRule exists:"
echo "     kubectl get apirule hello-world-mcp -n dev"
echo ""
echo "  2. Check APIRule status:"
echo "     kubectl describe apirule hello-world-mcp -n dev"
echo ""
echo "  3. Check Kyma gateway:"
echo "     kubectl get gateway -n kyma-system"
echo ""
echo "  4. Check pod status:"
echo "     kubectl get pods -n dev"
echo "     kubectl describe pod <pod-name> -n dev"
echo "     kubectl logs <pod-name> -n dev --tail=50"
echo ""

echo "❌ ISSUE 5: Smoke tests fail (health check not 200)"
echo "────────────────────────────────────────────────────────────────"
echo "Error: 'Health check failed (expected 200, got 500)'"
echo ""
echo "CAUSE: Pod crashed or app not responding"
echo ""
echo "FIX:"
echo "  1. Check pod logs:"
echo "     kubectl logs -n dev -l app=hello-world-mcp --tail=100"
echo ""
echo "  2. Check pod status:"
echo "     kubectl describe pod <pod-name> -n dev"
echo ""
echo "  3. Port forward to test:"
echo "     kubectl port-forward -n dev svc/hello-world-mcp 8080:8080"
echo "     curl http://localhost:8080/actuator/health"
echo ""

echo "❌ ISSUE 6: Image pull error (ErrImagePull)"
echo "────────────────────────────────────────────────────────────────"
echo "Error: 'Failed to pull image' or 'ErrImagePull'"
echo ""
echo "CAUSE: docker-registry-secret not created or image not in registry"
echo ""
echo "FIX:"
echo "  1. Check if image was pushed:"
echo "     docker image ls | grep gtlc-docker"
echo ""
echo "  2. Check pull secret:"
echo "     kubectl get secret docker-registry-secret -n dev"
echo ""
echo "  3. Recreate pull secret:"
echo "     kubectl create secret docker-registry docker-registry-secret \\"
echo "       --docker-server=gtlc-docker.common.repositories.cloud.sap \\"
echo "       --docker-username=repostgtlc \\"
echo "       --docker-password=<PASSWORD> \\"
echo "       --docker-email=not-used@example.com \\"
echo "       -n dev --dry-run=client -o yaml | kubectl apply -f -"
echo ""

echo "════════════════════════════════════════════════════════════════"
echo "QUICK COMMANDS TO DEBUG"
echo "════════════════════════════════════════════════════════════════"
echo ""

echo "Setup kubeconfig:"
echo "  export KYMA_KUBECONFIG_B64=<from GitHub secret>"
echo "  bash scripts/setup-kubeconfig-kubectl.sh"
echo ""

echo "View pod status:"
echo "  kubectl get pods -n dev"
echo "  kubectl describe pod <pod-name> -n dev"
echo ""

echo "View logs:"
echo "  kubectl logs -n dev -l app=hello-world-mcp -f"
echo ""

echo "Check APIRule:"
echo "  kubectl get apirule hello-world-mcp -n dev"
echo "  kubectl describe apirule hello-world-mcp -n dev"
echo ""

echo "Test endpoints:"
echo "  curl -k https://<APIRULE_HOST>/actuator/health"
echo "  curl -k https://<APIRULE_HOST>/sse  # Should return 401"
echo ""

echo "════════════════════════════════════════════════════════════════"
echo "Ready to troubleshoot! Share any error output and I'll fix it."
echo "════════════════════════════════════════════════════════════════"

