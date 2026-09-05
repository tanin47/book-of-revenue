Development guide
===================

Requirements:
* Java/JDK 25 
* [SBT](https://www.scala-sbt.org/) >= 1.11

[SDKMAN](https://sdkman.io/) is recommended for managing JDK and SBT

Run locally
-------------

1. Set up a local Postgres instance that is compatible with the below Postgres URLs:
  * `postgres://bor_dev_user:dev@localhost:5432/bor_dev` for development
  * `postgres://bor_test_user:test@localhost:5432/bor_test` for test
2. Run `npm install`
3. Run `npm run hmr` to start the frontend hot-reload compilation server
4. Run `sbt run` to run the web server
5. Visit `http://localhost:9000`

When running locally, the background jobs are not running. 
If you want to run a background job, you will have to run it manually e.g. `sbt 'runMain background.StripeImporter'`.

Publish & test
-----------------

For initial installation:

1. Run `sbt stage docker:publish`
2. Copy `compose.yaml` to the server
3. Switch to the server
4. Set up `.env` with the required environment variables defined in `compose.yaml` e.g. `APP_DOMAIN`
5. Run `sudo docker compose up -d --pull always`
6. Visit `http://APP_DOMAIN` (not https) to set up

For redeploying:

1. Run `sbt stage docker:publish`
2. Switch to the server
3. Run `sudo docker compose up -d --pull always`
