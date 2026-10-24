#!/bin/sh
set -eu

repo_dir=$(CDPATH='' cd -- "$(dirname -- "$0")/../.." && pwd)
key_dir=${1:-"$repo_dir/.local/streaming"}
umask 077
mkdir -p "$key_dir"
chmod 700 "$key_dir"

if [ -f "$key_dir/private.pem" ] && [ -f "$key_dir/public.pem" ]; then
    key_action=Reused
elif [ -e "$key_dir/private.pem" ] || [ -e "$key_dir/public.pem" ]; then
    echo 'Incomplete key pair: restore the missing key; existing keys were not changed.' >&2
    exit 1
else
    key_action=Created
fi

key_work_dir=$(mktemp -d "$key_dir/.keygen.XXXXXX")
trap 'rm -rf "$key_work_dir"' EXIT HUP INT TERM

if [ "$key_action" = Created ]; then
    openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \
        -out "$key_work_dir/private.pem" 2>"$key_work_dir/generation.log"
    openssl pkey -in "$key_work_dir/private.pem" -pubout -out "$key_work_dir/public.pem"
    mv "$key_work_dir/private.pem" "$key_dir/private.pem"
    mv "$key_work_dir/public.pem" "$key_dir/public.pem"
fi

openssl pkey -in "$key_dir/private.pem" -pubout -outform DER -out "$key_work_dir/derived.der"
openssl pkey -pubin -in "$key_dir/public.pem" -outform DER -out "$key_work_dir/public.der"
if ! cmp -s "$key_work_dir/derived.der" "$key_work_dir/public.der"; then
    echo 'Private/public keys do not match; existing keys were not changed.' >&2
    exit 1
fi
if [ ! -e "$key_dir/callback.secret" ]; then
    openssl rand -hex 32 >"$key_dir/callback.secret"
fi
chmod 600 "$key_dir/private.pem" "$key_dir/public.pem" "$key_dir/callback.secret"
echo "$key_action and verified RSA key pair; callback secret ready in $key_dir"
