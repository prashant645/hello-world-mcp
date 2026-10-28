# Kyma Pipeline Strategy: Hybrid Approach

## Architecture

**Two different deployment strategies for different purposes:**

| Pipeline | Trigger | Purpose | Technology | Vault? |
|----------|---------|---------|-----------|--------|
| **PR Build** | Pull Request | Validation only (no deployment) | **Piper** (reusable workflow) | ✅ Yes |
| **Dev Deployment** | Push to `develop` | Actual Kyma deployment | **Direct Docker + Helm** | ❌ No (GitHub secrets) |

---

## Why This Hybrid Approach?

### PR Build (Keep Piper)
- ✅ Validates code changes before merge
- ✅ Builds Docker image (for validation)
- ✅ Runs security/quality checks
- ✅ **No actual deployment** → Lower risk if Vault has issues
- ✅ Uses Piper's built-in validation framework

### Dev Deployment (Direct Docker + Helm)
- ✅ Actual production Kyma deployment
- ✅ **Simpler and more reliable** - Direct control
- ✅ No Vault path complications
- ✅ No JSON format issues
- ✅ Faster execution (no Piper overhead)
- ✅ All steps visible and transparent

---

## Workflow Files

### `.github/workflows/pr-build.yml` (UNCHANGED - Still uses Piper)
```yaml
on:
  pull_request:
    branches: [develop, main]

jobs:
  build_pr:
    uses: GTLCInfra/gh-action-template/.github/workflows/pr-build.docker.yml@v4.1.0
    with:
      custom-defaults-paths: '.pipeline/env-pr.yml'
    secrets:
      PIPER_VAULTAPPROLEID: ${{ secrets.PIPER_VAULTAPPROLEID }}
      PIPER_VAULTAPPROLESECRETID: ${{ secrets.PIPER_VAULTAPPROLESECRETID }}
```

**Purpose:** Validate incoming PRs before merge
**No deployment** - Just build and test

---

### `.github/workflows/wfk-kyma-dev.yml` (REWRITTEN - Uses Direct Docker + Helm)
```yaml
on:
  push:
    branches: [develop]

jobs:
  preflight_kyma_dev:
    # Validate cluster access
  
  build_kyma_dev:
    # Gradle build
    # Docker login (GitHub secret)
    # Docker build + push
    # Helm deploy
  
  verify_kyma_dev:
    # Smoke tests
```

**Purpose:** Deploy to Kyma dev namespace
**Direct deployment** - Full control, no Piper

---

## Configuration Files

### Still in Use:
- **`.pipeline/env-pr.yml`** - Used by PR builds (Piper)
- **`.pipeline/env-dev.yml`** - Optional, can be kept as reference

### Legacy (No Longer Used):
- **`.pipeline/gtlc.yml`** - Was for Piper dev deployment (now using direct Docker + Helm)
- **`.pipeline/config.yml`** - Was for Piper (now using direct Docker + Helm)

---

## Setup Required (Dev Deployment Only)

### Add GitHub Secret: `DOCKER_CONFIG_JSON`

**Go to:** `https://github.tools.sap/GTLCInfra/helloworld-mcp/settings/secrets/actions`

**Add new secret:**
- **Name:** `DOCKER_CONFIG_JSON`
- **Value:** (Escaped JSON string)

```json
{"auths":{"gtlc-docker.common.repositories.cloud.sap":{"auth":"YOUR_BASE64_ENCODED_CREDS"}}}
```

**Note:** PR builds don't need this (they use Piper + Vault)

---

## How It Works

### When: PR opened/updated
```
GitHub PR → Piper validation workflow
         → Builds Docker image (for testing)
         → Validates code
         → Reports status
         → (No deployment)
```

### When: Commit pushed to develop
```
GitHub Push → Direct GitHub Actions
           → Preflight checks
           → Gradle build
           → Docker login (GitHub secret)
           → Docker build + push
           → Helm deploy to Kyma dev
           → Smoke tests
```

---

## Benefits of This Approach

✅ **PR validation** - Uses trusted Piper framework (lower risk)
✅ **Dev deployment** - Direct control, no Vault issues
✅ **Separation of concerns** - Different strategies for different purposes
✅ **Resilience** - PR checks still work even if dev deployment Vault fails
✅ **Transparency** - Dev deployment steps are explicit and visible
✅ **Cost** - No Piper overhead for actual deployment

---

## Troubleshooting

### PR Build Fails
Check `.pipeline/env-pr.yml` and Vault credentials (Piper's responsibility)

### Dev Deployment Fails
Check `DOCKER_CONFIG_JSON` GitHub secret and Kyma cluster access

---

## Files Changed

| File | Status | Used By |
|------|--------|---------|
| `.github/workflows/pr-build.yml` | ✅ Unchanged | Piper (PR validation) |
| `.github/workflows/wfk-kyma-dev.yml` | ✅ Rewritten | Direct Docker + Helm (dev deployment) |
| `.pipeline/env-pr.yml` | ✅ Still used | Piper (PR validation) |
| `.pipeline/gtlc.yml` | ❌ Deprecated | (Reference only) |
| `.pipeline/config.yml` | ❌ Deprecated | (Reference only) |

---


