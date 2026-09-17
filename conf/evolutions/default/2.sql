# Create the Metronome schema

# --- !Ups

CREATE SCHEMA metronome;

CREATE TABLE metronome.credit_type
(
  id                            TEXT PRIMARY KEY,
  environment_type              TEXT,
  name                          TEXT,
  is_currency                   BOOLEAN,
  updated_at                    TIMESTAMPTZ
);

CREATE TABLE metronome.customer
(
  id                            TEXT PRIMARY KEY,
  environment_type              TEXT,
  name                          TEXT,
  ingest_aliases                TEXT,
  salesforce_account_id         TEXT,
  billing_provider_type         TEXT,
  billing_provider_customer_id  TEXT,
  custom_fields                 TEXT,
  created_at                    TIMESTAMPTZ,
  updated_at                    TIMESTAMPTZ,
  archived_at                   TIMESTAMPTZ
);


CREATE TABLE metronome.invoice
(
  id                                        TEXT PRIMARY KEY,
  environment_type                          TEXT,
  status                                    TEXT,
  total                                     NUMERIC,
  credit_type_id                            TEXT,
  credit_type_name                          TEXT,
  customer_id                               TEXT,
  plan_id                                   TEXT,
  plan_name                                 TEXT,
  contract_id                               TEXT,
  billing_provider_invoice_id               TEXT,
  billing_provider_type                     TEXT,
  billing_provider_invoice_created_at       TIMESTAMPTZ,
  billing_provider_invoice_external_status  TEXT,
  invoice_label                             TEXT,
  metadata                                  TEXT,
  start_timestamp                           TIMESTAMPTZ,
  end_timestamp                             TIMESTAMPTZ,
  issued_at                                 TIMESTAMPTZ,
  updated_at                                TIMESTAMPTZ
);

CREATE INDEX metronome__invoice__customer_id ON metronome.invoice (customer_id);

CREATE TABLE metronome.line_item
(
  id                    TEXT PRIMARY KEY,
  environment_type      TEXT,
  invoice_id            TEXT,
  credit_grant_id       TEXT,
  credit_type_id        TEXT,
  credit_type_name      TEXT,
  name                  TEXT,
  quantity              NUMERIC,
  total                 NUMERIC,
  commit_id             TEXT,
  product_id            TEXT,
  group_key             TEXT,
  group_value           TEXT,
  unit_price            NUMERIC,
  pricing_group_values  TEXT,
  metadata              TEXT,
  subscription_id       TEXT,
  is_prorated           BOOLEAN,
  starting_at           TIMESTAMPTZ,
  ending_before         TIMESTAMPTZ,
  updated_at            TIMESTAMPTZ
);

CREATE INDEX metronome__line_item__invoice_id ON metronome.line_item (invoice_id);

CREATE TABLE metronome.draft_invoice
(
  _metronome_metadata_id               TEXT,
  id                                   TEXT NOT NULL,
  environment_type                     TEXT,
  snapshot_time                        TIMESTAMPTZ,
  status                               TEXT,
  total                                NUMERIC,
  credit_type_id                       TEXT,
  credit_type_name                     TEXT,
  customer_id                          TEXT,
  plan_id                              TEXT,
  plan_name                            TEXT,
  contract_id                          TEXT,
  billable_status                      TEXT,
  billing_provider_invoice_id          TEXT,
  billing_provider_invoice_created_at  TIMESTAMPTZ,
  label                                TEXT,
  start_timestamp                      TIMESTAMPTZ,
  end_timestamp                        TIMESTAMPTZ,
  updated_at                           TIMESTAMPTZ
);

CREATE UNIQUE INDEX metronome__draft_invoice__id__snapshot_time ON metronome.draft_invoice (id, snapshot_time);
CREATE INDEX metronome__draft_invoice__customer_id ON metronome.draft_invoice (customer_id);

CREATE TABLE metronome.draft_line_item
(
  _metronome_metadata_id  TEXT,
  id                      TEXT NOT NULL,
  environment_type        TEXT,
  snapshot_time           TIMESTAMPTZ,
  invoice_id              TEXT,
  credit_grant_id         TEXT,
  credit_type_id          TEXT,
  credit_type_name        TEXT,
  name                    TEXT,
  quantity                NUMERIC,
  total                   NUMERIC,
  commit_id               TEXT,
  product_id              TEXT,
  group_key               TEXT,
  group_value             TEXT,
  unit_price              NUMERIC,
  pricing_group_values    TEXT,
  subscription_id         TEXT,
  is_prorated             BOOLEAN,
  metadata                TEXT,
  starting_at             TIMESTAMPTZ,
  ending_before           TIMESTAMPTZ,
  updated_at              TIMESTAMPTZ
);

CREATE UNIQUE INDEX metronome__draft_line_item__id__snapshot_time ON metronome.draft_line_item (id, snapshot_time);
CREATE INDEX metronome__draft_line_item__invoice_id ON metronome.draft_line_item (invoice_id);

CREATE TABLE metronome.breakdowns_invoices
(
  id                         TEXT NOT NULL,
  environment_type           TEXT,
  snapshot_timestamp         TIMESTAMPTZ,
  invoice_id                 TEXT,
  customer_id                TEXT,
  transfer_id                TEXT,
  credit_type_id             TEXT,
  net_payment_term_days      INT,
  credit_type_name           TEXT,
  subtotal                   NUMERIC,
  total                      NUMERIC,
  type                       TEXT,
  external_invoice           TEXT,
  plan_id                    TEXT,
  contract_id                TEXT,
  amendment_id               TEXT,
  custom_fields              TEXT,
  billable_status            TEXT,
  window_size                TEXT,
  metadata                   TEXT,
  issued_at                  TIMESTAMPTZ,
  invoice_start_timestamp    TIMESTAMPTZ,
  invoice_end_timestamp      TIMESTAMPTZ,
  breakdown_start_timestamp  TIMESTAMPTZ,
  breakdown_end_timestamp    TIMESTAMPTZ,
  updated_at                 TIMESTAMPTZ
);

CREATE UNIQUE INDEX metronome__breakdowns_invoices__id__snapshot_timestamp ON metronome.breakdowns_invoices (id, snapshot_timestamp);
CREATE INDEX metronome__breakdowns_invoices__invoice_id ON metronome.breakdowns_invoices (invoice_id);
CREATE INDEX metronome__breakdowns_invoices__customer_id ON metronome.breakdowns_invoices (customer_id);

CREATE TABLE metronome.breakdowns_line_items
(
  id                         TEXT NOT NULL,
  environment_type           TEXT,
  snapshot_timestamp         TIMESTAMPTZ,
  watermark_timestamp        TIMESTAMPTZ,
  invoice_breakdown_id       TEXT,
  name                       TEXT,
  transfer_id                TEXT,
  group_key                  TEXT,
  group_value                TEXT,
  quantity                   NUMERIC,
  total                      NUMERIC,
  unit_price                 NUMERIC,
  product_id                 TEXT,
  product_type               TEXT,
  credit_type_id             TEXT,
  credit_type_name           TEXT,
  commit_id                  TEXT,
  commit_segment_id          TEXT,
  commit_type                TEXT,
  subscription_id            TEXT,
  is_prorated                BOOLEAN,
  line_item_id               TEXT,
  line_item_type             TEXT,
  custom_fields              TEXT,
  pricing_group_values       TEXT,
  presentation_group_values  TEXT,
  billable_metric_id         TEXT,
  metadata                   TEXT,
  breakdown_start_timestamp  TIMESTAMPTZ,
  breakdown_end_timestamp    TIMESTAMPTZ,
  updated_at                 TIMESTAMPTZ
);

CREATE UNIQUE INDEX metronome__breakdowns_line_items__id__snapshot_timestamp ON metronome.breakdowns_line_items (id, snapshot_timestamp);
CREATE INDEX metronome__breakdowns_line_items__invoice_breakdown_id ON metronome.breakdowns_line_items (invoice_breakdown_id);

CREATE TABLE metronome.breakdowns_draft_invoices
(
  id                         TEXT NOT NULL,
  environment_type           TEXT,
  snapshot_timestamp         TIMESTAMPTZ,
  invoice_id                 TEXT,
  customer_id                TEXT,
  transfer_id                TEXT,
  credit_type_id             TEXT,
  net_payment_term_days      INT,
  credit_type_name           TEXT,
  subtotal                   NUMERIC,
  total                      NUMERIC,
  type                       TEXT,
  external_invoice           TEXT,
  plan_id                    TEXT,
  contract_id                TEXT,
  amendment_id               TEXT,
  custom_fields              TEXT,
  billable_status            TEXT,
  window_size                TEXT,
  metadata                   TEXT,
  issued_at                  TIMESTAMPTZ,
  invoice_start_timestamp    TIMESTAMPTZ,
  invoice_end_timestamp      TIMESTAMPTZ,
  breakdown_start_timestamp  TIMESTAMPTZ,
  breakdown_end_timestamp    TIMESTAMPTZ,
  updated_at                 TIMESTAMPTZ
);

CREATE UNIQUE INDEX metronome__breakdowns_draft_invoices__id__snapshot_timestamp ON metronome.breakdowns_draft_invoices (id, snapshot_timestamp);
CREATE INDEX metronome__breakdowns_draft_invoices__invoice_id ON metronome.breakdowns_draft_invoices (invoice_id);
CREATE INDEX metronome__breakdowns_draft_invoices__customer_id ON metronome.breakdowns_draft_invoices (customer_id);

CREATE TABLE metronome.breakdowns_draft_line_items
(
  id                         TEXT NOT NULL,
  environment_type           TEXT,
  snapshot_timestamp         TIMESTAMPTZ,
  watermark_timestamp        TIMESTAMPTZ,
  invoice_breakdown_id       TEXT,
  name                       TEXT,
  transfer_id                TEXT,
  group_key                  TEXT,
  group_value                TEXT,
  quantity                   NUMERIC,
  total                      NUMERIC,
  unit_price                 NUMERIC,
  product_id                 TEXT,
  product_type               TEXT,
  credit_type_id             TEXT,
  credit_type_name           TEXT,
  commit_id                  TEXT,
  commit_segment_id          TEXT,
  commit_type                TEXT,
  subscription_id            TEXT,
  is_prorated                BOOLEAN,
  line_item_id               TEXT,
  line_item_type             TEXT,
  custom_fields              TEXT,
  pricing_group_values       TEXT,
  presentation_group_values  TEXT,
  billable_metric_id         TEXT,
  metadata                   TEXT,
  breakdown_start_timestamp  TIMESTAMPTZ,
  breakdown_end_timestamp    TIMESTAMPTZ,
  updated_at                 TIMESTAMPTZ
);

CREATE UNIQUE INDEX metronome__breakdowns_draft_line_items__id__snapshot_timestamp ON metronome.breakdowns_draft_line_items (id, snapshot_timestamp);
CREATE INDEX metronome__breakdowns_draft_line_items__invoice_breakdown_id ON metronome.breakdowns_draft_line_items (invoice_breakdown_id);

# --- !Downs

DROP TABLE metronome.credit_type;
DROP TABLE metronome.customer;
DROP TABLE metronome.invoice;
DROP TABLE metronome.line_item;
DROP TABLE metronome.draft_invoice;
DROP TABLE metronome.draft_line_item;
DROP TABLE metronome.breakdowns_invoices;
DROP TABLE metronome.breakdowns_line_items;
DROP TABLE metronome.breakdowns_draft_invoices;
DROP TABLE metronome.breakdowns_draft_line_items;

DROP SCHEMA metronome;
