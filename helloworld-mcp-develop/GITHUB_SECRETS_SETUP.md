# GitHub Actions Secrets Setup

## Required Secrets for Kyma Dev / QA / Prod Deployment

The deployment workflow needs the following secret to be configured in GitHub:

### 1. **KYMA_KUBECONFIG_DEV**
- Base64-encoded kubeconfig for accessing the Kyma cluster

### 2. **KYMA_KUBECONFIG_QA** (Optional)
- Required only if the QA workflow targets a different cluster or kubeconfig context than dev
- If QA runs on the same cluster/context as dev, the QA workflow falls back to `KYMA_KUBECONFIG_DEV`

### 3. **KYMA_KUBECONFIG_PROD**
- Base64-encoded kubeconfig for accessing the prod Kyma cluster

## IAS Credentials Are Stored In Kubernetes, Not GitHub Actions

The workflow now **verifies** that the Kubernetes secret exists, but it does **not** recreate or overwrite it.

Create or rotate the secret manually with `platform-ops/kyma/bootstrap-ias-secret.sh` before running the deployment pipeline.

Required Kubernetes secret values:

| Secret Key | Source IAS App | Purpose |
|------------|----------------|---------|
| `IAS_CLIENT_ID` | `hello-world-mcp-iasams-dev` | M2M client ID for MCP → hello-world-service |
| `IAS_CLIENT_SECRET` | `hello-world-mcp-iasams-dev` | M2M client secret |
| `IAS_MCP_UI_CLIENT_ID` | `helloworld-mcp-ui-dev` | Audience validation for Joule user tokens |

## GitHub Secrets

### Via Web UI (Easiest)

1. Go to your GitHub repository:
   ```
   https://github.tools.sap/GTLCInfra/helloworld-mcp/settings/secrets/actions
   ```

2. Confirm `KYMA_KUBECONFIG_DEV` exists.
3. If QA uses a different cluster/context, add `KYMA_KUBECONFIG_QA` too.
4. Confirm `KYMA_KUBECONFIG_PROD` exists.

### Manual Kubernetes bootstrap

```bash
IAS_CLIENT_ID='<mcp-m2m-client-id>' \
IAS_CLIENT_SECRET='<mcp-m2m-client-secret>' \
IAS_MCP_UI_CLIENT_ID='<joule-ui-client-id>' \
NAMESPACE=<dev-or-qa-or-prod> \
bash platform-ops/kyma/bootstrap-ias-secret.sh
```

## How the Secrets Are Used

### In the Deployment Workflow

When you deploy to any Kyma environment:

1. **Preflight checks** verify cluster access
2. **Verification step** checks that `hello-world-mcp-ias-credentials` exists in Kubernetes and contains the required keys
3. **Helm deployment** reads from this Kubernetes secret
4. **MCP pod** uses credentials to:
   - Call hello-world-service (M2M auth via IAS_CLIENT_ID/IAS_CLIENT_SECRET)
   - Validate Joule Desktop tokens (via IAS_MCP_UI_CLIENT_ID)

## Verification

After adding the GitHub kubeconfig secret(s), you can verify they're set:

```bash
gh secret list
```

You should see at least:
```
KYMA_KUBECONFIG_DEV
PIPER_VAULTAPPROLEID
PIPER_VAULTAPPROLESECRETID
```

If QA uses a different cluster/context, you should also see:
```
KYMA_KUBECONFIG_QA
```

For production, you should also see:
```
KYMA_KUBECONFIG_PROD
```

To verify the Kubernetes secret exists:

```bash
kubectl get secret hello-world-mcp-ias-credentials -n <dev-or-qa-or-prod>
```

## Next Steps

1. ✅ Bootstrap or rotate `hello-world-mcp-ias-credentials` in Kubernetes
2. Push a commit to `develop` branch to trigger the dev workflow, or run `Kyma Pipeline - qa` / `Kyma Pipeline - prod` manually for QA/prod
3. Monitor the deployment in the Actions tab
4. The workflow will fail fast if the Kubernetes secret is missing or incomplete

## Troubleshooting

### "secret not found" error during preflight
- Verify `hello-world-mcp-ias-credentials` exists in the target namespace (`dev`, `qa`, or `prod`)
- Verify the secret contains `IAS_CLIENT_ID`, `IAS_CLIENT_SECRET`, and `IAS_MCP_UI_CLIENT_ID`
- Re-run `platform-ops/kyma/bootstrap-ias-secret.sh` if credentials were rotated

### "context deadline exceeded" in Helm deploy
- This usually means the pod can't start
- The pod will be in CrashLoopBackOff because IAS credentials are missing
- Once secrets are properly configured, the pod should start successfully

---

**Status**: Workflow validates the Kubernetes IAS secret; operators manage the secret contents manually.

