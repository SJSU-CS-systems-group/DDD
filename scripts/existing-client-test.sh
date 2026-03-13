#!/bin/bash
# DDD Existing Client Exchange Test
#
# Tests email exchange with an already-registered client. Simulates a returning
# mobile client: adds an outbound email, exchanges with the server, then polls
# for an auto-reply from the target address.
#
# Prerequisites:
#   - client-dir must already be registered (run canary-test.sh once to register and save the dir)
#   - canary-test.sh writes 'registered-email.txt' automatically after successful registration
#
# Usage:
#   ./existing-client-test.sh <cli-jar> <client-dir> <host> <port> <target-email>

set -e

CLI_JAR="${1:?Usage: $0 <cli-jar> <client-dir> <host> <port> <target-email>}"
CLIENT_DIR="${2:?Client directory required}"
HOST="${3:?Server host required}"
PORT="${4:?Server port required}"
TARGET_EMAIL="${5:?Target email required}"

K9_APP_ID="net.discdd.mail"
RECV_DIR="$CLIENT_DIR/receive/$K9_APP_ID"

fail() {
    echo "EXISTING CLIENT TEST FAILED: $1" >&2
    exit 1
}

REGISTERED_EMAIL=$(cat "$CLIENT_DIR/registered-email.txt" 2>/dev/null | tr -d '\r')
[ -n "$REGISTERED_EMAIL" ] || fail "registered-email.txt not found in $CLIENT_DIR — run canary-test.sh first to register the persistent client"

echo "Testing as: $REGISTERED_EMAIL  jar: $(basename "$CLI_JAR")"


echo ""
echo "=== Step 1: Queue outbound email to $TARGET_EMAIL ==="
cat > "$CLIENT_DIR/test-email.eml" << EMLEOF
From: $REGISTERED_EMAIL
To: $TARGET_EMAIL
Subject: DDD Exchange Test $(date +%s)
Content-Type: text/plain

Automated DDD exchange test. Please reply.
EMLEOF

java -jar "$CLI_JAR" bc addAdu "$CLIENT_DIR" "$K9_APP_ID" "$CLIENT_DIR/test-email.eml"


echo ""
echo "=== Step 2: Exchange ==="
java -jar "$CLI_JAR" bc exchange "$CLIENT_DIR"


echo ""
echo "=== Step 3: Poll for auto-reply from $TARGET_EMAIL ==="

# Snapshot ADU files that already exist before polling so we only check new ones
mkdir -p "$RECV_DIR"
EXISTING_ADUS=$(ls "$RECV_DIR" 2>/dev/null | sort)

for attempt in $(seq 1 15); do
    echo "Exchange attempt $attempt/15..."
    sleep 20
    java -jar "$CLI_JAR" bc exchange "$CLIENT_DIR"

    for adu_file in "$RECV_DIR"/*; do
        [ -f "$adu_file" ] || continue
        adu_name=$(basename "$adu_file")

        # Skip ADUs that existed before this test run
        echo "$EXISTING_ADUS" | grep -qxF "$adu_name" && continue

        if grep -q "Subject: Mail delivery failed" "$adu_file" 2>/dev/null; then
            fail "Email bounced: $(cat "$adu_file")"
        fi

        # Any new non-control ADU is treated as the reply
        if ! head -1 "$adu_file" 2>/dev/null | grep -q "^# CONTROL"; then
            echo "Reply received (ADU $adu_name):"
            head -5 "$adu_file"
            echo ""
            echo "=== Existing client test PASSED ==="
            exit 0
        fi
    done
done

fail "No reply received within 5 minutes. New ADUs in receive dir: $(ls "$RECV_DIR" 2>/dev/null | grep -vxF "$(echo "$EXISTING_ADUS")" || echo none)"
