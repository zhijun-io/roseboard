#!/usr/bin/env bash
set -Eeuo pipefail

BASE_URL="${ROSEBOARD_BASE_URL:-http://localhost:8080}"
MQTT_HOST="${ROSEBOARD_MQTT_HOST:-127.0.0.1}"
MQTT_PORT="${ROSEBOARD_MQTT_PORT:-1883}"
ADMIN_TOKEN="${ROSEBOARD_ADMIN_TOKEN:-}"
ADMIN_EMAIL="${ROSEBOARD_ADMIN_EMAIL:-}"
ADMIN_PASSWORD="${ROSEBOARD_ADMIN_PASSWORD:-}"
TENANT_ID="${ROSEBOARD_TENANT_ID:-}"
DEVICE_NAME="${ROSEBOARD_MQTT_DEVICE_NAME:-roseboard-mqtt-demo-device-$(date +%s)}"
DEVICE_TYPE="${ROSEBOARD_MQTT_DEVICE_TYPE:-mqtt-demo-sensor}"
PROVISION_DEVICE_NAME="${ROSEBOARD_MQTT_PROVISIONED_DEVICE_NAME:-roseboard-mqtt-provisioned-$(date +%s)}"
PROVISION_DEVICE_KEY="${ROSEBOARD_MQTT_PROVISION_DEVICE_KEY:-roseboard-mqtt-provision-key-$(date +%s)}"
PROVISION_DEVICE_SECRET="${ROSEBOARD_MQTT_PROVISION_DEVICE_SECRET:-roseboard-mqtt-provision-secret}"
CLEANUP="${ROSEBOARD_MQTT_CLEANUP:-false}"
TMP_DIR=""
DEVICE_ID=""
DEVICE_TOKEN=""
DEVICE_PROFILE_ID=""
EXTRA_PROFILE_ID=""
FIRMWARE_ID=""
SOFTWARE_ID=""
PROVISIONED_DEVICE_ID=""
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/lifecycle-common.sh"

usage() {
    cat <<'EOF'
Usage:
  ROSEBOARD_ADMIN_TOKEN=... ROSEBOARD_TENANT_ID=... ./scripts/mqtt-device-lifecycle.sh [--cleanup]

Required environment:
  ROSEBOARD_ADMIN_TOKEN       Existing SYS_ADMIN or TENANT_ADMIN JWT.
  ROSEBOARD_TENANT_ID         Tenant UUID used when creating the device.

Alternative authentication:
  ROSEBOARD_ADMIN_EMAIL       Login email, used when ADMIN_TOKEN is omitted.
  ROSEBOARD_ADMIN_PASSWORD    Login password, used when ADMIN_TOKEN is omitted.

Optional environment:
  ROSEBOARD_BASE_URL          Default: http://localhost:8081
  ROSEBOARD_MQTT_HOST         Default: 127.0.0.1
  ROSEBOARD_MQTT_PORT         Default: 1883
  ROSEBOARD_MQTT_CLEANUP      Set true to delete all test resources on exit.

The script validates the plain MQTT device surface:
  CONNECT authentication, PINGREQ, SUBSCRIBE/UNSUBSCRIBE, QoS 0/1/2
  telemetry, attributes, attribute reads/updates, device RPC, server RPC,
  claim, firmware/software chunk downloads, provision-only CONNECT, and
  invalid-topic rejection.
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
require_command nc
require_command python3

TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/roseboard-mqtt-demo.XXXXXX")"


on_exit() {
    local exit_code=$?
    set +e
    if [[ "$CLEANUP" == "true" && -n "$ADMIN_TOKEN" ]]; then
        if [[ -z "$PROVISIONED_DEVICE_ID" && -n "$PROVISION_DEVICE_NAME" ]]; then
            PROVISIONED_DEVICE_ID="$(curl -sS \
                -H "Authorization: Bearer $ADMIN_TOKEN" \
                "$BASE_URL/api/devices?pageSize=100&page=0&textSearch=$PROVISION_DEVICE_NAME" \
                | jq -r --arg name "$PROVISION_DEVICE_NAME" '.data[]? | select(.name == $name) | .id' \
                | head -n 1)"
        fi
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
    [[ -z "$TMP_DIR" ]] || rm -rf "$TMP_DIR"
    exit "$exit_code"
}
trap on_exit EXIT
authenticate_admin
require_tenant



if ! nc -z -w 2 "$MQTT_HOST" "$MQTT_PORT"; then
    echo "MQTT server is not reachable at $MQTT_HOST:$MQTT_PORT." >&2
    echo "Start the backend with ROSEBOARD_MQTT_ENABLED=true and the configured port." >&2
    exit 1
fi


step 'create MQTT device and credentials'
create_payload="$(jq -nc --arg tenantId "$TENANT_ID" --arg name "$DEVICE_NAME" --arg type "$DEVICE_TYPE" \
    '{tenantId:$tenantId,name:$name,type:$type,label:"mqtt-script",additionalInfo:{source:"mqtt-device-lifecycle.sh"}}')"
request POST /api/devices "$create_payload"
DEVICE_ID="$(jq -r '.id' <<<"$HTTP_BODY")"
DEVICE_PROFILE_ID="$(jq -r '.deviceProfileId' <<<"$HTTP_BODY")"
request POST "/api/devices/$DEVICE_ID/credentials"
DEVICE_TOKEN="$(jq -r '.credentialsValue' <<<"$HTTP_BODY")"
[[ -n "$DEVICE_TOKEN" && "$DEVICE_TOKEN" != null ]]

step 'configure shared attributes and provisioning profile'
attributes_payload='{"mode":"PER_ITEM","items":[{"scope":"SHARED","key":"mqttDesired","value":"eco"}]}'
request POST "/api/devices/$DEVICE_ID/attributes" "$attributes_payload"
profile_name="roseboard-mqtt-profile-$(date +%s)"
profile_payload="$(jq -nc --arg tenantId "$TENANT_ID" --arg name "$profile_name" \
    --arg key "$PROVISION_DEVICE_KEY" --arg secret "$PROVISION_DEVICE_SECRET" \
    '{tenantId:$tenantId,name:$name,description:"MQTT script profile",isDefault:false,profileData:{provisionDeviceKey:$key,provisionConfiguration:{type:"ALLOW_CREATE_NEW_DEVICES",provisionDeviceSecret:$secret}}}')"
request POST /api/device-profiles "$profile_payload"
EXTRA_PROFILE_ID="$(jq -r '.id' <<<"$HTTP_BODY")"

step 'create and assign firmware/software packages'
printf '%s' 'firmware-from-mqtt-script' > "$TMP_DIR/firmware.bin"
printf '%s' '0123456789abcdef' > "$TMP_DIR/software.bin"
firmware_payload="$(jq -nc --arg profile "$DEVICE_PROFILE_ID" \
    '{deviceProfileId:$profile,type:"firmware",title:"mqtt-script-firmware",version:"1.0.0",usesUrl:false,fileName:"firmware.bin",contentType:"application/octet-stream"}')"
request POST /api/ota-packages "$firmware_payload"
FIRMWARE_ID="$(jq -r '.id' <<<"$HTTP_BODY")"
request_multipart "/api/ota-packages/$FIRMWARE_ID/content" "$TMP_DIR/firmware.bin"
software_payload="$(jq -nc --arg profile "$DEVICE_PROFILE_ID" \
    '{deviceProfileId:$profile,type:"software",title:"mqtt-script-software",version:"2.0.0",usesUrl:false,fileName:"software.bin",contentType:"application/octet-stream"}')"
request POST /api/ota-packages "$software_payload"
SOFTWARE_ID="$(jq -r '.id' <<<"$HTTP_BODY")"
request_multipart "/api/ota-packages/$SOFTWARE_ID/content" "$TMP_DIR/software.bin"
update_payload="$(jq -nc --arg id "$DEVICE_ID" --arg name "$DEVICE_NAME" --arg type "$DEVICE_TYPE" \
    --arg firmware "$FIRMWARE_ID" --arg software "$SOFTWARE_ID" \
    '{id:$id,name:$name,type:$type,firmwareId:$firmware,softwareId:$software}')"
request PUT "/api/devices/$DEVICE_ID" "$update_payload"

cat > "$TMP_DIR/mqtt_probe.py" <<'PY'
import json
import os
import socket
import struct
import threading
import time
import urllib.error
import urllib.request

BASE_URL = os.environ["ROSEBOARD_BASE_URL"]
MQTT_HOST = os.environ["ROSEBOARD_MQTT_HOST"]
MQTT_PORT = int(os.environ["ROSEBOARD_MQTT_PORT"])
ADMIN_TOKEN = os.environ["ROSEBOARD_ADMIN_TOKEN"]
TENANT_ID = os.environ["ROSEBOARD_TENANT_ID"]
DEVICE_ID = os.environ["ROSEBOARD_MQTT_DEVICE_ID"]
DEVICE_TOKEN = os.environ["ROSEBOARD_MQTT_DEVICE_TOKEN"]
PROVISION_KEY = os.environ["ROSEBOARD_MQTT_PROVISION_KEY"]
PROVISION_SECRET = os.environ["ROSEBOARD_MQTT_PROVISION_SECRET"]
PROVISION_NAME = os.environ["ROSEBOARD_MQTT_PROVISION_NAME"]
DEVICE_TOPIC = "devices/me"
TELEMETRY_TOPIC = DEVICE_TOPIC + "/telemetry"
ATTRIBUTES_TOPIC = DEVICE_TOPIC + "/attributes"
ATTRIBUTES_REQUEST_PREFIX = ATTRIBUTES_TOPIC + "/request/"
ATTRIBUTES_RESPONSE_PREFIX = ATTRIBUTES_TOPIC + "/response/"
ATTRIBUTES_RESPONSE_FILTER = ATTRIBUTES_RESPONSE_PREFIX + "+"
RPC_REQUEST_PREFIX = DEVICE_TOPIC + "/rpc/request/"
RPC_REQUEST_FILTER = RPC_REQUEST_PREFIX + "+"
RPC_RESPONSE_PREFIX = DEVICE_TOPIC + "/rpc/response/"
RPC_RESPONSE_FILTER = RPC_RESPONSE_PREFIX + "+"
CLAIM_TOPIC = DEVICE_TOPIC + "/claim"
FIRMWARE_REQUEST_PREFIX = DEVICE_TOPIC + "/firmware/request/"
FIRMWARE_RESPONSE_FILTER = DEVICE_TOPIC + "/firmware/response/+/chunk/+"
SOFTWARE_REQUEST_PREFIX = DEVICE_TOPIC + "/software/request/"
SOFTWARE_RESPONSE_FILTER = DEVICE_TOPIC + "/software/response/+/chunk/+"


def remaining_length(value):
    result = bytearray()
    while True:
        digit = value % 128
        value //= 128
        if value:
            digit |= 128
        result.append(digit)
        if not value:
            return bytes(result)


def utf8(value):
    raw = value.encode()
    return struct.pack("!H", len(raw)) + raw


class MqttClient:
    def __init__(self, client_id):
        self.client_id = client_id
        self.sock = None
        self.next_packet_id = 1
        self.queued = []

    def connect(self, username=None, password=None):
        self.sock = socket.create_connection((MQTT_HOST, MQTT_PORT), timeout=10)
        flags = 0x02
        payload = utf8(self.client_id)
        if username is not None:
            flags |= 0x80
            payload += utf8(username)
        if password is not None:
            flags |= 0x40
            payload += utf8(password)
        body = b"\x00\x04MQTT" + bytes([4, flags]) + struct.pack("!H", 60) + payload
        self.send(0x10, body)
        packet_type, _, body = self.read_packet()
        if packet_type != 2 or len(body) < 2 or body[1] != 0:
            code = body[1] if len(body) > 1 else -1
            self.close()
            raise RuntimeError(f"MQTT CONNECT rejected with CONNACK code {code}")

    def send(self, first_byte, body=b""):
        self.sock.sendall(bytes([first_byte]) + remaining_length(len(body)) + body)

    def read_exact(self, size):
        chunks = bytearray()
        while len(chunks) < size:
            chunk = self.sock.recv(size - len(chunks))
            if not chunk:
                raise RuntimeError("MQTT connection closed")
            chunks.extend(chunk)
        return bytes(chunks)

    def read_packet(self, timeout=10):
        self.sock.settimeout(timeout)
        first = self.read_exact(1)[0]
        multiplier = 1
        length = 0
        while True:
            digit = self.read_exact(1)[0]
            length += (digit & 127) * multiplier
            if digit & 128 == 0:
                break
            multiplier *= 128
            if multiplier > 128 * 128 * 128:
                raise RuntimeError("Invalid MQTT remaining length")
        return first >> 4, first & 15, self.read_exact(length)

    def packet_id(self):
        packet_id = self.next_packet_id
        self.next_packet_id = 1 if packet_id == 65535 else packet_id + 1
        return packet_id

    def publish(self, topic, payload, qos=1):
        if isinstance(payload, str):
            payload = payload.encode()
        elif not isinstance(payload, bytes):
            payload = json.dumps(payload, separators=(",", ":")).encode()
        packet_id = self.packet_id() if qos else None
        body = utf8(topic)
        if packet_id is not None:
            body += struct.pack("!H", packet_id)
        body += payload
        self.send(0x30 | (qos << 1), body)
        if qos == 0:
            return 0
        while True:
            packet_type, _, packet_body = self.read_packet()
            if packet_type == 4 and len(packet_body) >= 2 and struct.unpack("!H", packet_body[:2])[0] == packet_id:
                return 0
            if packet_type == 5 and len(packet_body) >= 2 and struct.unpack("!H", packet_body[:2])[0] == packet_id:
                self.send(0x62, struct.pack("!H", packet_id))
            if packet_type == 7 and len(packet_body) >= 2 and struct.unpack("!H", packet_body[:2])[0] == packet_id:
                return 0
            self.handle_control(packet_type, packet_body)

    def subscribe(self, topic_filters, qos=1):
        packet_id = self.packet_id()
        body = struct.pack("!H", packet_id)
        for topic_filter in topic_filters:
            body += utf8(topic_filter) + bytes([qos])
        self.send(0x82, body)
        while True:
            packet_type, _, packet_body = self.read_packet()
            if packet_type == 9 and len(packet_body) >= 2 and struct.unpack("!H", packet_body[:2])[0] == packet_id:
                grants = packet_body[2:]
                if not grants or any(grant >= 128 for grant in grants):
                    raise AssertionError(f"SUBSCRIBE rejected: {list(grants)}")
                return
            self.handle_control(packet_type, packet_body)

    def unsubscribe(self, topic_filters):
        packet_id = self.packet_id()
        body = struct.pack("!H", packet_id) + b"".join(utf8(topic) for topic in topic_filters)
        self.send(0xA2, body)
        while True:
            packet_type, _, packet_body = self.read_packet()
            if packet_type == 11 and len(packet_body) >= 2 and struct.unpack("!H", packet_body[:2])[0] == packet_id:
                return
            self.handle_control(packet_type, packet_body)

    def ping(self):
        self.send(0xC0)
        while True:
            packet_type, _, packet_body = self.read_packet()
            if packet_type == 13:
                return
            self.handle_control(packet_type, packet_body)

    def wait_publish(self, topic, timeout=10):
        deadline = time.monotonic() + timeout
        while True:
            for index, message in enumerate(self.queued):
                if message[0] == topic:
                    return self.queued.pop(index)
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError(f"Timed out waiting for MQTT topic {topic}")
            packet_type, flags, packet_body = self.read_packet(remaining)
            if packet_type == 3:
                message = self.parse_publish(flags, packet_body)
                if message[0] == topic:
                    return message
                self.queued.append(message)
            else:
                self.handle_control(packet_type, packet_body)

    def parse_publish(self, flags, body):
        topic_length = struct.unpack("!H", body[:2])[0]
        topic = body[2:2 + topic_length].decode()
        offset = 2 + topic_length
        qos = (flags >> 1) & 3
        packet_id = None
        if qos:
            packet_id = struct.unpack("!H", body[offset:offset + 2])[0]
            offset += 2
            if qos == 1:
                self.send(0x40, struct.pack("!H", packet_id))
            elif qos == 2:
                self.send(0x50, struct.pack("!H", packet_id))
        elif len(body) >= offset + 2:
            # Roseboard currently emits a two-byte message id on QoS 0 publishes.
            offset += 2
        return topic, body[offset:]

    def handle_control(self, packet_type, body):
        if packet_type == 6 and len(body) >= 2:
            self.send(0x70, body[:2])
        elif packet_type == 12:
            self.send(0xD0)
        elif packet_type == 3:
            self.queued.append(self.parse_publish(0, body))

    def close(self):
        if self.sock is not None:
            try:
                self.send(0xE0)
            except OSError:
                pass
            self.sock.close()
            self.sock = None


def admin_request(method, path, payload=None):
    data = None if payload is None else json.dumps(payload).encode()
    request = urllib.request.Request(
        BASE_URL + path,
        data=data,
        method=method,
        headers={"Authorization": "Bearer " + ADMIN_TOKEN, "Content-Type": "application/json"},
    )
    with urllib.request.urlopen(request, timeout=20) as response:
        return response.status, response.read()


def admin_json(method, path, payload=None):
    status, body = admin_request(method, path, payload)
    if not 200 <= status < 300:
        raise AssertionError(f"HTTP {status} for {method} {path}: {body!r}")
    return json.loads(body or b"{}")


def wait_until(predicate, timeout=10):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(0.1)
    raise AssertionError("Timed out waiting for MQTT data to reach the API")


def assert_invalid_connect():
    client = MqttClient("mqtt-script-invalid")
    try:
        client.connect(username="invalid-device-token")
    except RuntimeError as error:
        if "rejected" not in str(error):
            raise
    else:
        raise AssertionError("Invalid MQTT credentials were accepted")
    finally:
        client.close()


def main():
    assert_invalid_connect()
    client = MqttClient("mqtt-script-device")
    client.connect(username=DEVICE_TOKEN)
    try:
        client.ping()
        filters = [
            ATTRIBUTES_RESPONSE_FILTER,
            ATTRIBUTES_TOPIC,
            RPC_REQUEST_FILTER,
            RPC_RESPONSE_FILTER,
            FIRMWARE_RESPONSE_FILTER,
            SOFTWARE_RESPONSE_FILTER,
        ]
        client.subscribe(filters, qos=1)

        client.publish(TELEMETRY_TOPIC, {"mqttTemperature": 21}, qos=1)
        client.publish(TELEMETRY_TOPIC, {"mqttTemperatureQos0": 22}, qos=0)
        client.publish(ATTRIBUTES_TOPIC, {"mqttMode": "auto"}, qos=2)
        wait_until(lambda: "mqttTemperature" in json.dumps(admin_json(
            "GET", f"/api/plugins/telemetry/DEVICE/{DEVICE_ID}/values/timeseries?keys=mqttTemperature")))
        wait_until(lambda: "mqttMode" in json.dumps(admin_json(
            "GET", f"/api/devices/{DEVICE_ID}/attributes?scope=CLIENT&keys=mqttMode")))

        client.publish(
            ATTRIBUTES_REQUEST_PREFIX + "7",
            {"clientKeys": ["mqttMode"], "sharedKeys": ["mqttDesired"]},
            qos=1,
        )
        response_topic, response_payload = client.wait_publish(ATTRIBUTES_RESPONSE_PREFIX + "7")
        response = json.loads(response_payload.decode("utf-8"))
        assert response["client"]["mqttMode"] == "auto", response
        assert response["shared"]["mqttDesired"] == "eco", response

        admin_json("POST", f"/api/devices/{DEVICE_ID}/attributes", {
            "mode": "PER_ITEM",
            "items": [{"scope": "SHARED", "key": "mqttUpdated", "value": "yes"}],
        })
        update_topic, update_payload = client.wait_publish(ATTRIBUTES_TOPIC)
        assert json.loads(update_payload)["mqttUpdated"] == "yes", update_payload

        client.publish(
            RPC_REQUEST_PREFIX + "42",
            {"method": "sumOnServer", "params": {"a": 2, "b": 2}},
            qos=1,
        )
        rpc_response_topic, rpc_response_payload = client.wait_publish(RPC_RESPONSE_PREFIX + "42")
        assert json.loads(rpc_response_payload)["result"] == 4, rpc_response_payload

        one_way_result = {}
        def one_way_request():
            try:
                one_way_result["status"], one_way_result["body"] = admin_request(
                    "POST", f"/api/rpc/oneway/{DEVICE_ID}",
                    {"method": "refresh", "params": {"source": "mqtt-script"}},
                )
            except Exception as error:
                one_way_result["error"] = error
        one_way_thread = threading.Thread(target=one_way_request)
        one_way_thread.start()
        one_way_topic, one_way_payload = client.wait_publish(RPC_REQUEST_PREFIX + "1")
        assert json.loads(one_way_payload)["method"] == "refresh", one_way_payload
        one_way_thread.join(10)
        assert one_way_result.get("status") == 200, one_way_result

        two_way_result = {}
        def two_way_request():
            try:
                two_way_result["status"], two_way_result["body"] = admin_request(
                    "POST", f"/api/rpc/twoway/{DEVICE_ID}",
                    {"method": "setGpio", "params": {"pin": 7}, "timeout": 10000},
                )
            except Exception as error:
                two_way_result["error"] = error
        two_way_thread = threading.Thread(target=two_way_request)
        two_way_thread.start()
        two_way_topic, two_way_payload = client.wait_publish(RPC_REQUEST_PREFIX + "2")
        request_id = json.loads(two_way_payload)["id"]
        assert json.loads(two_way_payload)["method"] == "setGpio", two_way_payload
        client.publish(f"{RPC_RESPONSE_PREFIX}{request_id}", {"status": "ok"}, qos=1)
        two_way_thread.join(10)
        assert two_way_result.get("status") == 200, two_way_result
        assert json.loads(two_way_result["body"])["status"] == "ok", two_way_result

        status, persistent_body = admin_request("POST", f"/api/rpc/twoway/{DEVICE_ID}", {
            "method": "reboot", "params": {"delay": 1}, "persistent": True, "timeout": 10000,
        })
        assert status == 200
        persistent_id = persistent_body.decode().strip('"')
        client.unsubscribe([RPC_REQUEST_FILTER, RPC_RESPONSE_FILTER, ATTRIBUTES_RESPONSE_FILTER, ATTRIBUTES_TOPIC,
                            FIRMWARE_RESPONSE_FILTER, SOFTWARE_RESPONSE_FILTER])
        client.subscribe([RPC_REQUEST_FILTER], qos=1)
        persistent_topic, persistent_payload = client.wait_publish(RPC_REQUEST_PREFIX + "3")
        persistent_request_id = json.loads(persistent_payload)["id"]
        client.publish(f"{RPC_RESPONSE_PREFIX}{persistent_request_id}", {"done": True}, qos=1)
        persistent_record = admin_json("GET", f"/api/rpc/persistent/{persistent_id}")
        assert persistent_record["id"] == persistent_id, persistent_record
        admin_request("DELETE", f"/api/rpc/persistent/{persistent_id}")

        client.subscribe([FIRMWARE_RESPONSE_FILTER, SOFTWARE_RESPONSE_FILTER, ATTRIBUTES_TOPIC], qos=1)
        client.publish(CLAIM_TOPIC, {"secretKey": "mqtt-claim-secret", "durationMs": 60000}, qos=1)
        wait_until(lambda: "mqtt-claim-secret" in json.dumps(admin_json(
            "GET", f"/api/devices/{DEVICE_ID}/attributes/claimingData?scope=SERVER")))

        client.publish(f"{FIRMWARE_REQUEST_PREFIX}1/chunk/0",
                       {"title": "mqtt-script-firmware", "version": "1.0.0", "size": 0}, qos=1)
        _, firmware_payload = client.wait_publish(f"{DEVICE_TOPIC}/firmware/response/1/chunk/0")
        assert firmware_payload == b"firmware-from-mqtt-script", firmware_payload
        client.publish(f"{SOFTWARE_REQUEST_PREFIX}2/chunk/1",
                       {"title": "mqtt-script-software", "version": "2.0.0", "size": 8}, qos=1)
        _, software_payload = client.wait_publish(f"{DEVICE_TOPIC}/software/response/2/chunk/1")
        assert software_payload == b"89abcdef", software_payload

        client.unsubscribe([ATTRIBUTES_TOPIC, FIRMWARE_RESPONSE_FILTER, SOFTWARE_RESPONSE_FILTER])
        client.publish(DEVICE_TOPIC + "/unknown", {"bad": True}, qos=1)
        try:
            client.ping()
        except (RuntimeError, OSError):
            pass
        else:
            raise AssertionError("Unknown MQTT topic did not close the connection")
    finally:
        client.close()

    provision = MqttClient("provision")
    provision.connect(username="provision")
    try:
        provision.publish(TELEMETRY_TOPIC, {"invalid": True}, qos=1)
        try:
            provision.ping()
        except (RuntimeError, OSError):
            pass
        else:
            raise AssertionError("Provision-only session accepted a device topic")
    finally:
        try:
            provision.close()
        except Exception:
            pass

    provision = MqttClient("provision")
    provision.connect(username="provision")
    try:
        provision.subscribe(["provision/response"], qos=1)
        provision.publish("provision/request", {
            "deviceName": PROVISION_NAME,
            "provisionDeviceKey": PROVISION_KEY,
            "provisionDeviceSecret": PROVISION_SECRET,
        }, qos=1)
        _, payload = provision.wait_publish("provision/response")
        response = json.loads(payload)
        assert response["status"] == "SUCCESS", response
        assert response["credentialsType"] == "ACCESS_TOKEN", response
    finally:
        provision.close()

    print("All MQTT request checks passed")


if __name__ == "__main__":
    main()
PY

step 'run MQTT protocol probe'
ROSEBOARD_BASE_URL="$BASE_URL" \
ROSEBOARD_MQTT_HOST="$MQTT_HOST" \
ROSEBOARD_MQTT_PORT="$MQTT_PORT" \
ROSEBOARD_ADMIN_TOKEN="$ADMIN_TOKEN" \
ROSEBOARD_TENANT_ID="$TENANT_ID" \
ROSEBOARD_MQTT_DEVICE_ID="$DEVICE_ID" \
ROSEBOARD_MQTT_DEVICE_TOKEN="$DEVICE_TOKEN" \
ROSEBOARD_MQTT_PROVISION_KEY="$PROVISION_DEVICE_KEY" \
ROSEBOARD_MQTT_PROVISION_SECRET="$PROVISION_DEVICE_SECRET" \
ROSEBOARD_MQTT_PROVISION_NAME="$PROVISION_DEVICE_NAME" \
python3 "$TMP_DIR/mqtt_probe.py"

PROVISIONED_DEVICE_ID="$(curl -sS \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    "$BASE_URL/api/devices?pageSize=100&page=0&textSearch=$PROVISION_DEVICE_NAME" \
    | jq -r --arg name "$PROVISION_DEVICE_NAME" '.data[]? | select(.name == $name) | .id' \
    | head -n 1)"
[[ -n "$PROVISIONED_DEVICE_ID" && "$PROVISIONED_DEVICE_ID" != null ]]

printf '\nMQTT lifecycle validation completed for device %s.\n' "$DEVICE_ID"
if [[ "$CLEANUP" == "true" ]]; then
    echo 'All MQTT test resources will be deleted by the exit cleanup handler.'
else
    echo 'MQTT test resources retained. Re-run with --cleanup to delete them.'
fi
