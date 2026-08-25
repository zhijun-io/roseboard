#!/usr/bin/env bash

require_command() {
    command -v "$1" >/dev/null 2>&1 || {
        echo "Missing required command: $1" >&2
        exit 1
    }
}

cleanup_delete() {
    local label="$1"
    local path="$2"
    local status
    status="$(curl -sS -o /dev/null -w '%{http_code}' -X DELETE \
        -H "Authorization: Bearer $ADMIN_TOKEN" "$BASE_URL$path")" || status=""
    if [[ "$status" != 2* ]]; then
        echo "[cleanup] failed to delete $label (HTTP ${status:-curl-error})" >&2
        exit_code=1
    fi
}

request() {
    local method="$1"
    local path="$2"
    local payload="${3:-}"
    local response
    local -a args=(-sS --fail-with-body -X "$method"
        -H "Authorization: Bearer $ADMIN_TOKEN"
        -w $'\n%{http_code}')
    if [[ -n "$payload" ]]; then
        args+=(-H 'Content-Type: application/json' --data "$payload")
    fi
    response="$(curl "${args[@]}" "$BASE_URL$path")" || {
        echo "HTTP request failed: $method $path" >&2
        echo "$response" >&2
        exit 1
    }
    HTTP_STATUS="${response##*$'\n'}"
    HTTP_BODY="${response%$'\n'*}"
    [[ "$HTTP_STATUS" == 2* ]] || {
        echo "Unexpected HTTP $HTTP_STATUS: $method $path" >&2
        echo "$HTTP_BODY" >&2
        exit 1
    }
}

request_noauth() {
    local method="$1"
    local path="$2"
    local payload="${3:-}"
    local response
    local -a args=(-sS --fail-with-body -X "$method"
        -H 'Content-Type: application/json'
        -w $'\n%{http_code}')
    if [[ -n "$payload" ]]; then
        args+=(--data "$payload")
    fi
    response="$(curl "${args[@]}" "$BASE_URL$path")" || {
        echo "HTTP request failed: $method $path" >&2
        echo "$response" >&2
        exit 1
    }
    HTTP_STATUS="${response##*$'\n'}"
    HTTP_BODY="${response%$'\n'*}"
    [[ "$HTTP_STATUS" == 2* ]] || {
        echo "Unexpected HTTP $HTTP_STATUS: $method $path" >&2
        echo "$HTTP_BODY" >&2
        exit 1
    }
}

request_without_admin() {
    request_noauth "$@"
}

request_multipart() {
    local path="$1"
    local file="$2"
    local response
    response="$(curl -sS --fail-with-body -X PUT \
        -H "Authorization: Bearer $ADMIN_TOKEN" \
        -F 'checksumAlgorithm=SHA-256' \
        -F "file=@$file;type=application/octet-stream" \
        -w $'\n%{http_code}' \
        "$BASE_URL$path")" || {
        echo "HTTP request failed: PUT $path" >&2
        echo "$response" >&2
        exit 1
    }
    HTTP_STATUS="${response##*$'\n'}"
    HTTP_BODY="${response%$'\n'*}"
    [[ "$HTTP_STATUS" == 2* ]] || {
        echo "Unexpected HTTP $HTTP_STATUS: PUT $path" >&2
        echo "$HTTP_BODY" >&2
        exit 1
    }
}

authenticate_admin() {
    if [[ -n "$ADMIN_TOKEN" ]]; then
        return
    fi
    if [[ -z "$ADMIN_EMAIL" || -z "$ADMIN_PASSWORD" ]]; then
        echo "Set ROSEBOARD_ADMIN_TOKEN, or set both ROSEBOARD_ADMIN_EMAIL and ROSEBOARD_ADMIN_PASSWORD." >&2
        exit 2
    fi
    echo "[auth] logging in as $ADMIN_EMAIL"
    local login_payload
    login_payload="$(python3 - "$ADMIN_EMAIL" "$ADMIN_PASSWORD" <<'PY'
import json
import sys
print(json.dumps({'username': sys.argv[1], 'password': sys.argv[2]}))
PY
)"
    request_noauth POST /api/login "$login_payload"
    ADMIN_TOKEN="$(python3 - "$HTTP_BODY" <<'PY'
import json
import sys
print(json.loads(sys.argv[1])['token'])
PY
)"
}

require_tenant() {
    if [[ -z "$TENANT_ID" ]]; then
        echo "ROSEBOARD_TENANT_ID is required." >&2
        exit 2
    fi
}

step() {
    echo
echo "== $1 =="
}
