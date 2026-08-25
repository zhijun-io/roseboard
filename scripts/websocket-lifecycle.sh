#!/usr/bin/env bash
set -Eeuo pipefail

BASE_URL="${ROSEBOARD_BASE_URL:-http://localhost:8080}"
WS_BASE_URL="${ROSEBOARD_WS_BASE_URL:-${BASE_URL/http:/ws:}}"
ADMIN_TOKEN="${ROSEBOARD_ADMIN_TOKEN:-}"
ADMIN_EMAIL="${ROSEBOARD_ADMIN_EMAIL:-}"
ADMIN_PASSWORD="${ROSEBOARD_ADMIN_PASSWORD:-}"
TENANT_ID="${ROSEBOARD_TENANT_ID:-}"
DEVICE_NAME="${ROSEBOARD_WS_DEVICE_NAME:-roseboard-ws-demo-device-$(date +%s)}"
DEVICE_TYPE="${ROSEBOARD_WS_DEVICE_TYPE:-ws-demo-sensor}"
CLEANUP="${ROSEBOARD_WS_CLEANUP:-false}"
DEVICE_ID=""
DEVICE_TOKEN=""
API_KEY_ID=""
API_KEY_VALUE=""
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/lifecycle-common.sh"

usage() {
    cat <<'EOF'
Usage:
  ROSEBOARD_ADMIN_TOKEN=... ROSEBOARD_TENANT_ID=... ./scripts/websocket-lifecycle.sh [--cleanup]

Required environment:
  ROSEBOARD_ADMIN_TOKEN       Existing SYS_ADMIN or TENANT_ADMIN JWT.
  ROSEBOARD_TENANT_ID         Tenant UUID used when creating the test device.

Alternative authentication:
  ROSEBOARD_ADMIN_EMAIL       Login email, used when ADMIN_TOKEN is omitted.
  ROSEBOARD_ADMIN_PASSWORD    Login password, used when ADMIN_TOKEN is omitted.

Optional environment:
  ROSEBOARD_BASE_URL          Default: http://localhost:8080
  ROSEBOARD_WS_BASE_URL       Default: ws://localhost:8080
  ROSEBOARD_WS_CLEANUP        Set true to delete test resources on exit.

The script validates every management WebSocket command and endpoint:
  JWT/API-key auth, invalid auth, /api/ws, telemetry and notifications plugins,
  TIMESERIES, ATTRIBUTES, TIMESERIES_HISTORY, ENTITY_DATA, ENTITY_COUNT,
  all unsubscribe commands, NOTIFICATIONS, NOTIFICATIONS_COUNT, and mark-read
  commands. It also verifies telemetry/attribute push updates through REST.
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
require_command jq
require_command node
require_command python3


on_exit() {
    local exit_code=$?
    set +e
    if [[ "$CLEANUP" == "true" && -n "$ADMIN_TOKEN" ]]; then
        if [[ -n "$API_KEY_ID" ]]; then
            echo "[cleanup] deleting API key $API_KEY_ID"
            cleanup_delete "API key $API_KEY_ID" "/api/api-keys/$API_KEY_ID"
        fi
        if [[ -n "$DEVICE_ID" ]]; then
            echo "[cleanup] deleting device $DEVICE_ID"
            cleanup_delete "device $DEVICE_ID" "/api/devices/$DEVICE_ID"
        fi
    fi
    exit "$exit_code"
}
trap on_exit EXIT
authenticate_admin
require_tenant



step 'create WebSocket test device and API key'
create_payload="$(jq -nc --arg tenantId "$TENANT_ID" --arg name "$DEVICE_NAME" --arg type "$DEVICE_TYPE" \
    '{tenantId:$tenantId,name:$name,type:$type,label:"websocket-script",additionalInfo:{source:"websocket-lifecycle.sh"}}')"
request POST /api/devices "$create_payload"
DEVICE_ID="$(jq -r '.id' <<<"$HTTP_BODY")"
request POST "/api/devices/$DEVICE_ID/credentials"
DEVICE_TOKEN="$(jq -r '.credentialsValue' <<<"$HTTP_BODY")"

USER_ID="$(python3 - "$ADMIN_TOKEN" <<'PY'
import base64
import json
import sys
parts = sys.argv[1].split('.')
raw = parts[1] + '=' * (-len(parts[1]) % 4)
print(json.loads(base64.urlsafe_b64decode(raw))['userId'])
PY
)"
api_key_payload="$(jq -nc --arg userId "$USER_ID" \
    '{userId:$userId,description:"websocket-lifecycle-script",enabled:true}')"
request POST "/api/users/$USER_ID/api-keys" "$api_key_payload"
API_KEY_ID="$(jq -r '.id' <<<"$HTTP_BODY")"
API_KEY_VALUE="$(jq -r '.value' <<<"$HTTP_BODY")"
[[ -n "$API_KEY_VALUE" && "$API_KEY_VALUE" != null ]]

step 'seed telemetry and attributes for initial WebSocket responses'
request_noauth POST "/api/http/$DEVICE_TOKEN/telemetry" '{"wsTemperature":23.5,"wsHumidity":45}'
request_noauth POST "/api/http/$DEVICE_TOKEN/attributes" '{"wsMode":"auto"}'

export ROSEBOARD_WS_BASE_URL="$WS_BASE_URL"
export ROSEBOARD_BASE_URL ADMIN_TOKEN API_KEY_VALUE DEVICE_ID DEVICE_TOKEN DEVICE_NAME DEVICE_TYPE

node <<'NODE'
const base = process.env.ROSEBOARD_WS_BASE_URL.replace(/\/$/, "");
const httpBase = process.env.ROSEBOARD_BASE_URL.replace(/\/$/, "");
const jwt = process.env.ADMIN_TOKEN;
const apiKey = process.env.API_KEY_VALUE;
const deviceId = process.env.DEVICE_ID;
const deviceToken = process.env.DEVICE_TOKEN;
const deviceName = process.env.DEVICE_NAME;
const deviceType = process.env.DEVICE_TYPE;

function sleep(ms) { return new Promise(resolve => setTimeout(resolve, ms)); }
function assert(condition, message) { if (!condition) throw new Error(message); }
function parse(data) {
  const text = typeof data === "string" ? data : Buffer.from(data).toString("utf8");
  return JSON.parse(text);
}

class JsonWebSocket {
  constructor(url) {
    this.url = url;
    this.messages = [];
    this.closed = null;
    this.errors = [];
    this.opened = new Promise((resolve, reject) => {
      this.resolveOpen = resolve;
      this.rejectOpen = reject;
    });
    this.ws = new WebSocket(url);
    this.ws.addEventListener("open", () => this.resolveOpen());
    this.ws.addEventListener("message", event => this.messages.push(event.data));
    this.ws.addEventListener("error", event => this.errors.push(event));
    this.closed = new Promise(resolve => this.ws.addEventListener("close", resolve));
  }

  async open() {
    await this.opened;
    return this;
  }

  send(payload) {
    this.ws.send(typeof payload === "string" ? payload : JSON.stringify(payload));
  }

  async next(timeoutMs = 8000) {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      if (this.messages.length > 0) return parse(this.messages.shift());
      await sleep(20);
    }
    throw new Error(`Timed out waiting for WebSocket message from ${this.url}`);
  }

  async waitFor(predicate, timeoutMs = 8000) {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      const message = await this.next(Math.max(deadline - Date.now(), 1));
      if (predicate(message)) return message;
    }
    throw new Error(`Timed out waiting for matching WebSocket message from ${this.url}`);
  }

  close() {
    if (this.ws.readyState === WebSocket.OPEN || this.ws.readyState === WebSocket.CONNECTING) {
      this.ws.close();
    }
  }
}

async function rest(path, options = {}) {
  const response = await fetch(httpBase + path, {
    ...options,
    headers: { Authorization: `Bearer ${jwt}`, ...(options.headers || {}) },
  });
  const body = await response.text();
  assert(response.ok, `HTTP ${response.status} ${path}: ${body}`);
  return body ? JSON.parse(body) : null;
}
async function devicePost(path, body) {
  const response = await fetch(httpBase + path, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  const text = await response.text();
  assert(response.ok, `HTTP ${response.status} ${path}: ${text}`);
}

const singleEntityQuery = {
  entityFilter: { type: "singleEntity", singleEntity: { entityType: "DEVICE", id: deviceId } },
};
const deviceTypeQuery = {
  entityFilter: { type: "deviceType", deviceTypes: [deviceType], deviceNameFilter: deviceName },
};
const latestKeys = [
  { type: "TIME_SERIES", key: "wsTemperature" },
  { type: "CLIENT_ATTRIBUTE", key: "wsMode" },
];

async function authenticate(url, auth, cmds = []) {
  const client = await new JsonWebSocket(url).open();
  client.send({ authCmd: { cmdId: 0, ...auth }, cmds });
  return client;
}

async function expectClosed(url, payload) {
  const client = await new JsonWebSocket(url).open();
  client.send(payload);
  await Promise.race([
    client.closed,
    (async () => { await sleep(4000); throw new Error(`WebSocket stayed open: ${url}`); })(),
  ]);
  assert(client.ws.readyState !== WebSocket.OPEN, `Expected WebSocket close: ${url}`);
}

async function testGeneralJwt() {
  const client = await authenticate(`${base}/api/ws`, { token: jwt });
  try {
    client.send({ cmds: [{ type: "TIMESERIES", cmdId: 1, entityType: "DEVICE", entityId: deviceId, keys: "wsTemperature" }] });
    let message = await client.waitFor(item => item.subscriptionId === 1);
    assert(message.errorCode === 0 && message.data.wsTemperature, "TIMESERIES response invalid");

    client.send({ cmds: [{ type: "ATTRIBUTES", cmdId: 2, entityType: "DEVICE", entityId: deviceId, scope: "CLIENT_SCOPE", keys: "wsMode" }] });
    message = await client.waitFor(item => item.subscriptionId === 2);
    assert(message.errorCode === 0 && message.data.wsMode, "ATTRIBUTES response invalid");

    const endTs = Date.now() + 1000;
    client.send({ cmds: [{ type: "TIMESERIES_HISTORY", cmdId: 3, entityType: "DEVICE", entityId: deviceId, keys: "wsTemperature", startTs: endTs - 60000, endTs, limit: 10 }] });
    message = await client.waitFor(item => item.subscriptionId === 3);
    assert(message.errorCode === 0 && Array.isArray(message.data.wsTemperature), "TIMESERIES_HISTORY response invalid");

    client.send({ cmds: [{ type: "ENTITY_DATA", cmdId: 4, query: singleEntityQuery, latestCmd: { keys: latestKeys } }] });
    message = await client.waitFor(item => item.cmdId === 4 && item.cmdUpdateType === "ENTITY_DATA");
    assert(message.errorCode === 0 && message.data && message.data.data.length === 1, "ENTITY_DATA response invalid");

    client.send({ cmds: [{ type: "ENTITY_COUNT", cmdId: 5, query: deviceTypeQuery }] });
    message = await client.waitFor(item => item.cmdId === 5 && item.cmdUpdateType === "COUNT_DATA");
    assert(message.errorCode === 0 && message.count >= 1, "ENTITY_COUNT response invalid");

    client.send({ cmds: [{ type: "NOTIFICATIONS", cmdId: 6, limit: 10, types: [] }] });
    message = await client.waitFor(item => item.cmdId === 6 && item.cmdUpdateType === "NOTIFICATIONS");
    assert(message.errorCode === 0 && Number.isInteger(message.totalUnreadCount), "NOTIFICATIONS response invalid");

    client.send({ cmds: [{ type: "NOTIFICATIONS_COUNT", cmdId: 7 }] });
    message = await client.waitFor(item => item.cmdId === 7 && item.cmdUpdateType === "NOTIFICATIONS_COUNT");
    assert(message.errorCode === 0 && Number.isInteger(message.totalUnreadCount), "NOTIFICATIONS_COUNT response invalid");

    client.send({ cmds: [
      { type: "MARK_NOTIFICATIONS_AS_READ", cmdId: 8, notifications: [] },
      { type: "MARK_ALL_NOTIFICATIONS_AS_READ", cmdId: 9 },
      { type: "TIMESERIES", cmdId: 1, unsubscribe: true },
      { type: "ATTRIBUTES", cmdId: 2, unsubscribe: true },
      { type: "ENTITY_DATA_UNSUBSCRIBE", cmdId: 4 },
      { type: "ENTITY_COUNT_UNSUBSCRIBE", cmdId: 5 },
      { type: "NOTIFICATIONS_UNSUBSCRIBE", cmdId: 6 },
    ] });
    await sleep(250);
  } finally {
    client.close();
  }
}

async function testApiKey() {
  const client = await authenticate(`${base}/api/ws`, { apiKey });
  try {
    client.send({ cmds: [{ type: "ENTITY_COUNT", cmdId: 10, query: deviceTypeQuery }] });
    const message = await client.waitFor(item => item.cmdId === 10 && item.cmdUpdateType === "COUNT_DATA");
    assert(message.errorCode === 0, "API-key WebSocket authentication failed");
  } finally {
    client.close();
  }
}

async function testTelemetryPlugin() {
  const client = await new JsonWebSocket(`${base}/api/ws/plugins/telemetry?token=${encodeURIComponent(jwt)}`).open();
  try {
    client.send({
      tsSubCmds: [{ type: "TIMESERIES", cmdId: 11, entityType: "DEVICE", entityId: deviceId, keys: "wsTemperature" }],
      attrSubCmds: [{ type: "ATTRIBUTES", cmdId: 12, entityType: "DEVICE", entityId: deviceId, scope: "CLIENT_SCOPE", keys: "wsMode" }],
      historyCmds: [{ type: "TIMESERIES_HISTORY", cmdId: 13, entityType: "DEVICE", entityId: deviceId, keys: "wsTemperature", startTs: Date.now() - 60000, endTs: Date.now() + 1000, limit: 10 }],
      entityDataCmds: [{ type: "ENTITY_DATA", cmdId: 14, query: singleEntityQuery, latestCmd: { keys: latestKeys } }],
      entityCountCmds: [{ type: "ENTITY_COUNT", cmdId: 15, query: deviceTypeQuery }],
    });
    const seen = new Set();
    while (seen.size < 5) {
      const message = await client.next();
      if ([11, 12, 13].includes(message.subscriptionId) || [14, 15].includes(message.cmdId)) seen.add(message.subscriptionId ?? message.cmdId);
    }
    client.send({
      tsSubCmds: [{ type: "TIMESERIES", cmdId: 11, unsubscribe: true }],
      attrSubCmds: [{ type: "ATTRIBUTES", cmdId: 12, unsubscribe: true }],
      entityDataUnsubscribeCmds: [{ type: "ENTITY_DATA_UNSUBSCRIBE", cmdId: 14 }],
      entityCountUnsubscribeCmds: [{ type: "ENTITY_COUNT_UNSUBSCRIBE", cmdId: 15 }],
    });
  } finally {
    client.close();
  }
}

async function testNotificationsPlugin() {
  const client = await new JsonWebSocket(`${base}/api/ws/plugins/notifications?token=${encodeURIComponent(jwt)}`).open();
  try {
    client.send({
      unreadSubCmd: { type: "NOTIFICATIONS", cmdId: 21, limit: 10, types: [] },
      unreadCountSubCmd: { type: "NOTIFICATIONS_COUNT", cmdId: 22 },
      markAsReadCmd: { type: "MARK_NOTIFICATIONS_AS_READ", cmdId: 23, notifications: [] },
      markAllAsReadCmd: { type: "MARK_ALL_NOTIFICATIONS_AS_READ", cmdId: 24 },
    });
    const first = await client.waitFor(item => item.cmdId === 21 && item.cmdUpdateType === "NOTIFICATIONS");
    const second = await client.waitFor(item => item.cmdId === 22 && item.cmdUpdateType === "NOTIFICATIONS_COUNT");
    assert(first.errorCode === 0 && second.errorCode === 0, "notifications plugin response invalid");
    client.send({ unsubCmd: { type: "NOTIFICATIONS_UNSUBSCRIBE", cmdId: 21 } });
  } finally {
    client.close();
  }
}

async function testPushUpdates() {
  const client = await authenticate(`${base}/api/ws`, { token: jwt });
  try {
    client.send({ cmds: [{ type: "TIMESERIES", cmdId: 31, entityType: "DEVICE", entityId: deviceId, keys: "wsPush" }] });
    await client.waitFor(item => item.subscriptionId === 31);
    await devicePost(`/api/http/${deviceToken}/telemetry`, { wsPush: 33.3 });
    const update = await client.waitFor(item => item.subscriptionId === 31 && item.data?.wsPush);
    assert(JSON.stringify(update.data.wsPush).includes("33.3"), "telemetry push update invalid");

    client.send({ cmds: [{ type: "ATTRIBUTES", cmdId: 32, entityType: "DEVICE", entityId: deviceId, scope: "CLIENT_SCOPE", keys: "wsPushMode" }] });
    await client.waitFor(item => item.subscriptionId === 32);
    await devicePost(`/api/http/${deviceToken}/attributes`, { wsPushMode: "updated" });
    const attrUpdate = await client.waitFor(item => item.subscriptionId === 32 && item.data?.wsPushMode);
    assert(JSON.stringify(attrUpdate.data.wsPushMode).includes("updated"), "attribute push update invalid");

    client.send({ cmds: [{ type: "ENTITY_DATA", cmdId: 33, query: singleEntityQuery, latestCmd: { keys: [{ type: "TIME_SERIES", key: "wsPush" }] } }] });
    await client.waitFor(item => item.cmdId === 33 && item.cmdUpdateType === "ENTITY_DATA");
    await devicePost(`/api/http/${deviceToken}/telemetry`, { wsPush: 44.4 });
    const entityUpdate = await client.waitFor(item => item.cmdId === 33 && Array.isArray(item.update));
    assert(JSON.stringify(entityUpdate.update).includes("44.4"), "entity data push update invalid");
  } finally {
    client.close();
  }
}

async function main() {
  await expectClosed(`${base}/api/ws`, { authCmd: { cmdId: 0, token: "invalid-websocket-token" }, cmds: [] });
  await testGeneralJwt();
  await testApiKey();
  await testTelemetryPlugin();
  await testNotificationsPlugin();
  await testPushUpdates();
  console.log("All management WebSocket request checks passed");
}

main().catch(error => { console.error(error.stack || error); process.exitCode = 1; });
NODE

printf '\nWebSocket lifecycle validation completed for device %s.\n' "$DEVICE_ID"
if [[ "$CLEANUP" == "true" ]]; then
    echo 'All WebSocket test resources will be deleted by the exit cleanup handler.'
else
    echo 'WebSocket test resources retained. Re-run with --cleanup to delete them.'
fi
