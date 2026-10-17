CREATE TABLE IF NOT EXISTS orders (
    invoice_id INTEGER PRIMARY KEY AUTOINCREMENT,
    idempotency_key TEXT NOT NULL UNIQUE,
    token_hash TEXT NOT NULL,
    store TEXT NOT NULL CHECK (store IN ('googlePlay', 'rustore', 'huawei')),
    mode TEXT NOT NULL CHECK (mode IN ('test', 'live')),
    merchant_login TEXT NOT NULL,
    amount TEXT NOT NULL CHECK (amount = '149.00'),
    currency TEXT NOT NULL CHECK (currency = 'RUB'),
    status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'paid', 'test_paid')),
    license_id TEXT NOT NULL UNIQUE,
    created_at INTEGER NOT NULL,
    paid_at INTEGER,
    last_state_check INTEGER,
    receipt TEXT
);

-- Pseudonymous order metadata only: no email, card, Google token or health records.
CREATE INDEX IF NOT EXISTS orders_status_created ON orders(status, created_at);
