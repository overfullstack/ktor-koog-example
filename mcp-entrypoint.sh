#!/bin/sh
# Generate mcp-secrets.env from environment variables
echo "google-maps.api_key=${GOOGLE_MAPS_API_KEY}" > /mcp-secrets.env

# Execute the gateway with all arguments passed to this script
exec /docker-mcp gateway run "$@"
