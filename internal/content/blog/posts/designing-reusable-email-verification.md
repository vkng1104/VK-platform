## The boundary matters more than the code generator

VK Platform needed a way to reveal a public Google Drive CV link only after a visitor proved control of an email address. The tempting implementation was a CV-specific endpoint that generated a code, sent an email, and returned access when the code matched.

That would have worked for one screen, but it would have mixed two distinct decisions:

1. **Verification:** has the visitor demonstrated short-lived control of this mailbox?
2. **Authorization:** what may this verified visitor do now?

I kept those decisions in separate features. The Go API owns a reusable email-verification capability. The Next.js CV feature consumes a successful, purpose-bound verification and decides whether to issue a short-lived CV-access session. The verification service never sees the Drive URL and cannot grant access to it directly.

## Model a challenge, not a code

A six-digit code is only one attribute of a verification challenge. The persisted challenge also needs an identifier, normalized destination, purpose, expiry, resend boundary, remaining attempt behavior, and terminal state.

The raw code is delivered once and stored only as a keyed digest. Verification checks the submitted code through the repository's atomic challenge operation, so two concurrent requests cannot both turn one challenge into two successful proofs. A successful or exhausted challenge cannot be replayed.

Purpose binding prevents a proof created for one workflow from silently authorizing another. VK Platform currently supports `restricted_resource_access`; future consumers can add explicit purposes without changing the meaning of existing challenges.

## Treat abuse limits as product behavior

Email delivery is an external side effect with cost and reputation consequences. Rate limiting therefore belongs in the initial design, not a later hardening pass.

The service applies limits at several scopes:

- a resend window for the immediate interaction;
- a destination limit to protect a mailbox from repeated messages;
- a requester limit to slow one source targeting many destinations; and
- a global limit to cap overall provider usage.

The API returns a stable rate-limit error and `Retry-After` guidance. The UI can tell the visitor when to try again without learning internal counters or whether a particular challenge still exists.

Failure responses are intentionally less specific during code verification. Missing, expired, incorrect, already-used, and attempt-exhausted challenges share one public result. The server logs retain the request identifier and internal cause for diagnosis, while the client receives only the information required to recover safely.

## Isolate delivery infrastructure

The application service depends on a small sender interface. Gmail is one implementation, configured with the narrow send-only OAuth scope and server-only credentials. Templates are embedded into the Go binary so deployment does not depend on runtime-mounted files.

This boundary keeps provider behavior out of the domain. Replacing Gmail with a transactional provider should change composition and infrastructure code, not challenge rules or HTTP contracts.

Provider failure also changes persisted state carefully. The system must not report a usable challenge when no message was sent, and it must not expose provider diagnostics in the public response. Those branches belong to service and repository tests at the layers that own them.

## Let the consuming feature authorize access

The browser does not call the verification API directly. Next.js server actions start and verify the challenge, validate the runtime response, and keep server-only configuration outside the client bundle.

After the API confirms the expected purpose, the CV feature issues a signed cookie with a short expiry. The cookie contains version and timing data plus a random nonce—never the email address, code, or Drive URL. The `/cv` route reveals the configured link only when that cookie validates. Invalid configuration or a malformed session fails closed.

This session is intentionally local to the resource. It does not pretend to be account authentication, and it does not make a public Drive link revocable after a visitor copies it. If per-viewer revocation becomes a requirement, the storage and delivery model must change rather than stretching email verification beyond what it proves.

## What this structure buys

The design creates a few durable seams:

- verification can serve another purpose without importing CV rules;
- the CV feature can replace its resource storage without changing challenge generation;
- the email provider can change without changing the application contract;
- persistence, service rules, HTTP behavior, and UI disclosure are tested independently; and
- public errors remain stable while internal causes stay diagnosable through request IDs.

The main lesson is simple: reusable security capabilities stay reusable when they produce narrowly defined evidence. Authorization remains an explicit decision at the feature that owns the protected action.
