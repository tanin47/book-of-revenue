# Create the initial schema

# --- !Ups

CREATE TABLE "user"
(
    id TEXT PRIMARY KEY DEFAULT ('user-' || gen_random_uuid()),
    username TEXT NOT NULL,
    hashed_password TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX user__username ON "user" (username);

CREATE TABLE "forgot_password_token"
(
    user_id TEXT NOT NULL,
    token TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE "email_verification_token"
(
    user_id TEXT NOT NULL,
    token TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);


CREATE TABLE raw_stripe_object
(
  stripe_account_id TEXT NOT NULL,
  live_mode BOOLEAN NOT NULL,
  id          TEXT,
  object_type TEXT NOT NULL,
  checksum    TEXT NOT NULL,
  raw_json    TEXT NOT NULL,
  synced_at TIMESTAMPTZ NOT NULL,
  processed_count INT NOT NULL
);

CREATE UNIQUE INDEX raw_stripe_object__id_checksum ON raw_stripe_object (id, checksum);
CREATE INDEX raw_stripe_object__id ON raw_stripe_object (id);

CREATE TABLE stripe_event
(
  stripe_account_id TEXT NOT NULL,
  live_mode BOOLEAN NOT NULL,
  id              TEXT PRIMARY KEY,
  raw_json        TEXT NOT NULL,
  processed_count INT NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL
);

CREATE TABLE stripe_importer_job
(
  stripe_account_id TEXT NOT NULL,
  live_mode BOOLEAN NOT NULL,
  id          TEXT PRIMARY KEY DEFAULT ('stripe-importer-job-' || gen_random_uuid()),
  started_at  TIMESTAMPTZ,
  finished_at TIMESTAMPTZ,
  job_type            TEXT NOT NULL,
  status            TEXT NOT NULL
);


CREATE TABLE stripe_importer_job_cursor
(
  stripe_importer_job_id TEXT NOT NULL,
  object_type            TEXT NOT NULL,
  customer_id            TEXT,
  latest_id              TEXT,
  starting_after         TEXT,
  ending_before          TEXT
);

CREATE INDEX stripe_importer_job_cursor__stripe_importer_job_id ON stripe_importer_job_cursor (stripe_importer_job_id);

CREATE TABLE invoice
(
  stripe_account_id TEXT NOT NULL,
  live_mode BOOLEAN NOT NULL,
  number TEXT,
  id           TEXT PRIMARY KEY,
  customer_id  TEXT NOT NULL,
  total        BIGINT NOT NULL,
  amount_paid      BIGINT NOT NULL DEFAULT 0,
  amount_overpaid  BIGINT NOT NULL DEFAULT 0,
  amount_remaining BIGINT NOT NULL DEFAULT 0,
  currency     TEXT NOT NULL,
  finalized_at TIMESTAMPTZ,
  paid_at      TIMESTAMPTZ,
  due_at       TIMESTAMPTZ,
  marked_uncollectible_at TIMESTAMPTZ,
  voided_at    TIMESTAMPTZ,
  starting_balance BIGINT,
  ending_balance   BIGINT,
  status       TEXT NOT NULL,
  synced_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE invoice_line_item
(
  stripe_account_id TEXT NOT NULL,
  live_mode BOOLEAN NOT NULL,
  description TEXT,
  id          TEXT PRIMARY KEY,
  invoice_id  TEXT      NOT NULL,
  amount      BIGINT    NOT NULL,
  currency    TEXT      NOT NULL,
  started_at  TIMESTAMPTZ,
  ended_at    TIMESTAMPTZ,
  rank        INT         NOT NULL,
  invoice_item_id TEXT,
  subscription_item_id TEXT,
  price_id TEXT,
  pricing_unit_amount_decimal TEXT,
  customer_id TEXT NOT NULL,
  synced_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE invoice_item
(
  stripe_account_id TEXT NOT NULL,
  live_mode BOOLEAN NOT NULL,
  description TEXT,
  id          TEXT PRIMARY KEY,
  invoice_id  TEXT,
  customer_id TEXT      NOT NULL,
  amount      BIGINT    NOT NULL,
  currency    TEXT      NOT NULL,
  started_at  TIMESTAMPTZ,
  ended_at    TIMESTAMPTZ,
  discount_ids TEXT[] NOT NULL DEFAULT '{}',
  tax_rate_ids TEXT[] NOT NULL DEFAULT '{}',
  price_id    TEXT,
  product_id  TEXT,
  created_at  TIMESTAMPTZ NOT NULL,
  synced_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE journal_entry
(
  stripe_account_id            TEXT NOT NULL,
  live_mode                     BOOLEAN NOT NULL,
  accounting_period            TIMESTAMPTZ NOT NULL,
  attribution_period           TIMESTAMPTZ,
  debit                        TEXT      NOT NULL,
  credit                       TEXT      NOT NULL,
  settlement_amount                       BIGINT    NOT NULL,
  settlement_currency                     TEXT      NOT NULL,
  presentment_amount                      BIGINT    NOT NULL,
  presentment_currency                    TEXT      NOT NULL,
  occurred_at                  TIMESTAMPTZ NOT NULL,
  event                        TEXT      NOT NULL,
  reversed_event               TEXT,
  principle_account            TEXT      NOT NULL,
  customer_id                  TEXT,
  invoice_id                   TEXT,
  invoice_line_item_id         TEXT,
  invoice_item_id              TEXT,
  charge_id                    TEXT,
  balance_transaction_id       TEXT,
  dispute_id                   TEXT,
  refund_id                    TEXT,
  customer_balance_transaction_id TEXT,
  payment_intent_id            TEXT,
  payment_record_id            TEXT,
  subscription_id              TEXT,
  subscription_item_id         TEXT,
  credit_balance_transaction_id TEXT,
  credit_note_id               TEXT,
  credit_note_line_item_id     TEXT,
  product_id                   TEXT,
  price_id                     TEXT,
  rev_rec_transaction_id       TEXT NOT NULL,
  rev_rec_transaction_type     TEXT NOT NULL,
  created_at                   TIMESTAMPTZ NOT NULL
);

CREATE TABLE rev_rec_transaction
(
  stripe_account_id TEXT NOT NULL,
  live_mode BOOLEAN NOT NULL,
  started_at TIMESTAMPTZ,
  id TEXT NOT NULL,
  type TEXT NOT NULL,
  status TEXT NOT NULL,
  customer_id TEXT,
  title TEXT,
  settlement_total_value BIGINT,
  settlement_currency TEXT,
  processed_at TIMESTAMPTZ,
  synced_at TIMESTAMPTZ,
  batch_timestamp TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX rev_rec_transaction__id__type ON rev_rec_transaction (id, type);

CREATE TABLE charge
(
  stripe_account_id TEXT NOT NULL,
  live_mode BOOLEAN NOT NULL,
  id              TEXT PRIMARY KEY,
  balance_transaction_id TEXT,
  customer_id     TEXT,
  amount          BIGINT NOT NULL,
  currency        TEXT NOT NULL,
  description     TEXT,
  disputed        BOOLEAN NOT NULL,
  refunded        BOOLEAN NOT NULL,
  amount_refunded BIGINT,
  payment_intent_id TEXT,
  created         TIMESTAMPTZ NOT NULL,
  status          TEXT NOT NULL,
  synced_at       TIMESTAMPTZ NOT NULL
);

CREATE TABLE customer
(
  stripe_account_id TEXT NOT NULL,
  live_mode BOOLEAN NOT NULL,
  id        TEXT PRIMARY KEY,
  name      TEXT,
  email     TEXT,
  synced_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE customer_balance_transaction
(
  stripe_account_id TEXT NOT NULL,
  live_mode BOOLEAN NOT NULL,
  id              TEXT PRIMARY KEY,
  amount          BIGINT NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL,
  currency        TEXT NOT NULL,
  customer_id     TEXT NOT NULL,
  description     TEXT,
  ending_balance  BIGINT NOT NULL,
  invoice_id      TEXT,
  credit_note_id  TEXT,
  type            TEXT NOT NULL,
  synced_at       TIMESTAMPTZ NOT NULL
);

CREATE TABLE invoice_payment
(
  stripe_account_id TEXT NOT NULL,
  live_mode BOOLEAN NOT NULL,
  id                 TEXT PRIMARY KEY,
  amount_paid        BIGINT,
  amount_requested   BIGINT,
  currency           TEXT NOT NULL,
  invoice_id         TEXT NOT NULL,
  charge_id          TEXT,
  payment_intent_id  TEXT,
  payment_record_id  TEXT,
  payment_type       TEXT,
  created_at         TIMESTAMPTZ,
  canceled_at        TIMESTAMPTZ,
  paid_at            TIMESTAMPTZ,
  status             TEXT NOT NULL,
  synced_at          TIMESTAMPTZ NOT NULL
);

CREATE TABLE payment_intent
(
  stripe_account_id TEXT NOT NULL,
  live_mode BOOLEAN NOT NULL,
  id             TEXT PRIMARY KEY,
  customer_id    TEXT,
  amount         BIGINT NOT NULL DEFAULT 0,
  currency       TEXT NOT NULL,
  description    TEXT,
  latest_charge_id  TEXT,
  synced_at      TIMESTAMPTZ NOT NULL
);

CREATE TABLE balance_transaction
(
  stripe_account_id TEXT NOT NULL,
  live_mode BOOLEAN NOT NULL,
  id                 TEXT PRIMARY KEY,
  amount             BIGINT NOT NULL,
  currency           TEXT NOT NULL,
  description        TEXT NOT NULL,
  fee_amount         BIGINT NOT NULL,
  net_amount         BIGINT NOT NULL,
  status             TEXT NOT NULL,
  type               TEXT NOT NULL,
  source             TEXT,
  created_at         TIMESTAMPTZ NOT NULL,
  synced_at          TIMESTAMPTZ NOT NULL
);

CREATE TABLE dispute
(
  stripe_account_id TEXT NOT NULL,
  live_mode BOOLEAN NOT NULL,
  id                     TEXT PRIMARY KEY,
  balance_transaction_ids TEXT[] NOT NULL,
  amount                 BIGINT NOT NULL,
  currency               TEXT NOT NULL,
  charge_id              TEXT,
  payment_intent_id      TEXT,
  status                 TEXT NOT NULL,
  created_at             TIMESTAMPTZ NOT NULL,
  synced_at              TIMESTAMPTZ NOT NULL
);

CREATE TABLE refund
(
  stripe_account_id TEXT NOT NULL,
  live_mode BOOLEAN NOT NULL,
  id                     TEXT PRIMARY KEY,
  balance_transaction_id TEXT,
  failure_balance_transaction_id TEXT,
  amount                 BIGINT NOT NULL,
  currency               TEXT NOT NULL,
  charge_id              TEXT,
  payment_intent_id      TEXT,
  status                 TEXT NOT NULL,
  created_at             TIMESTAMPTZ NOT NULL,
  synced_at              TIMESTAMPTZ NOT NULL
);

CREATE TABLE subscription
(
  stripe_account_id TEXT NOT NULL,
  live_mode BOOLEAN NOT NULL,
  id                 TEXT PRIMARY KEY,
  customer_id        TEXT NOT NULL,
  currency           TEXT NOT NULL,
  status             TEXT NOT NULL,
  start_date         TIMESTAMPTZ NOT NULL,
  discount_ids       TEXT[] NOT NULL DEFAULT '{}',
  default_tax_rate_ids TEXT[] NOT NULL DEFAULT '{}',
  synced_at          TIMESTAMPTZ NOT NULL
);

CREATE TABLE subscription_item
(
  stripe_account_id TEXT NOT NULL,
  live_mode BOOLEAN NOT NULL,
  id                   TEXT PRIMARY KEY,
  subscription_id      TEXT NOT NULL,
  price_id             TEXT NOT NULL,
  quantity             BIGINT NOT NULL,
  current_period_end   TIMESTAMPTZ NOT NULL,
  current_period_start TIMESTAMPTZ NOT NULL,
  discount_ids         TEXT[] NOT NULL DEFAULT '{}',
  tax_rate_ids         TEXT[] NOT NULL DEFAULT '{}',
  synced_at            TIMESTAMPTZ NOT NULL
);

CREATE INDEX subscription_item__subscription_id ON subscription_item (subscription_id);

CREATE TABLE product
(
  stripe_account_id TEXT NOT NULL,
  live_mode          BOOLEAN NOT NULL,
  id                TEXT PRIMARY KEY,
  name              TEXT NOT NULL,
  description       TEXT,
  synced_at         TIMESTAMPTZ NOT NULL
);

CREATE TABLE price
(
  stripe_account_id TEXT NOT NULL,
  live_mode BOOLEAN NOT NULL,
  id                       TEXT PRIMARY KEY,
  currency                 TEXT NOT NULL,
  product_id               TEXT NOT NULL,
  type                     TEXT NOT NULL,
  billing_scheme           TEXT NOT NULL,
  unit_amount              BIGINT NOT NULL,
  tiers_mode               TEXT,
  recurring_interval       TEXT,
  recurring_interval_count INTEGER,
  recurring_meter_id       TEXT,
  recurring_usage_type     TEXT,
  synced_at                TIMESTAMPTZ NOT NULL
);

CREATE TABLE price_tier
(
  stripe_account_id TEXT NOT NULL,
  live_mode BOOLEAN NOT NULL,
  price_id           TEXT NOT NULL,
  flat_amount        BIGINT,
  unit_amount        BIGINT,
  up_to              BIGINT,
  synced_at          TIMESTAMPTZ NOT NULL
);

CREATE INDEX price_tier__price_id ON price_tier (price_id);

CREATE TABLE meter_event_summary
(
  stripe_account_id TEXT NOT NULL,
  live_mode BOOLEAN NOT NULL,
  id                 TEXT PRIMARY KEY,
  aggregated_value   BIGINT NOT NULL,
  meter_id           TEXT NOT NULL,
  customer_id        TEXT NOT NULL,
  start_time         TIMESTAMPTZ NOT NULL,
  end_time           TIMESTAMPTZ NOT NULL,
  synced_at          TIMESTAMPTZ NOT NULL
);

CREATE INDEX meter_event_summary__meter_id ON meter_event_summary (meter_id);

CREATE TABLE invoice_line_item_discount_amount
(
  stripe_account_id TEXT NOT NULL,
  live_mode BOOLEAN NOT NULL,
  rank                 INT NOT NULL,
  invoice_line_item_id TEXT NOT NULL,
  amount               BIGINT NOT NULL,
  discount_id          TEXT NOT NULL
);

CREATE INDEX invoice_line_item_discount_amount__invoice_line_item_id ON invoice_line_item_discount_amount (invoice_line_item_id);

CREATE TABLE invoice_line_item_tax
(
  stripe_account_id TEXT NOT NULL,
  live_mode BOOLEAN NOT NULL,
  rank                 INT NOT NULL,
  invoice_line_item_id TEXT NOT NULL,
  amount               BIGINT NOT NULL,
  tax_behaviour        TEXT NOT NULL,
  tax_rate_id          TEXT
);

CREATE INDEX invoice_line_item_tax__invoice_line_item_id ON invoice_line_item_tax (invoice_line_item_id);

CREATE TABLE invoice_line_item_pretax_credit_amount
(
  stripe_account_id TEXT NOT NULL,
  live_mode BOOLEAN NOT NULL,
  rank                          INT NOT NULL,
  invoice_line_item_id          TEXT NOT NULL,
  amount                        BIGINT NOT NULL,
  discount_id                   TEXT,
  credit_balance_transaction_id TEXT,
  type                          TEXT NOT NULL
);

CREATE INDEX invoice_line_item_pretax_credit_amount__invoice_line_item_id ON invoice_line_item_pretax_credit_amount (invoice_line_item_id);

CREATE TABLE credit_grant
(
  stripe_account_id TEXT NOT NULL,
  live_mode BOOLEAN NOT NULL,
  id           TEXT PRIMARY KEY,
  customer_id     TEXT NOT NULL,
  amount       BIGINT,
  currency     TEXT,
  category     TEXT NOT NULL,
  created_at   TIMESTAMPTZ NOT NULL,
  effective_at TIMESTAMPTZ NOT NULL,
  expires_at   TIMESTAMPTZ,
  voided_at    TIMESTAMPTZ
);

CREATE TABLE credit_balance_transaction
(
  stripe_account_id TEXT NOT NULL,
  live_mode BOOLEAN NOT NULL,
  id                                        TEXT PRIMARY KEY,
  created_at                                TIMESTAMPTZ NOT NULL,
  effective_at                              TIMESTAMPTZ NOT NULL,
  type                                      TEXT,
  credit_grant_id                           TEXT NOT NULL,
  credit_amount                             BIGINT,
  credit_currency                           TEXT,
  credit_type                               TEXT,
  credit_invoice_voided_invoice_id          TEXT,
  credit_invoice_voided_invoice_line_item_id TEXT,
  debit_amount                              BIGINT,
  debit_currency                            TEXT,
  debit_type                                TEXT,
  debit_credits_applied_invoice_id          TEXT,
  debit_credits_applied_invoice_line_item_id TEXT,
  synced_at                                 TIMESTAMPTZ NOT NULL
);

CREATE TABLE credit_note
(
  stripe_account_id TEXT NOT NULL,
  live_mode BOOLEAN NOT NULL,
  id                              TEXT PRIMARY KEY,
  type                            TEXT NOT NULL,
  invoice_id                      TEXT NOT NULL,
  currency                        TEXT NOT NULL,
  total                           BIGINT NOT NULL,
  pre_payment_amount              BIGINT NOT NULL,
  customer_balance_transaction_id TEXT,
  out_of_band_amount              BIGINT,
  created_at                      TEXT NOT NULL,
  effective_at                    TEXT,
  voided_at                       TEXT
);

CREATE TABLE credit_note_line_item
(
  stripe_account_id TEXT NOT NULL,
  live_mode BOOLEAN NOT NULL,
  id                   TEXT PRIMARY KEY,
  credit_note_id       TEXT NOT NULL,
  description          TEXT,
  rank                 INT NOT NULL,
  amount               BIGINT NOT NULL,
  type                 TEXT NOT NULL,
  invoice_line_item_id TEXT
);

CREATE TABLE credit_note_line_item_pretax_credit_amount
(
  rank                          INT NOT NULL,
  credit_note_line_item_id      TEXT NOT NULL,
  amount                        BIGINT NOT NULL,
  discount_id                   TEXT,
  credit_balance_transaction_id TEXT,
  type                          TEXT NOT NULL
);

CREATE INDEX credit_note_line_item__credit_note_id ON credit_note_line_item (credit_note_id);

CREATE TABLE credit_note_refund
(
  stripe_account_id TEXT NOT NULL,
  live_mode BOOLEAN NOT NULL,
  credit_note_id           TEXT NOT NULL,
  rank                     INT NOT NULL,
  refund_id                TEXT,
  type                     TEXT NOT NULL,
  amount_refunded          BIGINT NOT NULL,
  payment_record_refund_id TEXT
);

CREATE INDEX credit_note_refund__credit_note_id ON credit_note_refund (credit_note_id);

CREATE TABLE credit_note_line_item_tax
(
  stripe_account_id TEXT NOT NULL,
  live_mode BOOLEAN NOT NULL,
  credit_note_line_item_id TEXT NOT NULL,
  rank                     INT NOT NULL,
  amount                   BIGINT NOT NULL,
  tax_behavior             TEXT NOT NULL
);

CREATE INDEX credit_note_line_item_tax__credit_note_line_item_id ON credit_note_line_item_tax (credit_note_line_item_id);

CREATE TABLE coupon
(
  stripe_account_id TEXT NOT NULL,
  live_mode BOOLEAN NOT NULL,
  id           TEXT PRIMARY KEY,
  amount_off   BIGINT,
  currency     TEXT,
  percent_off  DOUBLE PRECISION
);

CREATE TABLE discount
(
  stripe_account_id TEXT NOT NULL,
  live_mode BOOLEAN NOT NULL,
  id         TEXT PRIMARY KEY,
  coupon_id  TEXT
);

CREATE TABLE tax_rate
(
  stripe_account_id TEXT NOT NULL,
  live_mode BOOLEAN NOT NULL,
  id                   TEXT PRIMARY KEY,
  inclusive            BOOLEAN NOT NULL,
  percentage           DOUBLE PRECISION NOT NULL,
  flat_amount          BIGINT,
  flat_amount_currency TEXT,
  rate_type            TEXT
);


CREATE TABLE file
(
  name    TEXT NOT NULL,
  content TEXT NOT NULL
);

CREATE UNIQUE INDEX file__name ON file (name);

CREATE TABLE http01_challenge_entry
(
  domain     TEXT NOT NULL,
  token      TEXT NOT NULL,
  content    TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE exported_file
(
  id            TEXT PRIMARY KEY DEFAULT ('exported-file-' || gen_random_uuid()),
  filename      TEXT NOT NULL,
  tmp_file_path TEXT NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL
);

CREATE TABLE stripe_account
(
  id               TEXT PRIMARY KEY,
  name             TEXT NOT NULL,
  default_currency TEXT NOT NULL,
  live_mode_api_key TEXT,
  test_mode_api_key TEXT
);

CREATE UNIQUE INDEX stripe_account__live_mode_api_key ON stripe_account (live_mode_api_key);
CREATE UNIQUE INDEX stripe_account__test_mode_api_key ON stripe_account (test_mode_api_key);

CREATE TABLE tracked_exception
(
  created_at          TIMESTAMPTZ NOT NULL,
  exception_class     TEXT NOT NULL,
  message             TEXT NOT NULL,
  stack_trace         TEXT NOT NULL
);

# --- !Downs

DROP TABLE "exported_file";
DROP TABLE "file";
DROP TABLE "http01_challenge_entry";
DROP TABLE "stripe_account";
DROP TABLE "email_verification_token";
DROP TABLE "forgot_password_token";
DROP TABLE "user";
DROP TABLE balance_transaction;
DROP TABLE charge;
DROP TABLE coupon;
DROP TABLE credit_balance_transaction;
DROP TABLE credit_grant;
DROP TABLE credit_note;
DROP TABLE credit_note_line_item;
DROP TABLE credit_note_line_item_pretax_credit_amount;
DROP TABLE credit_note_line_item_tax;
DROP TABLE credit_note_refund;
DROP TABLE customer;
DROP TABLE customer_balance_transaction;
DROP TABLE discount;
DROP TABLE dispute;
DROP TABLE invoice;
DROP TABLE invoice_item;
DROP TABLE invoice_line_item;
DROP TABLE invoice_line_item_discount_amount;
DROP TABLE invoice_line_item_pretax_credit_amount;
DROP TABLE invoice_line_item_tax;
DROP TABLE invoice_payment;
DROP TABLE journal_entry;
DROP TABLE meter_event_summary;
DROP TABLE payment_intent;
DROP TABLE price;
DROP TABLE price_tier;
DROP TABLE product;
DROP TABLE raw_stripe_object;
DROP TABLE refund;
DROP TABLE rev_rec_transaction;
DROP TABLE stripe_event;
DROP TABLE stripe_importer_job;
DROP TABLE stripe_importer_job_cursor;
DROP TABLE subscription;
DROP TABLE subscription_item;
DROP TABLE tax_rate;
DROP TABLE tracked_exception;
