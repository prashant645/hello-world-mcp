# Docker Credentials Fix: Two Options

The pipeline is failing because **Piper cannot read the Docker credentials** from Vault. The root cause is **format mismatch**.

You have **TWO OPTIONS** to fix this:

---

## Option A: Fix Vault Secret Format (RECOMMENDED - Quickest)

**This is the simplest fix.** The Vault secret is in the wrong format.

### Steps:

1. **Go to Vault:**
   ```
   https://vault.tools.sap
   Navigate to: Secrets / piper / sap-project-gtlcinfra-poc-1 / helloworld-mcp / docker-config
   ```

2. **Click the "Secret" tab** at the top

3. **Look at the current format** - it should look like:
   ```json
   {
     "dockerConfigJSON": {
       "auths": {
         "gtlc-docker...": {...}
       }
     }
   }
   ```
   This is WRONG (nested object).

4. **Click "Patch latest version"** button

5. **Replace the ENTIRE `dockerConfigJSON` value** with this **single escaped string** (copy exactly):
   ```
   {"auths":{"gtlc-docker.common.repositories.cloud.sap":{"auth":"cmVwb3NzdGxjOmNtVm1kR3R1T2pBeE9qRTRNakUwTURRMU9EYzZTMmQwZDJ0T1REa3dlbmxYU2s5SE5tOXVVMlJVTm1oNWJtZ3k="}}}
   ```

6. **Save the patch**

7. **Run the pipeline again**

### Why this works:
- Piper **expects** `dockerConfigJSON` to be a **JSON string** (with escaped quotes)
- Not a nested object
- This is how `hello-world-service` has it stored

---

## Option B: Use GitHub Secrets (Alternative)

If you really don't want to use Vault, we can use GitHub secrets, but it requires more changes:

### Steps:

1. **Add GitHub Secret:**
   ```
   Go to: https://github.tools.sap/GTLCInfra/helloworld-mcp/settings/secrets/actions
   Click: New repository secret
   Name: DOCKER_CONFIG_JSON
   Value: (copy the escaped string from Option A above)
   Click: Add secret
   ```

2. **Modify workflow** to use the secret (requires environment variable setup)

3. **This is more complex** and requires coordinating with the reusable workflow template

---

## Recommendation

**Go with Option A** - it's a one-time 2-minute fix in Vault UI, and then everything works. 

The core issue is that Vault stores it in the wrong format. Fixing it there solves the problem permanently for all pipelines using that secret.

---

## Test Command

After fixing Vault, you can test locally with:
```bash
# Retrieve the secret from Vault (using your AppRole credentials)
export VAULT_ADDR="https://vault.tools.sap"
export VAULT_NAMESPACE="ies/hyperspace/pipelines"

vault kv get piper/data/sap-project-gtlcinfra-poc-1/helloworld-mcp/docker-config

# Should show: dockerConfigJSON as a STRING (not an object)
```

---

## How to Verify It's Fixed

After patching Vault:
1. Push a commit to `develop` (or manually trigger the workflow)
2. The pipeline should get past the Docker push step
3. Look for: `Successfully tagged gtlc-docker.common.repositories.cloud.sap/hello-world-mcp:...`

If you see that message, you're done! ✅

