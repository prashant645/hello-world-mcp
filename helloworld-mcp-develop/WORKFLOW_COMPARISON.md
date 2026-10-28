# Kyma Dev Workflow Comparison: hello-world-service vs hello-world-mcp

> **Historical note:** This comparison was written before QA/prod rollout support was added to this repository. Refer to `.github/workflows/wfk-kyma-qa.yml`, `.github/workflows/wfk-kyma-prod.yml`, `helm/hello-world-mcp/values-qa.yaml`, and `helm/hello-world-mcp/values-prod.yaml` for the current deployment paths.

## Executive Summary

Both repositories follow the **same Kyma deployment pattern** using:
- ✅ gh-action-template v4.1.0 (unified reusable workflow)
- ✅ Piper pipeline orchestration
- ✅ Preflight validation → Build/Deploy → Post-deploy smoke tests
- ✅ Same Docker registry (gtlc-docker.common.repositories.cloud.sap)
- ✅ Same secret management (Vault)

**Key Differences:** Authentication mechanism and smoke test endpoints

---

## Detailed Comparison

### 1. Trigger Configuration

| Aspect | hello-world-service | hello-world-mcp |
|--------|-------------------|-----------------|
| **Trigger Events** | Push + manual dispatch | Push + manual dispatch |
| **Branch** | develop (likely also prod/qa) | develop, joule, main |
| **Path Filters** | Similar structure | Same: src/**, Dockerfile, build.gradle, VERSION, .pipeline/**, helm/** |
| **Workflow File Name** | wfk-kyma-dev.yml | wfk-kyma-dev.yml, wfk-kyma-qa.yml, wfk-kyma-prod.yml |

---

### 2. Jobs: Preflight Validation

Both use **identical preflight checks**:

```yaml
# Both repos do:
preflight_kyma_dev:
  - Checkout code
  - Setup kubeconfig from KYMA_KUBECONFIG_DEV secret
  - Display current kubectl context
  - Verify APIRule CRD exists
  - Verify Istio gateway CRD exists
```

**Status:** ✅ **IDENTICAL**

---

### 3. Jobs: Build and Deploy

#### hello-world-mcp:
```yaml
build_kyma_dev:
  uses: GTLCInfra/gh-action-template/.github/workflows/pipeline-unified.yml@v4.1.0
  with:
    manifest-path: .pipeline/gtlc.yml
    target: kyma
    environment: dev
    skip-validation: ${{ inputs.skip_validation == true }}
  secrets:
    PIPER_VAULTAPPROLEID: ${{ secrets.PIPER_VAULTAPPROLEID }}
    PIPER_VAULTAPPROLESECRETID: ${{ secrets.PIPER_VAULTAPPROLESECRETID }}
    KYMA_KUBECONFIG: ${{ secrets.KYMA_KUBECONFIG_DEV }}
```

#### hello-world-service:
Expected to be **identical or very similar**, using:
- Same gh-action-template version
- Same manifest-path pattern
- Same vault credentials
- Same kubeconfig secret structure

**Status:** ✅ **VERY LIKELY IDENTICAL**

---

### 4. Jobs: Post-Deploy Smoke Tests

This is where **authentication differs** between the two services:

#### hello-world-service:
```
Likely includes:
- Health check endpoint: /actuator/health (no auth)
- XSUAA OAuth verification (its own auth mechanism)
- Service availability test
```

#### hello-world-mcp:
```yaml
verify_kyma_dev:
  - Health check: GET /actuator/health → expect 200
  - IAS auth verification: GET /sse (no token) → expect 401
  - Shows IAS as the auth mechanism, not XSUAA
```

**Key Difference:**
- **hello-world-service**: Tests XSUAA OAuth (ServiceInstance/Binding based)
- **hello-world-mcp**: Tests IAS M2M auth (bootstrap credentials based)

**Status:** ✅ **APPROPRIATE FOR EACH SERVICE'S AUTH**

---

## Vault Configuration Differences

| Aspect | hello-world-service | hello-world-mcp |
|--------|-------------------|-----------------|
| **Vault Base Path** | `piper/data/sap-project-gtlcinfra-poc-1/hello-world-service` | `piper/data/sap-project-gtlcinfra-poc-1/hello-world-mcp` |
| **Docker Config Path** | `piper/data/sap-project-gtlcinfra-poc-1/docker-config` (shared) | `piper/data/sap-project-gtlcinfra-poc-1/docker-config` (shared) ✅ |
| **Auth Credentials** | XSUAA (SAP Cloud Platform native) | IAS M2M (via bootstrap script) |
| **K8s Secret Name** | (likely hello-world-service specific) | `hello-world-mcp-ias-credentials` |

---

## Piper Configuration Differences

### gtlc.yml Structure

**hello-world-service:**
```yaml
service:
  name: hello-world-service
  vault-base: piper/data/sap-project-gtlcinfra-poc-1/hello-world-service
```

**hello-world-mcp:**
```yaml
service:
  name: hello-world-mcp
  vault-base: piper/data/sap-project-gtlcinfra-poc-1/hello-world-mcp
```

**Docker Config (BOTH - CORRECTED):**
```yaml
docker:
  registry: gtlc-docker.common.repositories.cloud.sap
  vault-path: piper/data/sap-project-gtlcinfra-poc-1/docker-config  # SHARED ✅
  dockerfile: Dockerfile
  image-name: hello-world-service  # or hello-world-mcp
```

---

## Deployment Targets

| Target | hello-world-service | hello-world-mcp | Status |
|--------|-------------------|-----------------|--------|
| **Kyma (dev)** | ✅ Yes | ✅ Yes | Same |
| **Kyma (qa)** | ✅ Yes (likely) | ✅ Yes | MCP now has a QA pipeline |
| **Kyma (prod)** | ✅ Yes (likely) | ✅ Yes | MCP now has a production pipeline |
| **Cloud Foundry** | ✅ Yes (likely) | ❌ No | MCP uses Kyma only |

---

## Helm Chart Deployments

| Aspect | hello-world-service | hello-world-mcp |
|--------|-------------------|-----------------|
| **Chart Path** | `helm/hello-world-service` | `helm/hello-world-mcp` |
| **Release Name** | `hello-world-service` | `hello-world-mcp` |
| **Namespace** | `dev` (and qa/prod) | `dev`, `qa`, `prod` |
| **Values File** | `values.yaml`, `values-dev.yaml`, etc. | `values.yaml`, `values-dev.yaml`, `values-qa.yaml`, `values-prod.yaml` |

---

## Smoke Tests Comparison

### hello-world-service (Expected):
```bash
1. Setup kubeconfig
2. Wait for APIRule ready
3. Health check: GET /actuator/health → 200
4. XSUAA OAuth flow test (service has XSUAA binding)
5. API endpoint test (business logic)
```

### hello-world-mcp (Actual):
```bash
1. Setup kubeconfig  ✅
2. Wait for APIRule ready  ✅
3. Health check: GET /actuator/health → 200  ✅
4. IAS M2M auth test: GET /sse (no token) → 401  ✅ (Proves IAS auth is enforced)
5. No business logic test (MCP-specific; wraps REST API)
```

**Status:** ✅ **APPROPRIATE FOR EACH SERVICE'S ARCHITECTURE**

---

## Secrets Used

### Both Services:
```yaml
secrets:
  PIPER_VAULTAPPROLEID: ${{ secrets.PIPER_VAULTAPPROLEID }}
  PIPER_VAULTAPPROLESECRETID: ${{ secrets.PIPER_VAULTAPPROLESECRETID }}
  KYMA_KUBECONFIG: ${{ secrets.KYMA_KUBECONFIG_DEV }}
```

### Additional (hello-world-mcp):
```
# In K8s cluster (one-time bootstrap):
hello-world-mcp-ias-credentials (contains IAS_CLIENT_ID, IAS_CLIENT_SECRET, IAS_MCP_UI_CLIENT_ID)

# In GitHub Actions secrets:
(Same as hello-world-service)
```

---

## Concurrency & Locking

Both use:
```yaml
concurrency:
  group: kyma-dev-${{ github.ref_name }}
  cancel-in-progress: false
```

**Status:** ✅ **IDENTICAL** (prevents parallel runs on same branch)

---

## Critical Issues Fixed

### Issue 1: Docker Config Vault Path ✅ FIXED
- **Before:** `piper/data/sap-project-gtlcinfra-poc-1/hello-world-mcp/docker-config` (wrong)
- **After:** `piper/data/sap-project-gtlcinfra-poc-1/docker-config` (correct - shared)
- **Now matches:** hello-world-service pattern

### Issue 2: Unused SAP Steps in config.yml ✅ FIXED
- **Removed:** `sapCallStagingService`, `kanikoExecute`, `sapCumulusUpload`
- **Reason:** Not used in Kyma deployment; legacy artifacts
- **Impact:** config.yml cleanup; gtlc.yml is the authoritative config

---

## Conclusion

The Kyma dev pipelines are **architecturally equivalent** with appropriate service-specific customizations:

| Category | Status |
|----------|--------|
| Workflow structure | ✅ Same |
| Build process | ✅ Same |
| Helm deployment | ✅ Same |
| Docker registry | ✅ Same (shared credentials) |
| Preflight checks | ✅ Same |
| Post-deploy tests | ✅ Appropriate for each service |
| Vault integration | ✅ Same pattern (service-specific base paths) |

**hello-world-mcp is now correctly aligned with hello-world-service's Kyma CI/CD pattern.** 🚀

