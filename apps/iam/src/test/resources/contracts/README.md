# Email verification v1 parity fixtures

These JSON responses freeze the public email-verification shapes previously served by the Go resource API. Java MVC tests render the same fixed challenge ID and timestamps and compare their responses with these fixtures so future IAM changes cannot silently break the existing CV client contract.

The authentication fixtures freeze the passwordless sign-in challenge and one-time grant envelopes introduced by IAM Phase 3. They use fake identifiers and grant material only.
