# IAM session and token lifecycle contract

## Protocol scope

This phase implements OAuth 2.0 Authorization Code with PKCE, refresh tokens, and client credentials. It is not an OpenID Connect provider: it does not publish OIDC discovery, issue ID tokens, or process `nonce`.

## Browser and BFF boundary

The same-origin Next.js BFF is the only caller of the grant-exchange endpoint. It authenticates to IAM with the registered BFF client's HTTP Basic credential, may forward the trusted browser `Origin`, and forwards IAM's `Secure`, `HttpOnly`, `SameSite=Lax` session cookie to the browser. Direct cross-site exchange requests are rejected. Browser JavaScript never receives the BFF client secret, access token, or refresh token.

For logout, the BFF sends the access token and forwards the IAM session cookie in one request. IAM revokes the durable session and refresh-token family, invalidates the authorization-server session when present, and always emits an expired IAM cookie. The BFF also clears any cookie it owns. Cancellation and upstream IAM failures must not create or retain a BFF authenticated session.

## Revocation semantics

Access-token revocation is bounded, not immediate. Account and session state changes immediately block refresh and new token issuance. A previously issued JWT remains locally verifiable until its expiry, which is capped at five minutes and by the absolute session expiry. Resource services do not query IAM's database. Token issuance locks the account and session before persisting authorization changes, so an account-state change or revocation serializes with issuance.

## Signing-key operations

Signing keys and OAuth clients are never silently bootstrapped during ordinary startup. A deployment operator sets `IAM_SESSION_OPERATOR_OPERATION`, `IAM_SESSION_OPERATOR_ACTOR`, and a unique `IAM_SESSION_OPERATOR_OPERATION_ID` for one explicit run. Supported operations are:

- `bootstrap-signing-key`
- `rotate-signing-key`
- `retire-signing-keys`
- `reencrypt-signing-keys`
- `compromise-signing-key`
- `reconcile-oauth-clients`
- `rotate-oauth-client-secret`
- `cleanup-session-data`

The key and client operation guards serialize concurrent operators. Readiness remains down until a usable active signing key exists and both required OAuth clients match non-secret configuration. The scheduled retirement job removes retiring keys from publication only after the access-token TTL plus the safety overlap.

JWT key selection uses one immutable active-signer snapshot for both the `kid` header and signing. Public verification and JWKS publication never decrypt private material. Parsed private material is cached only for the current persisted key version and is invalidated after lifecycle commits.

## Protection-key rotation

Each encrypted private signing key stores its non-secret protection-key version. `IAM_SIGNING_KEY_PROTECTION_KEYS` supplies a semicolon-delimited key ring such as `v1=<base64>;v2=<base64>`, and `IAM_SIGNING_KEY_PROTECTION_VERSION` selects the version used for new encryption. Rollout is:

1. Deploy the old and new protection keys with the old version still current.
2. Switch the current version to the new version while retaining both keys.
3. Run bounded `reencrypt-signing-keys` operations until they report zero changes.
4. Verify readiness and signing, then remove the old protection key in a later deployment.

Each batch locks the lifecycle guard, decrypts through the row's recorded version, re-encrypts with the current version, verifies the result before mutation, and commits atomically. A failed or interrupted batch can be retried without data loss. Production assurance may later replace this database adapter with a KMS/HSM signer.

## Compromised signing keys

`compromise-signing-key` immediately removes the affected key from fresh JWKS responses and, when it was active, creates a replacement active key in the same transaction. JWKS responses advertise a 30-second revalidation bound. Verifiers must refresh once for an unknown `kid`, fail closed if it remains unresolved or IAM is unavailable, and never fall back to accepting an unverified token. A verifier with a previously cached compromised public key can still accept a forged token until cache revalidation; the hard upper bound remains the five-minute access-token lifetime. This phase deliberately accepts that bounded exposure instead of adding introspection or a distributed deny list.

## Retention

Cleanup runs in restartable bounded batches. It expires active sessions first, invalidates their authorizations, deletes refresh-token lineages before authorizations, deletes terminal sessions only when no authorization references remain, then removes expired audit rows and retired signing keys. Session and authorization data default to 30 days of retention; security audit data defaults to 365 days.
