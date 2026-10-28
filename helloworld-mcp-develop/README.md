# Hello World MCP Server

A Spring AI MCP (Model Context Protocol) server deployed on SAP BTP Kyma. It exposes the Hello World backend service as MCP tools that Joule Work Desktop can call conversationally.

## Current Status

- ✅ Dev pipeline is working on branch `joule`
- ✅ Deployment to the dev Kyma environment is working
- ✅ Joule Work Desktop integration is working end to end
- ✅ QA environment values and deployment workflow are available in the repo
- ✅ Production environment values and deployment workflow are available in the repo

---

## Architecture

```
Joule Work Desktop
      │
      │  OAuth 2.0 Authorization Code + PKCE (IAS)
      ▼
hello-world-mcp  ──── (A) M2M: client_credentials token ──────────────▶  hello-world-service
(this repo)      ──── (B) OBO: RFC 8693 token-exchanged user token ──▶  (backend REST API)
```

Three credential flows are involved:

| Flow | Direction | Grant type | When |
|------|-----------|------------|------|
| **Incoming** | Joule → MCP server | Authorization Code + PKCE | Every request — authenticates the desktop user |
| **Outgoing (A) M2M** | MCP server → backend | Client Credentials | No human user in SecurityContext (e.g. health checks, technical callers) |
| **Outgoing (B) OBO** | MCP server → backend | RFC 8693 Token Exchange | Human user JWT present in SecurityContext (`user_uuid` claim detected) |

### Authorization resolution at the backend

The backend (hello-world-service) resolves permissions differently depending on whether the incoming token is M2M or OBO:

**M2M (no `user_uuid` in token):**
- Permission is read from the `ias_apis` claim in the token.
- Currently `hello-world-mcp-iasams-dev` has `scope = writers`, so the token carries `ias_apis: ["writers"]`.
- The backend `@PreAuthorize` gate checks this claim directly. AMS is **not** consulted.
- This path is fully honoured when no human principal propagation occurs.

**OBO / Principal Propagation (`user_uuid` present):**
- The MCP server exchanges the user JWT via RFC 8693. The exchanged token carries the user's identity.
- The backend applies a two-tier resolution (`resolveOboAuthorization`):
  1. **User has an AMS policy binding** → that policy is the sole authorizer; `ias_apis` from the MCP server is ignored for that user.
  2. **User has no AMS policy (unenrolled)** → the MCP server's `ias_apis` (e.g. `writers`) is inherited as the effective permission. If `ias_apis` is absent, the request is denied.
- AMS must be reachable; if unavailable the OBO request is denied (fail-closed).

---

## MCP Tools

| Tool | Backend endpoint | M2M: required `ias_apis` | OBO: AMS policy OR MCP `ias_apis` fallback |
|------|-----------------|--------------------------|---------------------------------------------|
| `createGreeting` | `POST /api/v1/greetings` | readers / writers / admins | AMS `read` on DataResource, or MCP ias_apis ∈ {readers, writers, admins} |
| `updateConfiguration` | `POST /api/v1/greetings/update` | writers / admins | AMS `write` on DataResource, or MCP ias_apis ∈ {writers, admins} |
| `getGreetingConfig` | `GET /api/v1/greetings/config` | readers / writers / admins | AMS `read` on DataResource, or MCP ias_apis ∈ {readers, writers, admins} |
| `getActuatorInfo` | `GET /actuator/info` | none | none |

> For M2M calls the `ias_apis` claim in the token is the sole gate — AMS is not consulted.
> For OBO calls the backend first checks the user's AMS policy, then falls back to MCP `ias_apis` if the user is unenrolled. AMS unavailability causes a hard deny (fail-closed).

---

## Prerequisites

### 1 — IAS application: environment-specific Joule UI app (Joule Desktop users)

This IAS application is the OAuth client that Joule Work Desktop uses to authenticate users.
Its client ID is configured as the expected JWT audience in the MCP server
(`IAS_MCP_UI_CLIENT_ID` env var).

IAS tenant: `https://a3enmtmdf.accounts400.ondemand.com`

Navigate to the application in the IAS admin console and verify:

#### a) Enable Public Client Flows

`Trust → OpenID Connect Configuration → Client Authentication → Public Client`

- **Enable Public Client Flows: ON**

Joule Work Desktop uses PKCE (OAuth 2.0 Authorization Code + PKCE). It does **not** send a
client secret. Without this toggle, IAS will reject every authorization request from Joule.

#### b) Register the Joule Work Desktop redirect URI

`Trust → OpenID Connect Configuration → Redirect URIs → Add`

```
http://localhost:18766/mcp-callback
```

Joule Work Desktop binds a local HTTP listener on port 18766 to receive the OAuth callback
(RFC 8252 loopback redirect). IAS will reject the authorization request if this URI is not
on the allowlist — the OAuth flow will fail silently and the connector will show
_"Couldn't connect. Ensure this URL points to a valid MCP server URL."_

#### c) No client secret required

Leave **Secrets** empty. PKCE replaces the client secret for public clients.

---

### 2 — Kubernetes secret: `hello-world-mcp-ias-credentials` (M2M backend calls)

The MCP server calls `hello-world-service` using a separate IAS client credentials grant.
Create this secret once in each target namespace before deploying:

```bash
kubectl create secret generic hello-world-mcp-ias-credentials \
  --namespace <your-namespace> \
  --from-literal=IAS_CLIENT_ID=<m2m-client-id> \
  --from-literal=IAS_CLIENT_SECRET=<m2m-client-secret>
```

The M2M client ID and secret come from the IAS application used by `hello-world-service`
for service-to-service authentication. The secret name must match
`ias.credentialsSecretName` in the environment values file (`values-dev.yaml`, `values-qa.yaml`, `values-prod.yaml`; default: `hello-world-mcp-ias-credentials`).

---

## Deployment

Deployed via Helm. The CI/CD pipeline sets the image tag automatically.

```bash
helm upgrade --install hello-world-mcp ./helm/hello-world-mcp \
  -f ./helm/hello-world-mcp/values-dev.yaml \
  --namespace <your-namespace>
```

For QA, use the QA values file:

```bash
helm upgrade --install hello-world-mcp ./helm/hello-world-mcp \
  -f ./helm/hello-world-mcp/values-qa.yaml \
  --namespace qa
```

For production, use the prod values file:

```bash
helm upgrade --install hello-world-mcp ./helm/hello-world-mcp \
  -f ./helm/hello-world-mcp/values-prod.yaml \
  --namespace prod
```

Key values in the environment values files (`values-dev.yaml`, `values-qa.yaml`, `values-prod.yaml`):

| Value | Description |
|-------|-------------|
| `ias.mcpUiClientId` | Environment-specific IAS UI client ID — used for JWT audience validation |
| `ias.credentialsSecretName` | K8s Secret with M2M credentials for backend calls |
| `backend.baseUrl` | URL of the hello-world-service backend |
| `apirule.host` | Public hostname — also sets `MCP_PUBLIC_BASE_URL` inside the pod |

### GitHub Actions deployment workflows

- `wfk-kyma-dev.yml` — automatic dev deployment on push to `develop`
- `wfk-kyma-qa.yml` — manual QA deployment
- `wfk-kyma-prod.yml` — manual prod deployment and push to `main`

The QA workflow uses `KYMA_KUBECONFIG_QA` when that secret is present. If QA runs on the
same cluster/context as dev, it automatically falls back to `KYMA_KUBECONFIG_DEV`.
The prod workflow uses `KYMA_KUBECONFIG_PROD`.

---

## Adding the Connector in Joule Work Desktop

Extensions → Connectors → **+ New** → **Add Connector**

| Field | Value |
|-------|-------|
| **URL** | `https://hello-world-mcp-dev.c-1282d6a.stage.kyma.ondemand.com/sse` |
| **Client ID** | `32e0450f-44a1-40ec-a4f4-292777d52eca` |
| **Client Secret** | _(leave blank — PKCE, no secret needed)_ |

Click **Add**. Joule will open a browser window for IAS login. After successful authentication,
the connector status turns active and all four MCP tools are available.

> **If you see "Couldn't connect"**: verify both IAS prerequisites above (Public Client Flows
> enabled and redirect URI registered). These must be in place before the OAuth flow can complete.

---

## OAuth Flow (how it works end-to-end)

1. Joule Desktop connects to `/sse` → receives `401` with
   `WWW-Authenticate: Bearer resource_metadata="https://<host>/.well-known/oauth-protected-resource"`
2. Joule fetches `/.well-known/oauth-protected-resource` → discovers IAS as the authorization server
3. Joule opens the user's browser to `/authorize` (proxied by `OAuthBridgeController` to IAS)
   with `client_id`, `redirect_uri=http://localhost:18766/mcp-callback`, and a PKCE challenge
4. User authenticates in IAS → IAS redirects to `http://localhost:18766/mcp-callback?code=...`
5. Joule exchanges the code at `/token` (proxied to IAS) → receives an ID token
6. Joule attaches the token as `Authorization: Bearer <token>` on all subsequent SSE and tool-call requests
7. The MCP server validates the token: checks issuer, signature, expiry, and audience (`aud` must include `IAS_MCP_UI_CLIENT_ID`)
