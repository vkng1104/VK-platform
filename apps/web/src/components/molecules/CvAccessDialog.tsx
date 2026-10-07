"use client";

import { useActionState, useEffect, useRef, useState } from "react";

import {
  clearCvAccess,
  requestCvAccess,
  type CvAccessActionState,
} from "@/features/cv-access/actions";

const virusTotalUrl = "https://www.virustotal.com/gui/home/url";

export interface CvAccessDialogProps {
  cvUrl?: string;
}

function initialState(cvUrl: string | undefined): CvAccessActionState {
  return cvUrl ? { phase: "verified", cvUrl } : { phase: "email" };
}

export function CvAccessDialog({ cvUrl }: Readonly<CvAccessDialogProps>) {
  const [state, formAction, pending] = useActionState(
    requestCvAccess,
    initialState(cvUrl),
  );
  const [email, setEmail] = useState("");
  const [copyStatus, setCopyStatus] = useState<"idle" | "copied" | "failed">(
    "idle",
  );
  const [now, setNow] = useState<number | null>(null);
  const dialogRef = useRef<HTMLDialogElement>(null);
  const emailRef = useRef<HTMLInputElement>(null);
  const codeRef = useRef<HTMLInputElement>(null);
  const linkRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (!dialogRef.current?.open) {
      return;
    }

    if (state.phase === "email") {
      emailRef.current?.focus();
    } else if (state.phase === "code") {
      codeRef.current?.focus();
    } else {
      linkRef.current?.focus();
    }
  }, [state]);

  useEffect(() => {
    if (state.phase !== "code") {
      return;
    }

    const timeout = window.setTimeout(() => setNow(Date.now()), 0);
    const interval = window.setInterval(() => setNow(Date.now()), 1_000);
    return () => {
      window.clearTimeout(timeout);
      window.clearInterval(interval);
    };
  }, [state]);

  const resendSeconds =
    state.phase === "code" && now !== null
      ? Math.max(0, Math.ceil((Date.parse(state.resendAfter) - now) / 1_000))
      : state.phase === "code"
        ? 1
        : 0;

  function openDialog() {
    dialogRef.current?.showModal();
    if (state.phase === "email") {
      emailRef.current?.focus();
    } else if (state.phase === "code") {
      codeRef.current?.focus();
    } else {
      linkRef.current?.focus();
    }
  }

  function closeDialog() {
    dialogRef.current?.close();
    setCopyStatus("idle");
  }

  async function copyCvUrl() {
    if (state.phase !== "verified") {
      return;
    }

    try {
      await navigator.clipboard.writeText(state.cvUrl);
      setCopyStatus("copied");
    } catch {
      setCopyStatus("failed");
    }
  }

  return (
    <>
      <button
        className="rounded-full bg-sky-300 px-6 py-3 text-sm font-semibold text-slate-950 transition hover:bg-sky-200 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-sky-300"
        onClick={openDialog}
        type="button"
      >
        {state.phase === "verified" ? "View CV link" : "Get CV access"}
      </button>

      <dialog
        aria-describedby="cv-access-description"
        aria-labelledby="cv-access-title"
        className="cv-access-dialog m-auto w-[min(92vw,36rem)] rounded-3xl border border-white/15 bg-slate-900 p-0 text-left text-slate-100 shadow-2xl"
        onClose={() => setCopyStatus("idle")}
        ref={dialogRef}
      >
        <div className="p-6 sm:p-8">
          <div className="flex items-start justify-between gap-6">
            <div>
              <p className="font-mono text-xs uppercase tracking-[0.2em] text-sky-300">
                Verified access
              </p>
              <h2
                className="mt-3 text-2xl font-semibold tracking-tight text-white"
                id="cv-access-title"
              >
                {state.phase === "verified"
                  ? "Your CV link is ready"
                  : "Verify your email"}
              </h2>
            </div>
            <button
              aria-label="Close CV access dialog"
              className="rounded-full border border-white/15 px-3 py-1.5 text-sm text-slate-300 transition hover:border-white/30 hover:text-white focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sky-300"
              onClick={closeDialog}
              type="button"
            >
              Close
            </button>
          </div>

          <p className="mt-5 leading-7 text-slate-300" id="cv-access-description">
            {state.phase === "email" &&
              "Enter your email address. We will send a six-digit code to verify that you control the inbox."}
            {state.phase === "code" &&
              `Enter the code sent to ${state.maskedEmail}. The code expires after five minutes.`}
            {state.phase === "verified" &&
              "Email verification succeeded. You can copy or open the public Google Drive link below."}
          </p>

          {state.phase === "email" && (
            <form action={formAction} className="mt-7" noValidate>
              <input name="intent" type="hidden" value="start" />
              <label
                className="block text-sm font-medium text-slate-200"
                htmlFor="cv-access-email"
              >
                Email address
              </label>
              <input
                aria-describedby={state.error ? "cv-access-error" : undefined}
                aria-invalid={state.error ? true : undefined}
                autoComplete="email"
                className="mt-2 w-full rounded-2xl border border-white/15 bg-slate-950 px-4 py-3 text-white outline-none transition placeholder:text-slate-600 focus:border-sky-300 focus:ring-2 focus:ring-sky-300/20"
                id="cv-access-email"
                maxLength={254}
                name="email"
                onChange={(event) => setEmail(event.target.value)}
                placeholder="you@example.com"
                ref={emailRef}
                required
                type="email"
                value={email}
              />
              {state.error && (
                <p
                  className="mt-3 text-sm text-rose-300"
                  id="cv-access-error"
                  role="alert"
                >
                  {state.error}
                </p>
              )}
              <p className="mt-4 text-sm leading-6 text-slate-400">
                Verification proves inbox control only. Your address is not
                added to a contact list or stored as a profile.
              </p>
              <div className="mt-7 flex flex-wrap justify-end gap-3">
                <button
                  className="rounded-full border border-white/15 px-5 py-2.5 text-sm font-medium text-slate-200 transition hover:border-white/30 hover:text-white focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sky-300"
                  onClick={closeDialog}
                  type="button"
                >
                  Cancel
                </button>
                <button
                  className="rounded-full bg-sky-300 px-5 py-2.5 text-sm font-semibold text-slate-950 transition hover:bg-sky-200 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sky-300 disabled:cursor-wait disabled:opacity-60"
                  disabled={pending}
                  type="submit"
                >
                  {pending ? "Sending code…" : "Send code"}
                </button>
              </div>
            </form>
          )}

          {state.phase === "code" && (
            <div className="mt-7">
              <form action={formAction} noValidate>
                <input name="intent" type="hidden" value="verify" />
                <input
                  name="challenge_id"
                  type="hidden"
                  value={state.challengeId}
                />
                <label
                  className="block text-sm font-medium text-slate-200"
                  htmlFor="cv-access-code"
                >
                  Verification code
                </label>
                <input
                  aria-describedby={state.error ? "cv-access-error" : undefined}
                  aria-invalid={state.error ? true : undefined}
                  autoComplete="one-time-code"
                  className="mt-2 w-full rounded-2xl border border-white/15 bg-slate-950 px-4 py-3 font-mono text-lg tracking-[0.3em] text-white outline-none transition placeholder:tracking-normal placeholder:text-slate-600 focus:border-sky-300 focus:ring-2 focus:ring-sky-300/20"
                  id="cv-access-code"
                  inputMode="numeric"
                  maxLength={6}
                  name="code"
                  pattern="[0-9]{6}"
                  placeholder="123456"
                  ref={codeRef}
                  required
                  type="text"
                />
                {state.error && (
                  <p
                    className="mt-3 text-sm text-rose-300"
                    id="cv-access-error"
                    role="alert"
                  >
                    {state.error}
                  </p>
                )}
                <button
                  className="mt-6 w-full rounded-full bg-sky-300 px-5 py-2.5 text-sm font-semibold text-slate-950 transition hover:bg-sky-200 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sky-300 disabled:cursor-wait disabled:opacity-60"
                  disabled={pending}
                  type="submit"
                >
                  {pending ? "Verifying…" : "Verify and show link"}
                </button>
              </form>

              <div className="mt-5 flex flex-wrap items-center justify-between gap-3 border-t border-white/10 pt-5">
                <form action={formAction}>
                  <input name="intent" type="hidden" value="restart" />
                  <button
                    className="text-sm text-slate-400 underline decoration-white/20 underline-offset-4 hover:text-white focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sky-300"
                    disabled={pending}
                    type="submit"
                  >
                    Use another email
                  </button>
                </form>
                <form action={formAction}>
                  <input name="intent" type="hidden" value="resend" />
                  <input name="email" type="hidden" value={email} />
                  <button
                    className="text-sm text-sky-300 underline decoration-sky-300/30 underline-offset-4 hover:text-sky-200 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sky-300 disabled:cursor-not-allowed disabled:text-slate-600 disabled:no-underline"
                    disabled={pending || resendSeconds > 0 || !email}
                    type="submit"
                  >
                    {resendSeconds > 0
                      ? `Resend in ${resendSeconds}s`
                      : "Resend code"}
                  </button>
                </form>
              </div>
            </div>
          )}

          {state.phase === "verified" && (
            <div className="mt-7">
              <label
                className="block text-sm font-medium text-slate-200"
                htmlFor="cv-drive-link"
              >
                Google Drive CV link
              </label>
              <div className="mt-2 flex flex-col gap-3 sm:flex-row">
                <input
                  className="min-w-0 flex-1 rounded-2xl border border-white/15 bg-slate-950 px-4 py-3 text-sm text-slate-200 outline-none focus:border-sky-300 focus:ring-2 focus:ring-sky-300/20"
                  id="cv-drive-link"
                  onFocus={(event) => event.currentTarget.select()}
                  readOnly
                  ref={linkRef}
                  type="url"
                  value={state.cvUrl}
                />
                <button
                  className="rounded-full border border-sky-300/40 px-5 py-2.5 text-sm font-semibold text-sky-200 transition hover:border-sky-300 hover:text-white focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sky-300"
                  onClick={copyCvUrl}
                  type="button"
                >
                  Copy link
                </button>
              </div>
              <p aria-live="polite" className="mt-3 min-h-6 text-sm text-slate-400">
                {copyStatus === "copied" && "Link copied to your clipboard."}
                {copyStatus === "failed" &&
                  "Copy failed. Select the link and copy it manually."}
              </p>

              <div className="mt-5 flex flex-wrap gap-3">
                <a
                  className="rounded-full bg-sky-300 px-5 py-2.5 text-sm font-semibold text-slate-950 transition hover:bg-sky-200 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sky-300"
                  href={state.cvUrl}
                  rel="noreferrer"
                  target="_blank"
                >
                  Open CV
                </a>
                <form action={clearCvAccess}>
                  <button
                    className="rounded-full border border-white/15 px-5 py-2.5 text-sm font-medium text-slate-200 transition hover:border-white/30 hover:text-white focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sky-300"
                    type="submit"
                  >
                    Hide CV link
                  </button>
                </form>
              </div>

              <aside className="mt-7 rounded-2xl border border-amber-300/20 bg-amber-300/[0.06] p-4 text-sm leading-6 text-slate-300">
                Not sure this link is safe? Check it with{" "}
                <a
                  className="text-amber-200 underline decoration-amber-200/30 underline-offset-4 hover:text-amber-100 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-amber-200"
                  href={virusTotalUrl}
                  rel="noreferrer"
                  target="_blank"
                >
                  VirusTotal
                </a>{" "}
                before opening it. VirusTotal is an independent third-party
                service; checking is optional, and the CV link is not submitted
                automatically.
              </aside>
            </div>
          )}
        </div>
      </dialog>
    </>
  );
}
