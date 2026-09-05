CREATE USER bor_dev_user WITH PASSWORD 'dev';
CREATE DATABASE bor_dev;
GRANT ALL PRIVILEGES ON DATABASE bor_dev to bor_dev_user;
ALTER ROLE bor_dev_user superuser;

CREATE DATABASE bor_test;
GRANT ALL PRIVILEGES ON DATABASE bor_test to bor_dev_user;
