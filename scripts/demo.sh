#!/usr/bin/env bash
# Helper for the demo. Run from the project root while `docker compose up` is running.
#
#   scripts/demo.sh create [minutes-from-now] [idempotency-key]   book an appointment (default: 3 minutes ahead)
#   scripts/demo.sh replay                                        resend the last request byte-for-byte (a client retry)
#   scripts/demo.sh crash                                         kill -9 the app in the middle of a send, then start it again
#   scripts/demo.sh db                                            appointment, reminder, attempt and delivery rows
#   scripts/demo.sh payloads                                      exactly what was handed to the sender
#   scripts/demo.sh invariants                                    the "never twice" checks (every count must be 0)
#   scripts/demo.sh logs                                          only the interesting log lines
#
# For the crash demo, start the stack with a slow provider so there is time to hit a send:
#   NOTIFICATION_STUB_LATENCY=PT20S SPRING_PROFILES_ACTIVE=demo docker compose up --build
set -euo pipefail

API="${API:-http://localhost:8080}"
LAST_REQUEST="target/demo-last-request"

post() { # $1 = idempotency key, $2 = JSON body
  curl -sS -i -X POST "$API/appointments" -H 'Content-Type: application/json' -H "Idempotency-Key: $1" -d "$2"
  echo
}

sql() {
  docker compose exec -T db mysql -uapp -papp reminders --table -e "$1" 2>/dev/null
}

unsent() { # reminders the worker may still send; empty if the database can't be reached
  docker compose exec -T db mysql -uapp -papp reminders -N -e \
    "SELECT COUNT(*) FROM reminder WHERE status IN ('PENDING', 'PROCESSING')" 2>/dev/null
}

case "${1:-help}" in
  create)
    minutes="${2:-3}"
    key="${3:-demo-$(date +%s)}"
    at=$(date -u -v+"${minutes}"M +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -d "+${minutes} minutes" +%Y-%m-%dT%H:%M:%SZ)
    # A random Indian customer and car, so repeated runs look like real bookings.
    names=("Aarav Sharma" "Priya Iyer" "Rohan Mehta" "Ananya Reddy" "Vikram Singh" "Kavya Nair")
    phones=("+919876543210" "+919812345678" "+918765432109" "+919900112233" "+917012345678" "+919445566778")
    cars=("2023 Tata Nexon" "2022 Maruti Suzuki Swift" "2024 Mahindra XUV700" "2023 Tata Harrier" "2022 Mahindra Thar" "2024 Tata Punch")
    i=$((RANDOM % ${#names[@]}))
    body="{\"dealershipId\":1,\"customer\":{\"name\":\"${names[$i]}\",\"phone\":\"${phones[$i]}\",\"preferredChannel\":\"SMS\"},\"vehicle\":\"${cars[$i]}\",\"serviceType\":\"Oil change\",\"scheduledAt\":\"$at\"}"
    mkdir -p target && printf '%s\n%s\n' "$key" "$body" > "$LAST_REQUEST"
    post "$key" "$body"
    ;;
  replay)
    [ -f "$LAST_REQUEST" ] || { echo "run 'create' first"; exit 1; }
    post "$(sed -n 1p "$LAST_REQUEST")" "$(sed -n 2p "$LAST_REQUEST")"
    ;;
  crash)
    app=$(docker compose ps -q app)
    [ -n "$app" ] || { echo "the app is not running"; exit 1; }
    since=$(docker inspect -f '{{.State.StartedAt}}' "$app")
    echo ">>> waiting for a send to be in flight (book one with: $0 create)"
    # In flight = the newest stub line is a SEND, i.e. the provider call has started and not returned.
    until docker compose logs --since "$since" --no-log-prefix app 2>/dev/null \
        | grep -E 'NOTIFICATION_STUB_(SEND|ACCEPTED)' | tail -1 | grep -q NOTIFICATION_STUB_SEND; do
      if [ "$(unsent)" = 0 ]; then
        echo ">>> nothing left to send: no reminder is PENDING or PROCESSING. Book one with: $0 create 3, then run crash again"
        exit 1
      fi
      sleep 1
    done
    docker compose logs --since "$since" --no-log-prefix app | grep NOTIFICATION_STUB_SEND | tail -1 | cut -c1-170
    docker compose kill app # SIGKILL: no graceful shutdown, no outcome recorded
    echo ">>> killed mid-send. The reminder is still PROCESSING, and its delivery record says it was handed over:"
    sql "SELECT r.id, r.reminder_type, r.status, r.attempt_count AS tries, r.processing_until AS lease_until,
                d.status AS delivery_record
           FROM reminder r LEFT JOIN notification_delivery d ON d.idempotency_key = r.idempotency_key
          WHERE r.status = 'PROCESSING';"
    docker compose start app
    echo ">>> restarted. When the lease expires the reminder is reclaimed, and the retry is suppressed instead of resent."
    echo ">>> watch it with: $0 logs    then check: $0 invariants"
    ;;
  db)
    sql "SELECT id, dealership_id AS dealer, customer_name, scheduled_at, status, revision AS rev FROM appointment ORDER BY id;
         SELECT id, appointment_id AS appt, appointment_revision AS rev, reminder_type, status, due_at,
                attempt_count AS tries, processing_until AS lease_until, sent_at, LEFT(status_reason, 70) AS reason
           FROM reminder ORDER BY id;
         SELECT id, reminder_id AS reminder, attempt_no AS no, outcome, started_at, completed_at, LEFT(error, 50) AS error
           FROM notification_attempt ORDER BY id;
         SELECT idempotency_key, reminder_id AS reminder, attempt_no AS by_attempt, status, provider_message_id,
                recorded_at, accepted_at
           FROM notification_delivery ORDER BY recorded_at;"
    ;;
  payloads)
    sql "SELECT reminder_id AS reminder, attempt_no AS no, outcome, JSON_UNQUOTE(JSON_EXTRACT(payload, '$.body')) AS message
           FROM notification_attempt ORDER BY id;"
    ;;
  invariants)
    # The same SQL the integration tests run after every test (support/IntegrationTest).
    sql "SELECT 'I1 two rows for one logical reminder' AS invariant, COUNT(*) AS violations FROM (
           SELECT 1 FROM reminder GROUP BY appointment_id, appointment_revision, reminder_type HAVING COUNT(*) > 1) v
         UNION ALL
         SELECT 'I2 SENT but not recorded in notification_delivery', COUNT(*) FROM (
           SELECT r.id FROM reminder r LEFT JOIN notification_delivery d ON d.idempotency_key = r.idempotency_key
            WHERE r.status = 'SENT' AND d.idempotency_key IS NULL) v
         UNION ALL
         SELECT 'I3 more than one ACCEPTED attempt per reminder', COUNT(*) FROM (
           SELECT 1 FROM notification_attempt WHERE outcome = 'ACCEPTED' GROUP BY reminder_id HAVING COUNT(*) > 1) v;
         SELECT COUNT(*) AS 'in doubt: handed over, never acknowledged', COALESCE(GROUP_CONCAT(idempotency_key), '-') AS to_reconcile
           FROM notification_delivery
          WHERE status = 'RESERVED' AND recorded_at < UTC_TIMESTAMP(6) - INTERVAL 2 MINUTE;"
    ;;
  logs)
    docker compose logs -f --no-log-prefix app | grep --line-buffered -E "Started |APPOINTMENT_|REMINDER_|NOTIFICATION_"
    ;;
  *)
    sed -n '2,14p' "$0"
    ;;
esac
