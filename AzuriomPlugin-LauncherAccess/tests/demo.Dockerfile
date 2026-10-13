# PHP 8.3 with what Azuriom needs, for the throw-away demo (the host PHP may be too old)
FROM php:8.3-cli
RUN apt-get update && apt-get install -y --no-install-recommends git unzip && rm -rf /var/lib/apt/lists/*
ADD https://github.com/mlocati/docker-php-extension-installer/releases/latest/download/install-php-extensions /usr/local/bin/
RUN chmod +x /usr/local/bin/install-php-extensions && install-php-extensions gd intl zip bcmath pdo_mysql
COPY --from=composer:2 /usr/bin/composer /usr/local/bin/composer
