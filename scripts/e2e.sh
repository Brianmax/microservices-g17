#!/usr/bin/env bash
set -euo pipefail

identity_url=${IDENTITY_URL:-http://localhost:8081}
banking_url=${BANKING_URL:-http://localhost:8083}
transfer_url=${TRANSFER_URL:-http://localhost:8084}
email="e2e-$(date +%s)-$RANDOM@example.com"
password='SecurePass123'

for endpoint in "$identity_url/actuator/health" "http://localhost:8082/actuator/health" "$banking_url/actuator/health" "$transfer_url/actuator/health" "http://localhost:8085/actuator/health"; do
  curl --fail --silent --show-error --retry 30 --retry-connrefused --retry-delay 1 "$endpoint" >/dev/null
done

customer=$(curl --fail --silent --show-error -H 'Content-Type: application/json' -d "{\"firstName\":\"End\",\"lastName\":\"ToEnd\",\"email\":\"$email\",\"password\":\"$password\"}" "$identity_url/api/v1/auth/register")
user_id=$(jq -r .id <<<"$customer")
tokens=$(curl --fail --silent --show-error -H 'Content-Type: application/json' -d "{\"email\":\"$email\",\"password\":\"$password\"}" "$identity_url/api/v1/auth/login")
access_token=$(jq -r .accessToken <<<"$tokens")
refresh_token=$(jq -r .refreshToken <<<"$tokens")
auth=(-H "Authorization: Bearer $access_token" -H 'Content-Type: application/json')

open_account(){ curl --fail --silent --show-error "${auth[@]}" -d "{\"accountType\":\"$1\",\"currency\":\"$2\"}" "$banking_url/api/v1/users/$user_id/accounts" | jq -r .id; }
usd_source=$(open_account CHECKING USD)
usd_destination=$(open_account SAVINGS USD)
eur_destination=$(open_account SAVINGS EUR)
curl --fail --silent --show-error "${auth[@]}" -d '{"amount":500,"description":"E2E funding"}' "$banking_url/api/v1/accounts/$usd_source/deposits" >/dev/null

same_request="{\"sourceAccountId\":\"$usd_source\",\"destinationAccountId\":\"$usd_destination\",\"amount\":25,\"description\":\"same currency\",\"idempotencyKey\":\"same-$RANDOM\"}"
same=$(curl --fail --silent --show-error "${auth[@]}" -d "$same_request" "$transfer_url/api/v1/transfers")
[[ $(jq -r .status <<<"$same") == COMPLETED ]]
[[ $(jq -r .destinationAmount <<<"$same") == 25.0000 ]]

fx_key="fx-$RANDOM"
fx_request="{\"sourceAccountId\":\"$usd_source\",\"destinationAccountId\":\"$eur_destination\",\"amount\":50,\"description\":\"FX transfer\",\"idempotencyKey\":\"$fx_key\"}"
fx=$(curl --fail --silent --show-error "${auth[@]}" -d "$fx_request" "$transfer_url/api/v1/transfers")
replay=$(curl --fail --silent --show-error "${auth[@]}" -d "$fx_request" "$transfer_url/api/v1/transfers")
[[ $(jq -r .id <<<"$fx") == "$(jq -r .id <<<"$replay")" ]]
[[ $(jq -r .exchangeRate <<<"$fx") == 0.92000000 ]]
[[ $(jq -r .destinationAmount <<<"$fx") == 46.0000 ]]

insufficient_code=$(curl --silent --output /dev/null --write-out '%{http_code}' "${auth[@]}" -d "{\"sourceAccountId\":\"$usd_source\",\"destinationAccountId\":\"$usd_destination\",\"amount\":99999,\"idempotencyKey\":\"funds-$RANDOM\"}" "$transfer_url/api/v1/transfers")
same_account_code=$(curl --silent --output /dev/null --write-out '%{http_code}' "${auth[@]}" -d "{\"sourceAccountId\":\"$usd_source\",\"destinationAccountId\":\"$usd_source\",\"amount\":1,\"idempotencyKey\":\"same-account-$RANDOM\"}" "$transfer_url/api/v1/transfers")
unauthorized_code=$(curl --silent --output /dev/null --write-out '%{http_code}' "$transfer_url/api/v1/transfers")
[[ $insufficient_code == 409 ]]
[[ $same_account_code == 400 ]]
[[ $unauthorized_code == 401 ]]

source=$(curl --fail --silent --show-error "${auth[@]}" "$banking_url/api/v1/accounts/$usd_source")
[[ $(jq -r .balance <<<"$source") == 425.0000 ]]

password_change_code=$(curl --silent --output /dev/null --write-out '%{http_code}' "${auth[@]}" -X PUT -d '{"currentPassword":"SecurePass123","newPassword":"ChangedPass456"}' "$identity_url/api/v1/auth/password")
stale_token_code=$(curl --silent --output /dev/null --write-out '%{http_code}' -H "Authorization: Bearer $access_token" "$identity_url/api/v1/auth/me")
revoked_refresh_code=$(curl --silent --output /dev/null --write-out '%{http_code}' -H 'Content-Type: application/json' -d "{\"refreshToken\":\"$refresh_token\"}" "$identity_url/api/v1/auth/refresh")
[[ $password_change_code == 204 ]]
[[ $stale_token_code == 401 ]]
[[ $revoked_refresh_code == 401 ]]
echo "E2E passed: user=$user_id same=$(jq -r .id <<<"$same") fx=$(jq -r .id <<<"$fx")"
