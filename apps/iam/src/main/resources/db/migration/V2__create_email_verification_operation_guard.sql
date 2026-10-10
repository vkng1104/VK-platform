CREATE TABLE email_verification_operation_guards (
    id SMALLINT PRIMARY KEY,
    CONSTRAINT email_verification_operation_guards_singleton_check CHECK (id = 1)
);

INSERT INTO email_verification_operation_guards (id) VALUES (1);
