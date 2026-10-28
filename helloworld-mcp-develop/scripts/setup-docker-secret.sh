#!/bin/bash
# Helper script to properly format docker config for GitHub secret or Vault

# Instructions:
# This script shows how to properly format the docker credentials as a JSON string

echo "=== GitHub Secret Format ==="
echo ""
echo "1. Go to: https://github.tools.sap/GTLCInfra/helloworld-mcp/settings/secrets/actions"
echo "2. Click 'New repository secret'"
echo "3. Name: DOCKER_CONFIG_JSON"
echo "4. Value: Copy the following (it's a JSON STRING, not an object)"
echo ""

# Create the docker config JSON string with proper escaping
AUTH_VALUE="cmVwb3NzdGxjOmNtVm1kR3R1T2pBeE9qRTRNakUwTURRMU9EYzZTMmQwZDJ0T1REa3dlbmxYU2s5SE5tOXVVMlJVTm1oNWJtZ3k="
REGISTRY="gtlc-docker.common.repositories.cloud.sap"

# This is the proper escaped JSON string format that Piper expects:
DOCKER_CONFIG_JSON="{\"auths\":{\"${REGISTRY}\":{\"auth\":\"${AUTH_VALUE}\"}}}"

echo "$DOCKER_CONFIG_JSON"
echo ""
echo "5. Click 'Add secret'"
echo ""
echo "=== Alternatively: Update Vault Secret ==="
echo ""
echo "Go to Vault secret: piper/data/sap-project-gtlcinfra-poc-1/helloworld-mcp/docker-config"
echo "Replace 'dockerConfigJSON' value with:"
echo "$DOCKER_CONFIG_JSON"
echo ""
echo "The key difference:"
echo "- WRONG: dockerConfigJSON is a nested object { \"auths\": { ... } }"
echo "- RIGHT: dockerConfigJSON is a STRING containing escaped JSON"

