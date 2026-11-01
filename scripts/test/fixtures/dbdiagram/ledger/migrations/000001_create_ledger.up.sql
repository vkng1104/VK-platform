CREATE TABLE accounts (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code TEXT NOT NULL
);

CREATE UNIQUE INDEX accounts_code_lower_idx ON accounts (lower(code));

CREATE TABLE entries (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES accounts (id),
    amount NUMERIC(12,2) NOT NULL,
    booked_on DATE NOT NULL,
    tags TEXT[] NOT NULL DEFAULT '{}'
);

CREATE INDEX entries_account_booked_idx ON entries (account_id, booked_on);
