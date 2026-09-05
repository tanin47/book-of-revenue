#!/usr/bin/env bash

set -euo pipefail

COMPOSE_URL="https://github.com/tanin47/book-of-revenue/releases/download/GIT_TAG/compose.yaml"

gen_secret() {
  if command -v openssl > /dev/null 2>&1; then
    openssl rand -hex 16
  else
    # head closes the pipe early, which makes tr exit with SIGPIPE; pipefail would abort the script.
    ( set +o pipefail; LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 32 )
  fi
}

if [ -e .env ]; then
  echo "A .env file already exists in $(pwd)."
  echo "Overwriting it generates a new POSTGRES_PASSWORD, which would lock the app out of an existing database."
  read -r -p "Overwrite it? [y/N] " overwrite
  case "${overwrite}" in
    y|Y|yes|YES) ;;
    *) echo "Aborted. Nothing was changed."; exit 1 ;;
  esac
fi

while [ -z "${app_domain:-}" ]; do
  read -r -p "Enter the domain for this installation (e.g. test.bookofrevenue.com): " app_domain
done

echo
echo "The database is stored at the persistent data path."
echo "This path MUST be on a persistent disk. If it is on ephemeral storage, all of your data is lost when the machine is replaced or recreated."
echo "The book-of-revenue-data directory will be created inside the chosen persistent data path."
while [ -z "${persistent_data_path:-}" ]; do
  read -r -p "Enter the persistent data path (e.g. /mnt/data or '.'): " persistent_data_path
done

persistent_data_path="$(cd "${persistent_data_path}" && pwd)"

echo "Creating ${persistent_data_path}/book-of-revenue-data and ${persistent_data_path}/book-of-revenue-data/pgdata..."
sudo mkdir -p "${persistent_data_path}/book-of-revenue-data/pgdata"

umask 077
cat > .env <<EOF
APP_DOMAIN=${app_domain}
COOKIE_SECRET_KEY=$(gen_secret)
POSTGRES_PASSWORD=$(gen_secret)
PERSISTENT_DATA_PATH=${persistent_data_path}/book-of-revenue-data
EOF

echo "Wrote .env"

echo "Downloading compose.yaml..."
if command -v curl > /dev/null 2>&1; then
  curl -fsSL -o compose.yaml "${COMPOSE_URL}"
elif command -v wget > /dev/null 2>&1; then
  wget -q -O compose.yaml "${COMPOSE_URL}"
else
  echo "Neither curl nor wget is available. Please install one of them and re-run." >&2
  exit 1
fi

echo "Starting Book of Revenue..."
sudo docker compose up -d --pull always
