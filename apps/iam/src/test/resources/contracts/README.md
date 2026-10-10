# Email verification v1 parity fixtures

These JSON responses freeze the public email-verification shapes previously served by the Go resource API. Java MVC tests render the same fixed challenge ID and timestamps and compare their responses with these fixtures so future IAM changes cannot silently break the existing CV client contract.
