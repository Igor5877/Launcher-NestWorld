#!/usr/bin/env bash
# A throw-away Azuriom + MySQL in Docker with the Launcher Access plugin and example data.
#
#   tests/demo.sh          start (the first run downloads and builds for a few minutes)
#   tests/demo.sh test     also run the integration test against that MySQL
#   tests/demo.sh down     stop and remove the containers (the files stay in $DEMO_DIR)
#
# Then open http://127.0.0.1:8081/user/login   login: admin   password: admin123
# Your real site and database are never touched.
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
PLUGIN=$(dirname "$HERE")
WORK=${DEMO_DIR:-$HOME/.cache/launcher-access-demo}
PORT=${DEMO_PORT:-8081}
NET=la-demo
IMAGE=la-php
AZURIOM_REF=${AZURIOM_REF:-master}

say() { printf '\n\033[1m== %s\033[0m\n' "$*"; }

php_run() {
    docker run --rm --network "$NET" --user "$(id -u):$(id -g)" -e HOME=/tmp -e COMPOSER_HOME=/tmp/composer \
        -v "$WORK/azuriom:/app" -v "$PLUGIN:/app/plugins/launcheraccess:ro" -w /app "$@"
}

if [[ "${1:-up}" == "down" ]]; then
    docker rm -f la-web la-mysql >/dev/null 2>&1 || true
    docker network rm "$NET" >/dev/null 2>&1 || true
    echo "stopped. files: $WORK"
    exit 0
fi

command -v docker >/dev/null || { echo "docker is required"; exit 1; }
mkdir -p "$WORK"

say "PHP image"
docker image inspect "$IMAGE" >/dev/null 2>&1 || docker build -t "$IMAGE" -f "$HERE/demo.Dockerfile" "$HERE"

say "MySQL"
docker network inspect "$NET" >/dev/null 2>&1 || docker network create "$NET" >/dev/null
if ! docker ps -a --format '{{.Names}}' | grep -qx la-mysql; then
    docker run -d --name la-mysql --network "$NET" -e MYSQL_ROOT_PASSWORD=demo -e MYSQL_DATABASE=azuriom mysql:8.0 >/dev/null
fi
docker start la-mysql >/dev/null
until docker exec la-mysql mysqladmin ping -uroot -pdemo --silent >/dev/null 2>&1; do sleep 2; done

say "Azuriom"
if [[ ! -f "$WORK/azuriom/artisan" ]]; then
    git clone --depth 1 --branch "$AZURIOM_REF" https://github.com/Azuriom/Azuriom.git "$WORK/azuriom"
fi
mkdir -p "$WORK/azuriom/plugins/launcheraccess"
if [[ ! -d "$WORK/azuriom/vendor" ]]; then
    php_run "$IMAGE" composer install --no-dev --no-interaction --no-progress --prefer-dist
fi

if [[ ! -f "$WORK/azuriom/public/build/manifest.json" ]]; then
    say "Admin theme (npm)"
    (cd "$WORK/azuriom" && npm ci --no-audit --no-fund && npm run build) || echo "npm build failed: the panel will look unstyled, the plugin still works"
fi

if [[ ! -f "$WORK/azuriom/.env" ]]; then
    cp "$WORK/azuriom/.env.example" "$WORK/azuriom/.env"
    sed -i -e 's/^DB_CONNECTION=.*/DB_CONNECTION=mysql/' -e 's/^DB_HOST=.*/DB_HOST=la-mysql/' \
        -e 's/^DB_PASSWORD=.*/DB_PASSWORD=demo/' -e 's/^APP_ENV=.*/APP_ENV=local/' -e 's/^APP_DEBUG=.*/APP_DEBUG=true/' \
        -e "s#^APP_URL=.*#APP_URL=http://127.0.0.1:$PORT#" "$WORK/azuriom/.env"
    php_run "$IMAGE" php artisan key:generate --force
fi
echo '["launcheraccess"]' > "$WORK/azuriom/plugins/plugins.json"

say "Database and example data"
php_run "$IMAGE" php artisan migrate --force
php_run "$IMAGE" php /app/plugins/launcheraccess/tests/demo-seed.php /app

if [[ "${1:-up}" == "test" ]]; then
    say "Integration test (MySQL)"
    php_run "$IMAGE" php /app/plugins/launcheraccess/tests/azuriom-integration.php /app
    php_run "$IMAGE" php /app/plugins/launcheraccess/tests/demo-seed.php /app
fi

say "Web server"
docker rm -f la-web >/dev/null 2>&1 || true
docker run -d --name la-web --network "$NET" --user "$(id -u):$(id -g)" -e HOME=/tmp \
    -v "$WORK/azuriom:/app" -v "$PLUGIN:/app/plugins/launcheraccess:ro" -w /app -p "127.0.0.1:$PORT:8000" \
    "$IMAGE" php artisan serve --host=0.0.0.0 --port=8000 >/dev/null
sleep 2

cat <<MSG

  Ready:  http://127.0.0.1:$PORT/admin/launcheraccess
  Login:  admin / admin123   (http://127.0.0.1:$PORT/user/login)
  Stop:   $0 down
MSG
