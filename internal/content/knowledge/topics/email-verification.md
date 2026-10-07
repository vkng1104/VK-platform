## Verification is a reusable capability

Email verification answers a narrow question: did this visitor demonstrate short-lived control of a mailbox? It does not decide what the visitor may access. VK Platform keeps those responsibilities separate so the verification domain can support more than one restricted resource without learning the authorization rules of each consumer.

A challenge is bound to a declared purpose, destination, expiry, and attempt budget. The delivered code is never stored in plain text. Once a challenge succeeds, it cannot succeed again. Expired, incorrect, already-used, and attempt-exhausted challenges share a deliberately generic public failure so the API does not reveal unnecessary state.

## Abuse controls are part of the design

Sending email creates cost and reputation risk, so throttling is not an optional edge feature. The flow applies resend, destination, requester, and global limits before asking the provider to send a message. Public responses include a bounded retry signal when the caller should wait.

Provider credentials remain server-only, and the Gmail implementation sits behind a sender interface. The verification service coordinates a capability; it does not couple business rules to one delivery vendor.

## Authorization stays with the consumer

The CV feature starts and verifies a purpose-bound challenge through a trusted server action. Only after the backend confirms the expected purpose does the frontend server issue its own short-lived, signed CV-access cookie. That cookie contains no email address, verification code, or document URL.

This separation is important: mailbox control is evidence, not a universal login session. The consuming feature decides what that evidence authorizes, for how long, and how access is revoked.
