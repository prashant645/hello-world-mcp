# GitHub Actions & CI/CD Configuration Analysis

> **Historical note:** This document captures an earlier gap analysis. The current repository now contains the workflow files under `.github/workflows/`, including the QA deployment workflow `wfk-kyma-qa.yml`.

## Current State of hello-world-mcp

### What Exists ✅
1. **Kyma Dev Deployment Workflow** (`.github/workflows/wfk-kyma-dev.yml`)
   - Triggered on push to `develop` branch
   - Includes preflight checks, build/deploy via Helm, and smoke tests
   - Uses gh-action-template 4.1.0 (`pipeline-unified.yml`)
   - Has post-deploy health check and auth verification

2. **Pipeline Configuration** (`.pipeline/gtlc.yml`)
   - Configured for Kyma target with dev environment
   - Helm chart path: `helm/hello-world-mcp`
   - Uses Vault for secrets
   - Gradle build tool configured

3. **Base Scripts**
   - `scripts/setup-kubeconfig-kubectl.sh` - Kubeconfig setup
   - `scripts/wait-apirule-ready.sh` - APIRule readiness check
   - `scripts/bootstrap-ias-secret.sh` - IAS secret bootstrap

### What's Missing ❌

#### 1. **PR Build Workflow** (CRITICAL)
   - **File needed**: `.github/workflows/pr-build.yml`
   - **Purpose**: Validate code on pull requests before merge
   - **Triggers**: On PRs to `develop` and `main` branches
   - **Uses**: `gh-action-template/.github/workflows/pr-build.docker.yml@v4.1.0`
   - **Reference config**: `.pipeline/env-pr.yml`
   - **Status**: Without this, PRs won't be validated

#### 2. **Create Release Workflow** (RECOMMENDED)
   - **File needed**: `.github/workflows/create-release.yml`
   - **Purpose**: Automated release tagging and optional production deployments
   - **Triggers**: Manual workflow dispatch with version input
   - **Uses**: `gh-action-template/.github/workflows/create-release.yml@v4.1.0`
   - **Status**: Needed for release management

#### 3. **Kyma Drift Detection Workflow** (OPTIONAL but RECOMMENDED)
   - **File needed**: `.github/workflows/kyma-drift-detection.yml`
   - **Purpose**: Detect configuration drift in Kyma cluster
   - **Triggers**: Manual dispatch + scheduled daily
   - **Checks**: Deployment state vs. expected helm chart state
   - **Status**: Useful for operations/observability, not blocking

#### 4. **Environment Configuration Files** (OPTIONAL)
   - `.pipeline/env-pr.yml` - PR-specific overrides
     - Sets `productiveBranch: 'none'` 
     - Enables `dockerBuild` for validation
     - Disables `cloudFoundryDeploy`
   
   - `.pipeline/env-dev.yml` - Dev environment overrides (already covered in gtlc.yml but can be separated)

#### 5. **Supporting Scripts** (OPTIONAL)
   - `scripts/verify-kyma-bootstrap-contract.sh` - Validates bootstrap identities and deployer permissions
   - `scripts/test-ias-auth.sh` - IAS authentication smoke test (if using IAS)
   - `scripts/sync-version.sh` - Synchronizes VERSION file across multiple locations

### Configuration Differences from Reference

| Aspect | hello-world-service | hello-world-mcp | Status |
|--------|-------------------|-----------------|--------|
| Kyma Environments | dev, qa, prod | dev only | ✅ As specified (user wants only dev) |
| Cloud Foundry | Configured (dev/qa/prod) | Not configured | ✅ As specified (Kyma only) |
| Helm Chart | `helm/hello-world-service` | `helm/hello-world-mcp` | ✅ Correct |
| Docker Registry | `gtlc-docker.common.repositories.cloud.sap` | `gtlc-docker.common.repositories.cloud.sap` | ✅ Same |
| Vault Base | `piper/data/.../hello-world-service` | `piper/data/.../hello-world-mcp` | ✅ Different (correct) |
| Smoke Tests | IAS auth verification + health check | Health check + SSE 401 check | ⚠️ Different verification logic |
| gh-action-template | v4.1.0 | v4.1.0 | ✅ Same |

### Deployment Integration Points

**MCP talks to hello-world-service in dev namespace:**
- ✅ Both deploy to same Kyma cluster
- ✅ Both in `dev` namespace
- ✅ MCP uses IAS credentials (NOT shared XSUAA)
- ✅ hello-world-service has own XSUAA ServiceInstance/Binding
- ✅ No resource conflicts — each has isolated auth mechanism
- ✅ MCP needs hello-world-service endpoint: `hello-world-service.dev.svc.cluster.local`
- ✅ Current wfk-kyma-dev.yml does NOT provision shared resources — scope is correct

**Resource Ownership:**
| Resource | hello-world-service | hello-world-mcp | Notes |
|----------|-------------------|-----------------|-------|
| XSUAA ServiceInstance | ✅ Owns | ❌ Doesn't touch | Service uses XSUAA for OAuth |
| IAS Credentials Secret | ❌ Doesn't use | ✅ Owns | MCP uses IAS directly |
| Deployment | ✅ Own instance | ✅ Own instance | Separate pods, same namespace |
| Service | ✅ Own service | ✅ Own service | Different DNS names |
| APIRule | ✅ Own route | ✅ Own route | Different external hosts |

### Gaps to Address

**MUST HAVE (for proper CI/CD):**
1. Add `pr-build.yml` workflow
2. Add `env-pr.yml` configuration

**SHOULD HAVE (for complete pipeline):**
3. Add `create-release.yml` workflow
4. Add `kyma-drift-detection.yml` workflow

**OPTIONAL but RECOMMENDED (for better observability):**
5. Add `scripts/verify-kyma-bootstrap-contract.sh` (with MCP-specific bootstrap checks)
6. Review and potentially customize smoke tests for MCP behavior
7. Add `.pipeline/env-dev.yml` for explicit dev overrides (optional)

**COMPLETED (manual operations organized):**
8. Moved one-time bootstrap work to `platform-ops/kyma/`:
   - `bootstrap-ias-secret.sh` — IAS credentials Secret creation
   - `manifests/` — Reference K8s manifests
   - See `platform-ops/README.md` for bootstrap procedure

### Immediate Next Steps

1. **Priority 1**: Create `pr-build.yml` + `.pipeline/env-pr.yml`
   - Unblock PR validation
   - Estimated effort: 10 minutes

2. **Priority 2**: Create `create-release.yml`
   - Enable release workflow
   - Estimated effort: 5 minutes

3. **Priority 3**: Create `kyma-drift-detection.yml`
   - Optional but useful for ops
   - Estimated effort: 15 minutes (adapt from reference)

4. **Priority 4**: Review/adjust smoke tests in `wfk-kyma-dev.yml`
   - Current tests are basic but may need MCP-specific validation
   - Estimated effort: 10 minutes

---

**Total effort to full parity (Priority 1-3)**: ~30 minutes

