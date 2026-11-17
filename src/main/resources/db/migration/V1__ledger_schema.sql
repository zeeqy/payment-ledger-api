-- Double-entry ledger schema. Money is always BIGINT sen (1 MYR = 100 sen).
-- Sign convention: a positive posting increases that account's balance.
-- Every journal entry's postings must sum to zero (enforced at commit).

CREATE TABLE users (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email         text NOT NULL UNIQUE CHECK (email = lower(email)),
    password_hash text NOT NULL,
    role          text NOT NULL CHECK (role IN ('USER', 'MERCHANT', 'ADMIN')),
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE accounts (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id       uuid REFERENCES users (id),
    kind           text NOT NULL CHECK (kind IN ('WALLET', 'MERCHANT', 'SYSTEM')),
    code           text UNIQUE,                       -- only for SYSTEM accounts
    currency       char(3) NOT NULL DEFAULT 'MYR' CHECK (currency = 'MYR'),
    -- Cached balance, maintained ONLY by the trg_apply_posting trigger below.
    balance_sen    bigint NOT NULL DEFAULT 0,
    allow_negative boolean NOT NULL DEFAULT false,
    created_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_no_overdraft CHECK (allow_negative OR balance_sen >= 0)
);
CREATE UNIQUE INDEX ux_accounts_owner ON accounts (owner_id) WHERE owner_id IS NOT NULL;

CREATE TABLE merchants (
    user_id        uuid PRIMARY KEY REFERENCES users (id),
    account_id     uuid NOT NULL UNIQUE REFERENCES accounts (id),
    name           text NOT NULL,
    webhook_url    text,
    webhook_secret text NOT NULL
);

CREATE TABLE journal_entries (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    kind             text NOT NULL CHECK (kind IN ('TOPUP', 'TRANSFER', 'PAYMENT', 'REFUND', 'REVERSAL')),
    description      text,
    related_entry_id uuid REFERENCES journal_entries (id),  -- refund -> payment, reversal -> original
    initiated_by     uuid REFERENCES users (id),
    created_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_related CHECK ((kind IN ('REFUND', 'REVERSAL')) = (related_entry_id IS NOT NULL))
);
-- An entry can be reversed at most once.
CREATE UNIQUE INDEX ux_one_reversal ON journal_entries (related_entry_id) WHERE kind = 'REVERSAL';
CREATE INDEX ix_entries_related ON journal_entries (related_entry_id);

CREATE TABLE postings (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entry_id   uuid NOT NULL REFERENCES journal_entries (id),
    account_id uuid NOT NULL REFERENCES accounts (id),
    amount_sen bigint NOT NULL CHECK (amount_sen <> 0),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_postings_account ON postings (account_id, created_at);
CREATE INDEX ix_postings_entry ON postings (entry_id);

-- Balance is derived from postings: each new posting is applied to the account by the database itself.
CREATE FUNCTION apply_posting() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    UPDATE accounts SET balance_sen = balance_sen + NEW.amount_sen WHERE id = NEW.account_id;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_apply_posting AFTER INSERT ON postings
    FOR EACH ROW EXECUTE FUNCTION apply_posting();

-- Zero-sum rule, checked at COMMIT (deferred) so all postings of an entry can be inserted first.
CREATE FUNCTION check_entry_balanced() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    total bigint;
    n     int;
BEGIN
    SELECT coalesce(sum(amount_sen), 0), count(*) INTO total, n FROM postings WHERE entry_id = NEW.entry_id;
    IF total <> 0 OR n < 2 THEN
        RAISE EXCEPTION 'journal entry % is unbalanced (sum=%, postings=%)', NEW.entry_id, total, n
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER trg_entry_balanced AFTER INSERT ON postings
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_entry_balanced();

-- The ledger is append-only: corrections are new (reversal) entries, never edits.
CREATE FUNCTION forbid_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END $$;
CREATE TRIGGER trg_postings_append_only BEFORE UPDATE OR DELETE ON postings
    FOR EACH ROW EXECUTE FUNCTION forbid_change();
CREATE TRIGGER trg_entries_append_only BEFORE UPDATE OR DELETE ON journal_entries
    FOR EACH ROW EXECUTE FUNCTION forbid_change();

CREATE TABLE idempotency_keys (
    user_id       uuid NOT NULL REFERENCES users (id),
    idem_key      text NOT NULL,
    request_hash  text NOT NULL,
    status_code   int NOT NULL,
    response_body json NOT NULL,          -- json, not jsonb: replay must be byte-identical
    created_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, idem_key)
);

CREATE TABLE outbox_events (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    merchant_id     uuid NOT NULL REFERENCES merchants (user_id),
    event_type      text NOT NULL,
    payload         jsonb NOT NULL,
    status          text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'DELIVERED', 'DEAD')),
    attempts        int NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    last_error      text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    delivered_at    timestamptz
);
CREATE INDEX ix_outbox_due ON outbox_events (next_attempt_at) WHERE status = 'PENDING';

CREATE TABLE audit_log (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_id    uuid,
    action      text NOT NULL,
    entity_type text,
    entity_id   text,
    details     jsonb,
    ip          text,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_actor ON audit_log (actor_id, created_at);

-- Money enters the system from the (simulated) bank / FPX settlement account. It goes negative by design:
-- its balance is the mirror image of all e-money issued.
INSERT INTO accounts (kind, code, allow_negative) VALUES ('SYSTEM', 'SYS_FUNDING', true);
