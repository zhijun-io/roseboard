#!/usr/bin/env bash
set -Eeuo pipefail

BASE_URL="${ROSEBOARD_BASE_URL:-http://localhost:8080}"
ADMIN_TOKEN="${ROSEBOARD_ADMIN_TOKEN:-}"
ADMIN_EMAIL="${ROSEBOARD_ADMIN_EMAIL:-}"
ADMIN_PASSWORD="${ROSEBOARD_ADMIN_PASSWORD:-}"
TENANT_ID="${ROSEBOARD_TENANT_ID:-}"
CUSTOMER_ID="${ROSEBOARD_CUSTOMER_ID:-00000000-0000-0000-0000-000000000102}"
DEVICE_NAME="${ROSEBOARD_DEMO_DEVICE_NAME:-roseboard-demo-device-$(date +%s)}"
DEVICE_TYPE="${ROSEBOARD_DEMO_DEVICE_TYPE:-demo-sensor}"
CLEANUP="${ROSEBOARD_DEMO_CLEANUP:-false}"
RPC_TIMEOUT_MS="${ROSEBOARD_DEMO_RPC_TIMEOUT_MS:-20000}"
PROVISION_DEVICE_KEY="${ROSEBOARD_DEMO_PROVISION_DEVICE_KEY:-roseboard-script-provision-key-$(date +%s)}"
PROVISION_DEVICE_SECRET="${ROSEBOARD_DEMO_PROVISION_DEVICE_SECRET:-roseboard-script-provision-secret}"
TMP_DIR=""
DEVICE_ID=""
DEVICE_TOKEN=""
DEVICE_PROFILE_ID=""
EXTRA_PROFILE_ID=""
FIRMWARE_ID=""
SOFTWARE_ID=""
RPC_ID=""
PROVISIONED_DEVICE_ID=""
PROVISIONED_DEVICE_TOKEN=""
PROVISIONED_DEVICE_NAME=""
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/lifecycle-common.sh"

usage() {
    cat <<'EOF'
Usage:
  ROSEBOARD_ADMIN_TOKEN=... ROSEBOARD_TENANT_ID=... ./scripts/device-lifecycle.sh [--cleanup]

Required environment:
  ROSEBOARD_ADMIN_TOKEN       Existing SYS_ADMIN or TENANT_ADMIN JWT.
  ROSEBOARD_TENANT_ID         Tenant UUID used when creating the device.

Alternative authentication:
  ROSEBOARD_ADMIN_EMAIL       Login email, used when ADMIN_TOKEN is omitted.
  ROSEBOARD_ADMIN_PASSWORD    Login password, used when ADMIN_TOKEN is omitted.

Optional environment:
  ROSEBOARD_BASE_URL          Default: http://localhost:8080
  ROSEBOARD_CUSTOMER_ID       Customer UUID used by assign/unassign tests.
  ROSEBOARD_DEMO_DEVICE_NAME  Default: roseboard-demo-device-<unix-time>
  ROSEBOARD_DEMO_DEVICE_TYPE  Default: demo-sensor
  ROSEBOARD_DEMO_CLEANUP      Set true to delete all test resources on exit.
  ROSEBOARD_DEMO_RPC_TIMEOUT_MS Default: 20000
  ROSEBOARD_DEMO_PROVISION_DEVICE_KEY
  ROSEBOARD_DEMO_PROVISION_DEVICE_SECRET

The script validates the device HTTP surface:
  device CRUD, customer assignment, credentials, profiles, attributes,
  telemetry, connectivity, RPC, HTTP transport headers, claim/provision,
  and firmware/software OTA package upload/download/delete.
EOF
}

while (($# > 0)); do
    case "$1" in
        --cleanup)
            CLEANUP=true
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "Unknown argument: $1" >&2
            usage >&2
            exit 2
            ;;
    esac
done


require_command curl
require_command python3
require_command cmp

TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/roseboard-device-demo.XXXXXX")"


on_exit() {
    local exit_code=$?
    set +e
    if [[ "$CLEANUP" == "true" && -n "$ADMIN_TOKEN" ]]; then
        for resource_id in "$PROVISIONED_DEVICE_ID" "$DEVICE_ID"; do
            if [[ -n "$resource_id" ]]; then
                echo "[cleanup] deleting device $resource_id"
                cleanup_delete "device $resource_id" "/api/devices/$resource_id"
            fi
        done
        for package_id in "$FIRMWARE_ID" "$SOFTWARE_ID"; do
            if [[ -n "$package_id" ]]; then
                echo "[cleanup] deleting OTA package $package_id"
                cleanup_delete "OTA package $package_id" "/api/ota-packages/$package_id"
            fi
        done
        if [[ -n "$EXTRA_PROFILE_ID" ]]; then
            echo "[cleanup] deleting device profile $EXTRA_PROFILE_ID"
            cleanup_delete "device profile $EXTRA_PROFILE_ID" "/api/device-profiles/$EXTRA_PROFILE_ID"
        fi
    fi
    if [[ -n "$TMP_DIR" ]]; then
        rm -rf "$TMP_DIR"
    fi
    exit "$exit_code"
}
trap on_exit EXIT

json_get() {
    local path="$1"
    local json="$2"
    python3 -c '
import json
import sys

path = sys.argv[1].split(".")
value = json.loads(sys.argv[2])
for part in path:
    if isinstance(value, dict):
        value = value[part]
    elif isinstance(value, list):
        value = value[int(part)]
    else:
        raise KeyError(part)
if value is None:
    raise SystemExit(1)
if isinstance(value, (dict, list)):
    print(json.dumps(value, separators=(",", ":")))
else:
    print(value)
' "$path" "$json"
}

json_scalar() {
    local json="$1"
    python3 - "$json" <<'PY'
import json
import sys
value = json.loads(sys.argv[1])
if isinstance(value, (dict, list)):
    raise SystemExit(1)
print(value)
PY
}

json_has_keys() {
    local json="$1"
    shift
    python3 - "$json" "$@" <<'PY'
import json
import sys

value = json.loads(sys.argv[1])
if not isinstance(value, dict):
    raise SystemExit(1)
missing = [key for key in sys.argv[2:] if key not in value]
raise SystemExit(1 if missing else 0)
PY
}

json_lacks_key() {
    local key="$1"
    local json="$2"
    python3 - "$key" "$json" <<'PY'
import json
import sys
value = json.loads(sys.argv[2])
raise SystemExit(0 if isinstance(value, dict) and sys.argv[1] not in value else 1)
PY
}

json_list_lacks_value() {
    local value="$1"
    local json="$2"
    python3 - "$value" "$json" <<'PY'
import json
import sys
items = json.loads(sys.argv[2])
raise SystemExit(0 if isinstance(items, list) and sys.argv[1] not in items else 1)
PY
}

json_page_contains_id() {
    local id="$1"
    local json="$2"
    python3 - "$id" "$json" <<'PY'
import json
import sys
body = json.loads(sys.argv[2])
items = body.get("data", []) if isinstance(body, dict) else []
raise SystemExit(0 if any(item.get("id") == sys.argv[1] for item in items) else 1)
PY
}

json_list_contains_value() {
    local value="$1"
    local json="$2"
    python3 - "$value" "$json" <<'PY'
import json
import sys
items = json.loads(sys.argv[2])
raise SystemExit(0 if isinstance(items, list) and sys.argv[1] in items else 1)
PY
}

json_contains() {
    local json="$1"
    local needle="$2"
    python3 - "$json" "$needle" <<'PY'
import sys
raise SystemExit(0 if sys.argv[2] in sys.argv[1] else 1)
PY
}

json_find_default_profile_id() {
    local json="$1"
    python3 - "$json" <<'PY'
import json
import sys
body = json.loads(sys.argv[1])
items = body.get("data", []) if isinstance(body, dict) else []
for item in items:
    if item.get("isDefault") is True:
        print(item["id"])
        break
else:
    print(items[0]["id"])
PY
}

json_find_page_id_by_name() {
    local json="$1"
    local name="$2"
    python3 - "$json" "$name" <<'PY'
import json
import sys
body = json.loads(sys.argv[1])
name = sys.argv[2]
items = body.get("data", []) if isinstance(body, dict) else []
for item in items:
    if item.get("name") == name:
        print(item["id"])
        break
else:
    raise SystemExit(1)
PY
}

now_ms() {
    python3 - <<'PY'
import time
print(int(time.time() * 1000))
PY
}

json_object() {
    python3 - "$@" <<'PY'
import json
import sys

mode = sys.argv[1]
if mode == "device":
    _, _, tenant_id, name, device_type = sys.argv
    print(json.dumps({
        "tenantId": tenant_id,
        "name": name,
        "type": device_type,
        "label": "bash-script",
        "additionalInfo": {"source": "device-lifecycle.sh"},
    }, separators=(",", ":")))
elif mode == "device_update":
    _, _, device_id, name, device_type = sys.argv
    print(json.dumps({
        "id": device_id,
        "name": name,
        "type": device_type,
        "label": "bash-script-updated",
        "additionalInfo": {"source": "device-lifecycle.sh", "updated": True},
    }, separators=(",", ":")))
elif mode == "device_ota_update":
    _, _, device_id, name, device_type, firmware_id, software_id = sys.argv
    print(json.dumps({
        "id": device_id,
        "name": name,
        "type": device_type,
        "label": "bash-script-updated",
        "firmwareId": firmware_id,
        "softwareId": software_id,
    }, separators=(",", ":")))
elif mode == "telemetry":
    print(json.dumps({"temperature": 26.5, "humidity": 42}, separators=(",", ":")))
elif mode == "header_telemetry":
    print(json.dumps({"headerTemperature": 27.5}, separators=(",", ":")))
elif mode == "attributes":
    print(json.dumps({"mode": "auto", "firmwareVersion": "demo-1.0.0"}, separators=(",", ":")))
elif mode == "header_attributes":
    print(json.dumps({"headerMode": "enabled"}, separators=(",", ":")))
elif mode == "admin_attributes":
    print(json.dumps({
        "mode": "PER_ITEM",
        "items": [
            {"scope": "SHARED", "key": "targetMode", "value": "eco"},
            {"scope": "SERVER", "key": "serverEnabled", "value": True},
        ],
    }, separators=(",", ":")))
elif mode == "admin_attribute_update":
    _, _, key, value = sys.argv
    print(json.dumps({
        "mode": "PER_ITEM",
        "items": [{"scope": "SHARED", "key": key, "value": value}],
    }, separators=(",", ":")))
elif mode == "admin_telemetry":
    print(json.dumps({"adminMetric": 7}, separators=(",", ":")))
elif mode == "rpc":
    _, _, timeout = sys.argv
    print(json.dumps({
        "method": "setMode",
        "params": {"mode": "eco"},
        "persistent": False,
        "timeout": int(timeout),
    }, separators=(",", ":")))
elif mode == "rpc_oneway":
    print(json.dumps({"method": "refresh", "params": {"source": "bash-script"}, "persistent": False}, separators=(",", ":")))
elif mode == "rpc_persistent":
    print(json.dumps({
        "method": "reboot",
        "params": {"delay": 1},
        "persistent": True,
        "timeout": int(sys.argv[2]),
    }, separators=(",", ":")))
elif mode == "profile":
    _, _, tenant_id, name, provision_key, provision_secret = sys.argv
    print(json.dumps({
        "tenantId": tenant_id,
        "name": name,
        "description": "Profile created by device-lifecycle.sh",
        "isDefault": False,
        "profileData": {
            "transportConfiguration": {"deviceTelemetryTopic": "roseboard/script/telemetry"},
            "provisionDeviceKey": provision_key,
            "provisionConfiguration": {
                "type": "ALLOW_CREATE_NEW_DEVICES",
                "provisionDeviceSecret": provision_secret,
            },
        },
    }, separators=(",", ":")))
elif mode == "ota":
    _, _, profile_id, kind, title, version, file_name = sys.argv
    print(json.dumps({
        "deviceProfileId": profile_id,
        "type": kind,
        "title": title,
        "version": version,
        "usesUrl": False,
        "fileName": file_name,
        "contentType": "application/octet-stream",
    }, separators=(",", ":")))
elif mode == "provision":
    _, _, name, provision_key, provision_secret = sys.argv
    print(json.dumps({
        "deviceName": name,
        "provisionDeviceKey": provision_key,
        "provisionDeviceSecret": provision_secret,
    }, separators=(",", ":")))
else:
    raise SystemExit(f"unknown JSON mode: {mode}")
PY
}



request_with_device_headers() {
    local method="$1"
    local path="$2"
    local payload="${3:-}"
    local response

    response="$(curl -sS --fail-with-body -X "$method" \
        -H 'Content-Type: application/json' \
        -H "X-Device-Access-Token: $DEVICE_TOKEN" \
        --data "$payload" \
        -w $'\n%{http_code}' \
        "$BASE_URL$path")" || {
        echo "HTTP request failed: $method $path" >&2
        echo "$response" >&2
        exit 1
    }
    HTTP_STATUS="${response##*$'\n'}"
    HTTP_BODY="${response%$'\n'*}"
    if ((HTTP_STATUS < 200 || HTTP_STATUS >= 300)); then
        echo "Unexpected HTTP $HTTP_STATUS: $method $path" >&2
        echo "$HTTP_BODY" >&2
        exit 1
    fi
}


download_admin() {
    local path="$1"
    local output="$2"
    local status
    status="$(curl -sS --fail-with-body -o "$output" -w '%{http_code}' \
        -H "Authorization: Bearer $ADMIN_TOKEN" "$BASE_URL$path")" || {
        echo "HTTP download failed: GET $path" >&2
        exit 1
    }
    [[ "$status" == 2* ]] || {
        echo "Unexpected HTTP $status: GET $path" >&2
        exit 1
    }
}

download_device() {
    local path="$1"
    local output="$2"
    local status
    status="$(curl -sS --fail-with-body -o "$output" -w '%{http_code}' \
        "$BASE_URL$path")" || {
        echo "HTTP device download failed: GET $path" >&2
        exit 1
    }
    [[ "$status" == 2* ]] || {
        echo "Unexpected HTTP $status: GET $path" >&2
        exit 1
    }
}
authenticate_admin
require_tenant


step 'list and inspect default device profile'
request GET '/api/device-profiles?pageSize=100&page=0'
json_has_keys "$HTTP_BODY" data totalElements
DEVICE_PROFILE_ID="$(json_find_default_profile_id "$HTTP_BODY")"
request GET "/api/device-profiles/$DEVICE_PROFILE_ID"
json_contains "$HTTP_BODY" "$DEVICE_PROFILE_ID"

step 'create additional device profile and test default switching'
EXTRA_PROFILE_NAME="roseboard-script-profile-$(date +%s)"
profile_payload="$(json_object profile "$TENANT_ID" "$EXTRA_PROFILE_NAME" "$PROVISION_DEVICE_KEY" "$PROVISION_DEVICE_SECRET")"
request POST /api/device-profiles "$profile_payload"
EXTRA_PROFILE_ID="$(json_get id "$HTTP_BODY")"
request GET "/api/device-profiles/$EXTRA_PROFILE_ID"
request PUT "/api/device-profiles/$EXTRA_PROFILE_ID/default"
request PUT "/api/device-profiles/$DEVICE_PROFILE_ID/default"

step 'create and update device'
create_payload="$(json_object device "$TENANT_ID" "$DEVICE_NAME" "$DEVICE_TYPE")"
request POST /api/devices "$create_payload"
DEVICE_ID="$(json_get id "$HTTP_BODY")"
DEVICE_PROFILE_ID="$(json_get deviceProfileId "$HTTP_BODY")"
echo "deviceId=$DEVICE_ID"
echo "deviceProfileId=$DEVICE_PROFILE_ID"
echo "deviceName=$(json_get name "$HTTP_BODY")"
update_payload="$(json_object device_update "$DEVICE_ID" "$DEVICE_NAME" "$DEVICE_TYPE")"
request PUT "/api/devices/$DEVICE_ID" "$update_payload"
json_contains "$HTTP_BODY" 'bash-script-updated'

step 'read device through all device list variants'
request GET "/api/devices/$DEVICE_ID"
json_contains "$HTTP_BODY" "$DEVICE_ID"
request GET "/api/devices?deviceIds=$DEVICE_ID"
json_contains "$HTTP_BODY" "$DEVICE_ID"
request GET "/api/devices?pageSize=100&page=0&textSearch=$DEVICE_NAME&type=$DEVICE_TYPE&deviceProfileId=$DEVICE_PROFILE_ID"
json_contains "$HTTP_BODY" "$DEVICE_ID"

step 'assign and unassign device customer'
request PUT "/api/devices/$DEVICE_ID/customer" "{\"customerId\":\"$CUSTOMER_ID\"}"
json_contains "$HTTP_BODY" "$CUSTOMER_ID"
request DELETE "/api/devices/$DEVICE_ID/customer"
request GET "/api/devices/$DEVICE_ID"
json_lacks_key customerId "$HTTP_BODY"

step 'generate, inspect, and later revoke credentials'
request POST "/api/devices/$DEVICE_ID/credentials"
DEVICE_TOKEN="$(json_get credentialsValue "$HTTP_BODY")"
[[ -n "$DEVICE_TOKEN" ]]
request GET "/api/devices/$DEVICE_ID/credentials"
json_lacks_key credentialsValue "$HTTP_BODY"

step 'write and read admin attributes'
admin_attributes_payload="$(json_object admin_attributes)"
request POST "/api/devices/$DEVICE_ID/attributes" "$admin_attributes_payload"
request GET "/api/devices/$DEVICE_ID/attributes/targetMode?scope=SHARED"
json_contains "$HTTP_BODY" 'targetMode'
request GET "/api/devices/$DEVICE_ID/attributes?scope=SHARED&keys=targetMode"
json_contains "$HTTP_BODY" 'targetMode'
request GET "/api/devices/$DEVICE_ID/attributes/keys?scope=SHARED"
json_contains "$HTTP_BODY" 'targetMode'
request DELETE "/api/devices/$DEVICE_ID/attributes?scope=SERVER&keys=serverEnabled"
request GET "/api/devices/$DEVICE_ID/attributes/keys?scope=SERVER"
json_list_lacks_value serverEnabled "$HTTP_BODY"

step 'device HTTP transport attributes and telemetry'
attributes_payload="$(json_object attributes)"
request_without_admin POST "/api/http/$DEVICE_TOKEN/attributes" "$attributes_payload"
telemetry_payload="$(json_object telemetry)"
request_without_admin POST "/api/http/$DEVICE_TOKEN/telemetry" "$telemetry_payload"
request_without_admin GET "/api/http/$DEVICE_TOKEN/attributes?clientKeys=mode&sharedKeys=targetMode"
json_contains "$HTTP_BODY" '"mode":"auto"'
json_contains "$HTTP_BODY" '"targetMode":"eco"'
header_attributes_payload="$(json_object header_attributes)"
request_with_device_headers POST /api/http/attributes "$header_attributes_payload"
header_telemetry_payload="$(json_object header_telemetry)"
request_with_device_headers POST /api/http/telemetry "$header_telemetry_payload"

step 'device shared-attribute update subscription'
attribute_update_body="$TMP_DIR/attribute-update.json"
attribute_update_status="$TMP_DIR/attribute-update.status"
curl -sS --fail-with-body \
    -o "$attribute_update_body" \
    -w '%{http_code}' \
    "$BASE_URL/api/http/$DEVICE_TOKEN/attributes/updates?timeout=$RPC_TIMEOUT_MS" \
    > "$attribute_update_status" &
attribute_update_pid=$!
sleep 0.2
async_attribute_payload="$(json_object admin_attribute_update asyncTarget active)"
request POST "/api/devices/$DEVICE_ID/attributes" "$async_attribute_payload"
for _ in {1..100}; do
    if [[ -s "$attribute_update_body" ]]; then
        break
    fi
    if ! kill -0 "$attribute_update_pid" 2>/dev/null; then
        break
    fi
    sleep 0.1
done
wait "$attribute_update_pid" || true
[[ -s "$attribute_update_body" ]] || {
    echo 'Device attribute update subscription returned no body.' >&2
    exit 1
}
attribute_update_code="$(cat "$attribute_update_status")"
[[ "$attribute_update_code" == 2* ]] || {
    echo "Attribute update subscription failed with HTTP $attribute_update_code." >&2
    exit 1
}
json_contains "$(cat "$attribute_update_body")" '"asyncTarget":"active"'

step 'admin telemetry query, history, write, and delete'
request GET "/api/plugins/telemetry/DEVICE/$DEVICE_ID/keys/timeseries"
json_contains "$HTTP_BODY" temperature
request GET "/api/plugins/telemetry/DEVICE/$DEVICE_ID/values/timeseries?keys=temperature,humidity"
json_contains "$HTTP_BODY" temperature
telemetry_end="$(now_ms)"
request GET "/api/plugins/telemetry/DEVICE/$DEVICE_ID/values/timeseries/history?keys=temperature&startTs=0&endTs=$telemetry_end&limit=10&orderBy=DESC"
json_contains "$HTTP_BODY" temperature
admin_telemetry_payload="$(json_object admin_telemetry)"
request POST "/api/plugins/telemetry/DEVICE/$DEVICE_ID/timeseries/CLIENT" "$admin_telemetry_payload"
request GET "/api/plugins/telemetry/DEVICE/$DEVICE_ID/values/timeseries?keys=adminMetric"
json_contains "$HTTP_BODY" adminMetric
request DELETE "/api/plugins/telemetry/DEVICE/$DEVICE_ID/timeseries/delete?keys=adminMetric&startTs=0&endTs=$telemetry_end&rewriteLatestIfDeleted=true"
json_contains "$HTTP_BODY" adminMetric

step 'device connectivity state and publish commands'
request GET "/api/devices/$DEVICE_ID/connectivity"
json_has_keys "$HTTP_BODY" active lastConnectTime lastDisconnectTime lastActivityTime
request GET "/api/devices/$DEVICE_ID/connectivity/commands"
json_contains "$HTTP_BODY" http
if json_has_keys "$HTTP_BODY" mqtt 2>/dev/null; then
    json_contains "$HTTP_BODY" mqtt
fi

step 'device-to-cloud RPC'
request_without_admin POST "/api/http/$DEVICE_TOKEN/rpc" '{"method":"reportState","params":{"state":"ready"}}'
json_contains "$HTTP_BODY" success

step 'one-way server-to-device RPC'
oneway_body="$TMP_DIR/rpc-oneway.json"
oneway_status="$TMP_DIR/rpc-oneway.status"
oneway_payload="$(json_object rpc_oneway)"
curl -sS --fail-with-body \
    -o "$oneway_body" \
    -w '%{http_code}' \
    "$BASE_URL/api/http/$DEVICE_TOKEN/rpc?timeout=$RPC_TIMEOUT_MS" \
    > "$oneway_status" &
oneway_pid=$!
sleep 0.2
request POST "/api/rpc/oneway/$DEVICE_ID" "$oneway_payload"
wait "$oneway_pid" || true
[[ "$(cat "$oneway_status")" == 2* ]]
json_contains "$(cat "$oneway_body")" '"method":"refresh"'

step 'two-way server-to-device RPC'
rpc_poll_body="$TMP_DIR/rpc-poll.json"
rpc_poll_status="$TMP_DIR/rpc-poll.status"
rpc_admin_body="$TMP_DIR/rpc-admin.json"
rpc_admin_status="$TMP_DIR/rpc-admin.status"
curl -sS --fail-with-body \
    -o "$rpc_poll_body" \
    -w '%{http_code}' \
    "$BASE_URL/api/http/$DEVICE_TOKEN/rpc?timeout=$RPC_TIMEOUT_MS" \
    > "$rpc_poll_status" &
rpc_poll_pid=$!
sleep 0.2
rpc_payload="$(json_object rpc "$RPC_TIMEOUT_MS")"
curl -sS --fail-with-body -X POST \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -H 'Content-Type: application/json' \
    --data "$rpc_payload" \
    -o "$rpc_admin_body" \
    -w '%{http_code}' \
    "$BASE_URL/api/rpc/twoway/$DEVICE_ID" \
    > "$rpc_admin_status" &
rpc_admin_pid=$!
for _ in {1..100}; do
    if [[ -s "$rpc_poll_body" ]]; then
        break
    fi
    if ! kill -0 "$rpc_poll_pid" 2>/dev/null; then
        break
    fi
    sleep 0.1
done
[[ -s "$rpc_poll_body" ]] || {
    wait "$rpc_poll_pid" || true
    echo 'Device RPC poll returned no command.' >&2
    exit 1
}
wait "$rpc_poll_pid" || true
[[ "$(cat "$rpc_poll_status")" == 2* ]]
rpc_request_id="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["id"])' "$rpc_poll_body")"
request_without_admin POST "/api/http/$DEVICE_TOKEN/rpc/$rpc_request_id" '{"status":"ok","applied":true}'
wait "$rpc_admin_pid" || true
[[ "$(cat "$rpc_admin_status")" == 2* ]]
json_contains "$(cat "$rpc_admin_body")" '"status":"ok"'

auto_persistent_payload="$(json_object rpc_persistent "$RPC_TIMEOUT_MS")"
request POST "/api/rpc/twoway/$DEVICE_ID" "$auto_persistent_payload"
RPC_ID="${HTTP_BODY//\"/}"
request GET "/api/rpc/persistent/$RPC_ID"
json_contains "$HTTP_BODY" "$RPC_ID"
request GET "/api/rpc/persistent/device/$DEVICE_ID?pageSize=100&page=0"
json_contains "$HTTP_BODY" "$RPC_ID"
persistent_poll_body="$TMP_DIR/rpc-persistent-poll.json"
persistent_poll_status="$TMP_DIR/rpc-persistent-poll.status"
curl -sS --fail-with-body \
    -o "$persistent_poll_body" \
    -w '%{http_code}' \
    "$BASE_URL/api/http/$DEVICE_TOKEN/rpc?timeout=$RPC_TIMEOUT_MS" \
    > "$persistent_poll_status" &
persistent_poll_pid=$!
for _ in {1..100}; do
    if [[ -s "$persistent_poll_body" ]]; then
        break
    fi
    if ! kill -0 "$persistent_poll_pid" 2>/dev/null; then
        break
    fi
    sleep 0.1
done
wait "$persistent_poll_pid" || true
[[ "$(cat "$persistent_poll_status")" == 2* ]]
persistent_request_id="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["id"])' "$persistent_poll_body")"
request_without_admin POST "/api/http/$DEVICE_TOKEN/rpc/$persistent_request_id" '{"done":true}'
request DELETE "/api/rpc/persistent/$RPC_ID"

step 'device claim and provisioning'
claim_payload='{"secretKey":"bash-script-claim-secret","durationMs":60000}'
request_without_admin POST "/api/http/$DEVICE_TOKEN/claim" "$claim_payload"
PROVISIONED_DEVICE_NAME="roseboard-script-provisioned-$(date +%s)"
provision_payload="$(json_object provision "$PROVISIONED_DEVICE_NAME" "$PROVISION_DEVICE_KEY" "$PROVISION_DEVICE_SECRET")"
request_without_admin POST /api/http/provision "$provision_payload"
json_contains "$HTTP_BODY" '"status":"SUCCESS"'
PROVISIONED_DEVICE_TOKEN="$(json_get credentialsValue "$HTTP_BODY")"
request GET "/api/devices?pageSize=100&page=0&textSearch=$PROVISIONED_DEVICE_NAME"
PROVISIONED_DEVICE_ID="$(json_find_page_id_by_name "$HTTP_BODY" "$PROVISIONED_DEVICE_NAME")"
request_without_admin GET "/api/http/$PROVISIONED_DEVICE_TOKEN/attributes"
[[ "$HTTP_STATUS" == 200 ]]

step 'OTA package create, upload, list, download, and device download'
printf '%s' 'firmware-from-bash-script' > "$TMP_DIR/firmware.bin"
printf '%s' '0123456789abcdef' > "$TMP_DIR/software.bin"
firmware_payload="$(json_object ota "$DEVICE_PROFILE_ID" firmware roseboard-script-firmware 1.0.0 firmware.bin)"
request POST /api/ota-packages "$firmware_payload"
FIRMWARE_ID="$(json_get id "$HTTP_BODY")"
request_multipart "/api/ota-packages/$FIRMWARE_ID/content" "$TMP_DIR/firmware.bin"
request GET "/api/ota-packages/$FIRMWARE_ID"
json_contains "$HTTP_BODY" roseboard-script-firmware
request GET "/api/ota-packages/$FIRMWARE_ID"
json_contains "$HTTP_BODY" roseboard-script-firmware
request GET '/api/ota-packages?pageSize=100&page=0'
json_contains "$HTTP_BODY" "$FIRMWARE_ID"
request GET "/api/ota-packages?deviceProfileId=$DEVICE_PROFILE_ID&type=firmware&pageSize=100&page=0"
json_contains "$HTTP_BODY" "$FIRMWARE_ID"
download_admin "/api/ota-packages/$FIRMWARE_ID/content" "$TMP_DIR/firmware-admin.bin"
cmp "$TMP_DIR/firmware.bin" "$TMP_DIR/firmware-admin.bin"
software_payload="$(json_object ota "$DEVICE_PROFILE_ID" software roseboard-script-software 2.0.0 software.bin)"
request POST /api/ota-packages "$software_payload"
SOFTWARE_ID="$(json_get id "$HTTP_BODY")"
request_multipart "/api/ota-packages/$SOFTWARE_ID/content" "$TMP_DIR/software.bin"
request GET "/api/ota-packages?deviceProfileId=$DEVICE_PROFILE_ID&type=software&pageSize=100&page=0"
json_contains "$HTTP_BODY" "$SOFTWARE_ID"
update_ota_payload="$(json_object device_ota_update "$DEVICE_ID" "$DEVICE_NAME" "$DEVICE_TYPE" "$FIRMWARE_ID" "$SOFTWARE_ID")"
request PUT "/api/devices/$DEVICE_ID" "$update_ota_payload"
download_device "/api/http/$DEVICE_TOKEN/firmware?title=roseboard-script-firmware&version=1.0.0" "$TMP_DIR/firmware-device.bin"
cmp "$TMP_DIR/firmware.bin" "$TMP_DIR/firmware-device.bin"
download_device "/api/http/$DEVICE_TOKEN/software?title=roseboard-script-software&version=2.0.0&size=8&chunk=1" "$TMP_DIR/software-device-chunk.bin"
printf '%s' '89abcdef' > "$TMP_DIR/software-expected-chunk.bin"
cmp "$TMP_DIR/software-expected-chunk.bin" "$TMP_DIR/software-device-chunk.bin"

step 'revoke credentials and delete device'
request DELETE "/api/devices/$DEVICE_ID/credentials"
request GET "/api/devices/$DEVICE_ID/credentials"
json_lacks_key credentialsValue "$HTTP_BODY"

printf '\nAll device HTTP interface checks passed for device %s.\n' "$DEVICE_ID"
if [[ "$CLEANUP" == "true" ]]; then
    echo 'All test resources will be deleted by the exit cleanup handler.'
else
    echo 'Test resources retained. Re-run with --cleanup to delete them.'
fi
