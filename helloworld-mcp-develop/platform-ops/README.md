# Platform Operations – Manual Bootstrap & One-Time Setup

This directory contains **one-time human manual work** required to initialize the hello-world-mcp deployment in Kyma. These tasks are performed once per environment during initial setup and whenever credentials need to be rotated.

## Kyma Deployment Setup (`kyma/`)

### Overview

The MCP server deployment to Kyma requires a bootstrap step to create the IAS M2M credentials Secret. This is a **one-time** operation per environment (`dev`, `qa`, `prod`).

| Configuration | Managed by | Notes |
|---|---|---|
| Deployment, Service, APIRule, container builds | **Pipeline (automated)** | |
| M2M IAS credentials (`IAS_CLIENT_ID`, `IAS_CLIENT_SECRET`) | **Bootstrap script (one-time)** | Sensitive — stored in K8s Secret |
| Allowed MCP UI client IDs (`ias.mcpUiClientId`) | **Helm values per env** | Non-sensitive — comma-separated list in `values-<env>.yaml` |

---

### SAP Cloud Identity Services (IAS) Application Setup

Before running bootstrap, ensure the following applications are registered in the **SAP Cloud Identity Services console**:

#### Application 1: M2M Service Account (MCP → hello-world-service)
- **Type**: OAuth 2.0 / Confidential Client
- **Location**: Applications & Resources → Applications
- **Credentials needed**:
  - Application ID (GUID) → used as `IAS_CLIENT_ID`
  - Client Secret → used as `IAS_CLIENT_SECRET`
- **Used for**: Backend service-to-service authentication

#### Application 2: Joule Desktop connector (per environment)
- **Type**: OpenID Connect / Public Client (PKCE)
- **Location**: Applications & Resources → Applications (e.g. `helloworld-mcp-joule-ui-prod`)
- **Redirect URI**: Allowlist the callback URI that Joule Work Desktop presents during connector setup. Do not hardcode a port — use the URI displayed by the Joule client.
- **Client IDs per environment**:

  | Env | IAS App | Client ID |
  |-----|---------|-----------|
  | prod | `helloworld-mcp-joule-ui-prod` | `60bf3f28-a045-429e-91c4-e0d49ddd0d38` |
  | qa   | `helloworld-mcp-joule-ui-qa`   | `3c47ad61-7c15-4b51-bdb5-11ea2a1bbb6f` |
  | dev  | `helloworld-mcp-joule-ui-dev`  | `32e0450f-44a1-40ec-a4f4-292777d52eca` |

#### Application 3: Claude Code connector (per environment)
- **Type**: OpenID Connect / Public Client (PKCE), Enable Public Client Flows
- **Location**: Applications & Resources → Applications (e.g. `helloworld-mcp-claude-prod`)
- **Redirect URI**: Fixed loopback callback — Claude Code starts a local listener on the registered port.
- **Client IDs and redirect URIs per environment**:

  | Env | IAS App | Client ID | Redirect URI |
  |-----|---------|-----------|--------------|
  | prod | `helloworld-mcp-claude-prod` | `08da56ae-d144-493f-9869-ca3963f7a1ec` | `http://localhost:9191/callback` |
  | qa   | `helloworld-mcp-claude-qa`   | `16011b19-e0c7-426b-ba17-f4b6f5e16423` | `http://localhost:9292/callback` |
  | dev  | `helloworld-mcp-claude-dev`  | `f6f6dc30-e954-4fa0-abf7-58c20d76de03` | `http://localhost:9393/callback` |

> **Audience validation**: The MCP server (`SecurityConfig.java`) validates that the token's `aud` claim contains at least one of the client IDs listed in `ias.mcpUiClientId` (Helm values). Both Joule and Claude client IDs must be present for both clients to authenticate. The Helm values already contain the comma-separated list — no IAS dependency configuration is needed between the Joule app and the Claude app.

---

### Prerequisites

1. **kubectl access** to the target Kyma cluster:
   ```bash
   kubectl config current-context
   kubectl get ns dev qa prod
   ```

2. **M2M credentials** from the IAS admin console:
   - `IAS_CLIENT_ID` — Application ID of the M2M service account
     - Path: Applications & Resources → Applications → [M2M App] → Application ID
   - `IAS_CLIENT_SECRET` — Client secret of the M2M service account
     - Path: Applications & Resources → Applications → [M2M App] → Credentials

   > **Note**: `IAS_MCP_UI_CLIENT_ID` is **not** part of the secret. MCP UI client IDs
   > are non-sensitive and are managed in `helm/hello-world-mcp/values-<env>.yaml`.

3. **Target namespace exists**:
   ```bash
   kubectl create ns <dev|qa|prod> --dry-run=client -o yaml | kubectl apply -f -
   ```

---

### Bootstrap Procedure

#### Step 1: Create IAS M2M Credentials Secret (One-Time per Environment)

Run this **once** before the first pipeline deployment, or when rotating M2M credentials:

```bash
export IAS_CLIENT_ID="<m2m-app-id>"
export IAS_CLIENT_SECRET="<m2m-client-secret>"
export NAMESPACE="prod"   # or dev / qa

bash platform-ops/kyma/bootstrap-ias-secret.sh
```

**What it creates:**

| Key | Description |
|-----|-------------|
| `IAS_CLIENT_ID` | M2M client ID for MCP → hello-world-service auth (per namespace) |
| `IAS_CLIENT_SECRET` | M2M client secret |

The script uses `--dry-run=client | kubectl apply` — safe to re-run for rotation.

**Verification:**
```bash
kubectl get secret hello-world-mcp-ias-credentials -n <namespace>
kubectl get secret hello-world-mcp-ias-credentials -n <namespace> \
  -o jsonpath='{.data.IAS_CLIENT_ID}' | base64 -d
```

#### Step 2: First Pipeline Deployment

After the Secret is created, trigger the pipeline:

```bash
# Push to the target branch
git push origin develop   # → dev
git push origin joule     # → qa
git push origin main      # → prod

# Or trigger manually:
# GitHub Actions → "Kyma Pipeline - <env>" → "Run workflow"
```

The pipeline will build the image, deploy via Helm, and run health/auth smoke tests.

#### Step 3: Register Connector in Joule Work Desktop

1. Open **Joule Work Desktop**
2. Go to **Extensions → Connectors → Add Connector**
3. Fill in:
   - **Name**: `Hello World MCP` (or similar)
   - **URL**: MCP server URL for the environment:
     - prod: `https://hello-world-mcp-prod.c-1282d6a.stage.kyma.ondemand.com/mcp`
     - qa:   `https://hello-world-mcp-qa.c-1282d6a.stage.kyma.ondemand.com/mcp`
     - dev:  `https://hello-world-mcp-dev.c-1282d6a.stage.kyma.ondemand.com/mcp`
   - **Client ID**: Use the Joule client ID for the target environment (see Application 2 table above)
   - **Client Secret**: Leave blank — public PKCE client
4. Save. Joule will initiate the OIDC PKCE flow on first use.

> The MCP server does not control which redirect URI Joule uses. Joule sends its own
> `redirect_uri` in the authorize request. The IAS app must allowlist the exact URI
> that Joule presents — do not copy a URI from another client or assume a fixed port.

#### Step 4: Register MCP Server in Claude Code

Run once per environment. Each environment uses a different client ID and callback port.

**prod:**
```bash
claude mcp add \
  --transport http \
  --scope user \
  --callback-port 9191 \
  --client-id 08da56ae-d144-493f-9869-ca3963f7a1ec \
  kyma-hello-mcp https://hello-world-mcp-prod.c-1282d6a.stage.kyma.ondemand.com/mcp
```

**qa:**
```bash
claude mcp add \
  --transport http \
  --scope user \
  --callback-port 9292 \
  --client-id 16011b19-e0c7-426b-ba17-f4b6f5e16423 \
  kyma-hello-mcp-qa https://hello-world-mcp-qa.c-1282d6a.stage.kyma.ondemand.com/mcp
```

**dev:**
```bash
claude mcp add \
  --transport http \
  --scope user \
  --callback-port 9393 \
  --client-id f6f6dc30-e954-4fa0-abf7-58c20d76de03 \
  kyma-hello-mcp-dev https://hello-world-mcp-dev.c-1282d6a.stage.kyma.ondemand.com/mcp
```

After running `claude mcp add`, authenticate immediately:
```bash
claude
# then: /mcp → select the server → Authenticate
```

Verify the IAS authorize URL in the browser uses the correct `client_id` and `redirect_uri=http://localhost:<port>/callback` before completing login.

---

### Credential Rotation

**M2M credentials only** (re-run bootstrap script):
```bash
export IAS_CLIENT_ID="<new-client-id>"
export IAS_CLIENT_SECRET="<new-secret>"
export NAMESPACE="prod"   # or dev / qa

bash platform-ops/kyma/bootstrap-ias-secret.sh

# Restart pods to pick up new env vars
kubectl rollout restart deployment/hello-world-mcp --namespace prod
kubectl rollout status deployment/hello-world-mcp --namespace prod
```

**MCP UI client IDs** (Joule or Claude client ID changes):
Update `helm/hello-world-mcp/values-<env>.yaml` → `ias.mcpUiClientId` and deploy via pipeline.
No bootstrap script change required.

---

### Troubleshooting

**Secret not found on pod startup:**
```
Error from server: secret "hello-world-mcp-ias-credentials" not found
```
→ Run bootstrap script for the target namespace.

**Claude/Joule gets 401 after successful IAS login:**
```
Streamable HTTP error: Server returned 401 after successful authentication
```
→ Token audience mismatch. Check that the authenticating client's ID is in `ias.mcpUiClientId`
in `values-<env>.yaml`. Both Joule and Claude client IDs must be listed (comma-separated).
Check pod env: `kubectl exec deployment/hello-world-mcp -n <ns> -- env | grep IAS_MCP_UI_CLIENT_ID`

**Claude uses wrong client ID or random port:**
→ Stale OAuth cache. Run:
```bash
grep -r "<stale-client-id>" ~/.claude/
# Remove any files containing it, then:
claude mcp remove kyma-hello-mcp
claude mcp add --transport http --scope user --callback-port 9191 \
  --client-id 08da56ae-d144-493f-9869-ca3963f7a1ec \
  kyma-hello-mcp https://hello-world-mcp-prod.c-1282d6a.stage.kyma.ondemand.com/mcp
```

**Pods not starting after secret rotation:**
```bash
kubectl logs -n <namespace> -l app=hello-world-mcp --tail=50
```
→ Check for missing secret keys or IAS connectivity errors.

---

### Integration with hello-world-service

- **hello-world-service**: Uses XSUAA ServiceInstance/Binding (managed by that team)
- **hello-world-mcp**: Uses IAS M2M credentials for internal service-to-service calls

Both run in the same namespace with no resource conflicts.

---

## Support

For issues with Kyma bootstrap, drift detection, or credential management, refer to:
- `.github/workflows/README.md` — Workflow documentation
- `wfk-kyma-dev.yml` — Dev deployment pipeline
- `kyma-drift-detection.yml` — Cluster state validation
