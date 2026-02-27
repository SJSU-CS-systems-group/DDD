#!/bin/bash
# DDD Canary Sanity Test
#
# Simulates a new mobile client performing the complete end-to-end flow:
#   1. Initialize client storage
#   2. Register with the server (k9 email service)
#   3. Poll until registration acknowledgement received
#   4. (Optional) Send email to target address, poll for auto-reply
#
# Usage:
#   ./canary-test.sh <cli-jar> <server-keys-dir> <host> <port> [target-email]
#
# Arguments:
#   cli-jar         path to the built cli-*.jar
#   server-keys-dir path to directory containing server public key files
#   host            bundleserver hostname or IP
#   port            bundleserver port (typically 7778)
#   target-email    (optional) address with auto-reply configured;
#                   if omitted the email delivery steps are skipped

set -e

CLI_JAR="${1:?Usage: $0 <cli-jar> <server-keys-dir> <host> <port> [target-email]}"
SERVER_KEYS="${2:?Server keys directory required}"
HOST="${3:?Server host required}"
PORT="${4:?Server port required}"
TARGET_EMAIL="${5:-}"

K9_APP_ID="net.discdd.mail"
CLIENT_DIR=$(mktemp -d /tmp/ddd-sanity-XXXXXX)
trap 'rm -rf "$CLIENT_DIR"' EXIT

fail() {
    echo "SANITY TEST FAILED: $1" >&2
    exit 1
}

# ── Step 1: Initialize a fresh client ────────────────────────────────────────
echo "=== Step 1: Initialize client ==="
java -jar "$CLI_JAR" bc initializeStorage "$CLIENT_DIR" \
    --server-keys "$SERVER_KEYS" \
    --server "${HOST}:${PORT}"
echo "Client initialized at $CLIENT_DIR"

# ── Step 2: Queue registration request ───────────────────────────────────────
# RegisterControlAdu is a Java properties file prefixed with the "# CONTROL" header.
# The server will generate an email: prefix<rand>suffix@<domain>
echo ""
echo "=== Step 2: Queue registration request ==="
printf '# CONTROL\ntype=register\nprefix=test\nsuffix=test\npassword=testpass123\n' \
    > "$CLIENT_DIR/register.bin"
java -jar "$CLI_JAR" bc addAdu "$CLIENT_DIR" "$K9_APP_ID" "$CLIENT_DIR/register.bin"

# ── Step 3: Exchange — upload registration bundle ────────────────────────────
echo ""
echo "=== Step 3: Exchange to upload registration request ==="
java -jar "$CLI_JAR" bc exchange "$CLIENT_DIR"

# ── Step 4: Poll exchanges until register-ack received ───────────────────────
# The server processes the register ADU and queues a RegisterAckControlAdu.
# Because the client downloads before uploading, the ack arrives on the next exchange.
echo ""
echo "=== Step 4: Poll for registration acknowledgement ==="
REGISTERED_EMAIL=""
RECV_DIR="$CLIENT_DIR/receive/$K9_APP_ID"

for attempt in $(seq 1 5); do
    echo "Exchange attempt $attempt/5..."
    java -jar "$CLI_JAR" bc exchange "$CLIENT_DIR"

    if [ -d "$RECV_DIR" ]; then
        for adu_file in "$RECV_DIR"/*; do
            [ -f "$adu_file" ] || continue
            if grep -q "type=register-ack" "$adu_file" 2>/dev/null && \
               grep -q "success=true"     "$adu_file" 2>/dev/null; then
                REGISTERED_EMAIL=$(grep "^email=" "$adu_file" | sed 's/^email=//' | tr -d '\r')
                break 2
            fi
        done
    fi
    sleep 2
done

[ -n "$REGISTERED_EMAIL" ] || fail "Did not receive register-ack within 5 exchanges"
echo "Registered as: $REGISTERED_EMAIL"

# ── Optional email delivery test ─────────────────────────────────────────────
if [ -z "$TARGET_EMAIL" ]; then
    echo ""
    echo "No target email configured (TEST_EMAIL_TARGET not set) — skipping email delivery test"
    echo ""
    echo "=== Sanity test PASSED ==="
    exit 0
fi

# ── Step 5: Send a test email to the target address ──────────────────────────
echo ""
echo "=== Step 5: Send test email to $TARGET_EMAIL ==="
cat > "$CLIENT_DIR/test-email.eml" << EMLEOF
From: $REGISTERED_EMAIL
To: $TARGET_EMAIL
Subject: DDD Sanity Test $(date +%s)
Content-Type: text/plain

This is an automated DDD sanity test email. Please reply.
EMLEOF

java -jar "$CLI_JAR" bc addAdu "$CLIENT_DIR" "$K9_APP_ID" "$CLIENT_DIR/test-email.eml"

# ── Step 6: Exchange — upload email, then poll for bounce or reply ────────────
# The server forwards the email externally. If it fails immediately a bounce
# is queued and arrives on the next exchange download.
echo ""
echo "=== Step 6: Exchange to upload email, then poll for reply ==="
java -jar "$CLI_JAR" bc exchange "$CLIENT_DIR"

for attempt in $(seq 1 15); do
    echo "Exchange attempt $attempt/30 (waiting for reply from $TARGET_EMAIL)..."
    sleep 20
    java -jar "$CLI_JAR" bc exchange "$CLIENT_DIR"

    if [ -d "$RECV_DIR" ]; then
        for adu_file in "$RECV_DIR"/*; do
            [ -f "$adu_file" ] || continue

            # Bounce: server could not deliver the email
            if grep -q "Subject: Mail delivery failed" "$adu_file" 2>/dev/null; then
                fail "Email was bounced by server: $(cat "$adu_file")"
            fi

            # Reply: a non-control ADU that mentions the target address
            if ! head -1 "$adu_file" 2>/dev/null | grep -q "^# CONTROL" && \
               grep -q "$TARGET_EMAIL" "$adu_file" 2>/dev/null; then
                echo "Auto-reply received from $TARGET_EMAIL"
                echo ""
                echo "=== Sanity test PASSED ==="
                exit 0
            fi
        done
    fi
done

fail "Did not receive auto-reply from $TARGET_EMAIL within 5 minutes"
