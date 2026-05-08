#!/bin/bash
# DDD Login-Based Exchange Test
#
# Tests email exchange using an existing account via LoginControlAdu.
# Creates a fresh client dir, logs in with provided credentials, then
# sends an email and polls for an auto-reply.
#
# Usage:
#   ./existing-client-test.sh <cli-jar> <server-keys-dir> <host> <port> <email> <password> <target-email>

set -e

CLI_JAR="${1:?Usage: $0 <cli-jar> <server-keys-dir> <host> <port> <email> <password> <target-email>}"
SERVER_KEYS="${2:?Server keys directory required}"
HOST="${3:?Server host required}"
PORT="${4:?Server port required}"
EMAIL="${5:?Email required}"
PASSWORD="${6:?Password required}"
TARGET_EMAIL="${7:?Target email required}"

K9_APP_ID="net.discdd.mail"
CLIENT_DIR=$(mktemp -d /tmp/ddd-login-test-XXXXXX)
trap 'rm -rf "$CLIENT_DIR"' EXIT

RECV_DIR="$CLIENT_DIR/receive/$K9_APP_ID"

fail() {
    echo "LOGIN EXCHANGE TEST FAILED: $1" >&2
    exit 1
}


echo "=== Step 1: Initialize client ==="
java -jar "$CLI_JAR" bc initializeStorage "$CLIENT_DIR" \
    --server-keys "$SERVER_KEYS" \
    --server "${HOST}:${PORT}"
echo "Client initialized at $CLIENT_DIR"


echo ""
echo "=== Step 2: Send login ADU ==="
printf '# CONTROL\ntype=login\nemail=%s\npassword=%s\n' "$EMAIL" "$PASSWORD" \
    > "$CLIENT_DIR/login.bin"
java -jar "$CLI_JAR" bc addAdu "$CLIENT_DIR" "$K9_APP_ID" "$CLIENT_DIR/login.bin"


echo ""
echo "=== Step 3: Exchange to upload login request ==="
java -jar "$CLI_JAR" bc exchange "$CLIENT_DIR"


echo ""
echo "=== Step 4: Poll for login-ack ==="
LOGIN_OK=false
for attempt in $(seq 1 5); do
    echo "Exchange attempt $attempt/5..."
    java -jar "$CLI_JAR" bc exchange "$CLIENT_DIR"

    mkdir -p "$RECV_DIR"
    for adu_file in "$RECV_DIR"/*; do
        [ -f "$adu_file" ] || continue
        if grep -q "type=login-ack" "$adu_file" 2>/dev/null; then
            if grep -q "success=true" "$adu_file" 2>/dev/null; then
                echo "Login acknowledged for $EMAIL"
                LOGIN_OK=true
                break 2
            else
                fail "Login rejected: $(cat "$adu_file")"
            fi
        fi
    done
    sleep 2
done

[ "$LOGIN_OK" = true ] || fail "Did not receive login-ack within 5 exchanges"


echo ""
echo "=== Step 5: Queue outbound email to $TARGET_EMAIL ==="
TEST_SUBJECT="DDD Exchange Test $(date +%s)"
cat > "$CLIENT_DIR/test-email.eml" << EMLEOF
From: $EMAIL
To: $TARGET_EMAIL
Subject: $TEST_SUBJECT
Content-Type: text/plain

Automated DDD exchange test. Please reply.
EMLEOF

java -jar "$CLI_JAR" bc addAdu "$CLIENT_DIR" "$K9_APP_ID" "$CLIENT_DIR/test-email.eml"


echo ""
echo "=== Step 6: Exchange to send email ==="
java -jar "$CLI_JAR" bc exchange "$CLIENT_DIR"


echo ""
echo "=== Step 7: Poll for auto-reply from $TARGET_EMAIL ==="

# Snapshot existing ADUs before polling so we only check new ones
EXISTING_ADUS=$(ls "$RECV_DIR" 2>/dev/null | sort)

for attempt in $(seq 1 15); do
    echo "Exchange attempt $attempt/15..."
    sleep 20
    java -jar "$CLI_JAR" bc exchange "$CLIENT_DIR"

    for adu_file in "$RECV_DIR"/*; do
        [ -f "$adu_file" ] || continue
        adu_name=$(basename "$adu_file")

        # Skip ADUs that existed before the email was sent
        echo "$EXISTING_ADUS" | grep -qxF "$adu_name" && continue

        # Skip control ADUs
        head -1 "$adu_file" 2>/dev/null | grep -q "^# CONTROL" && continue

        if grep -q "Subject: Mail delivery failed" "$adu_file" 2>/dev/null; then
            fail "Email bounced: $(cat "$adu_file")"
        fi

        if grep -q "Re: $TEST_SUBJECT" "$adu_file" 2>/dev/null; then
            echo "Reply received (ADU $adu_name):"
            head -5 "$adu_file"
            echo ""
            echo "=== Login exchange test PASSED ==="
            exit 0
        fi
    done
done

fail "No reply to '$TEST_SUBJECT' received within 5 minutes."
