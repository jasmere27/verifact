import { useEffect, useId, useState, type FormEvent, type InputHTMLAttributes, type ReactNode } from "react";
import Link from "../components/Link";
import { navigate } from "../router";
import { useAuth } from "./useAuth";
import { auth, authErrorMessage, callbackUrl, enabledProviders, MIN_PASSWORD, rememberNext, resetUrl, safeNext, takeNext } from "./client";

function nextFromUrl(): string {
  return safeNext(new URLSearchParams(window.location.search).get("next"));
}

/** An error Supabase put in the redirect URL (expired or already-used link). */
function urlError(): string | null {
  const params = new URLSearchParams(window.location.search);
  const hash = new URLSearchParams(window.location.hash.replace(/^#/, ""));
  const description = params.get("error_description") ?? hash.get("error_description");
  if (!description) return null;
  return /expired|invalid/i.test(description)
    ? "This link has expired or was already used. Links work once, in the browser where you asked for them."
    : "We couldn't complete that request. Please try again.";
}

export function AuthCard({ title, lede, children }: { title: string; lede?: ReactNode; children: ReactNode }) {
  return (
    <section className="auth-page" aria-labelledby="auth-heading">
      <div className="card auth-card">
        <h1 id="auth-heading" className="auth-title">
          {title}
        </h1>
        {lede && <p className="auth-lede">{lede}</p>}
        {children}
      </div>
    </section>
  );
}

export function Field({ label, hint, ...input }: { label: string; hint?: string } & InputHTMLAttributes<HTMLInputElement>) {
  const id = useId();
  return (
    <div className="field">
      <label htmlFor={id} className="field-label">
        {label}
      </label>
      <input id={id} className="field-input" aria-describedby={hint ? `${id}-hint` : undefined} {...input} />
      {hint && (
        <p id={`${id}-hint`} className="field-hint">
          {hint}
        </p>
      )}
    </div>
  );
}

function ErrorText({ message }: { message: string | null }) {
  return message ? (
    <p className="alert auth-alert" role="alert">
      {message}
    </p>
  ) : null;
}

function GoogleButton({ next }: { next: string }) {
  const [google, setGoogle] = useState(false);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    let active = true;
    void enabledProviders().then((p) => active && setGoogle(p.google));
    return () => {
      active = false;
    };
  }, []);
  if (!google || !auth) return null;
  async function start() {
    rememberNext(next);
    const { error: e } = await auth!.signInWithOAuth({ provider: "google", options: { redirectTo: callbackUrl() } });
    if (e) setError(authErrorMessage(e));
  }
  return (
    <>
      <button type="button" className="button button--secondary auth-google" onClick={() => void start()}>
        <svg aria-hidden="true" viewBox="0 0 18 18" width="18" height="18">
          <path fill="#4285F4" d="M17.64 9.2c0-.64-.06-1.25-.16-1.84H9v3.48h4.84a4.14 4.14 0 0 1-1.8 2.72v2.26h2.92a8.78 8.78 0 0 0 2.68-6.62z" />
          <path fill="#34A853" d="M9 18c2.43 0 4.47-.8 5.96-2.18l-2.92-2.26c-.8.54-1.84.86-3.04.86-2.34 0-4.32-1.58-5.03-3.7H.96v2.33A9 9 0 0 0 9 18z" />
          <path fill="#FBBC05" d="M3.97 10.72A5.41 5.41 0 0 1 3.68 9c0-.6.1-1.18.29-1.72V4.95H.96A9 9 0 0 0 0 9c0 1.45.35 2.83.96 4.05l3.01-2.33z" />
          <path fill="#EA4335" d="M9 3.58c1.32 0 2.5.45 3.44 1.35l2.58-2.58A8.97 8.97 0 0 0 9 0 9 9 0 0 0 .96 4.95l3.01 2.33C4.68 5.16 6.66 3.58 9 3.58z" />
        </svg>
        Continue with Google
      </button>
      <ErrorText message={error} />
      <p className="auth-divider">
        <span>or with email</span>
      </p>
    </>
  );
}

export function SignInPage() {
  const { session, ready } = useAuth();
  const next = nextFromUrl();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [unconfirmed, setUnconfirmed] = useState(false);
  const [resent, setResent] = useState(false);

  useEffect(() => {
    if (ready && session) navigate(next, { replace: true });
  }, [ready, session, next]);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!auth) return;
    setBusy(true);
    setError(null);
    setUnconfirmed(false);
    const { error: e } = await auth.signInWithPassword({ email: email.trim(), password });
    setBusy(false);
    if (e) {
      setUnconfirmed(e.code === "email_not_confirmed");
      setError(authErrorMessage(e));
      return;
    }
    navigate(next, { replace: true });
  }

  async function resend() {
    if (!auth) return;
    rememberNext(next);
    await auth.resend({ type: "signup", email: email.trim(), options: { emailRedirectTo: callbackUrl() } });
    setResent(true);
  }

  return (
    <AuthCard title="Sign in" lede="One account for VeriFact, NewsFact, LegalFact and ResearchFact.">
      <GoogleButton next={next} />
      <form className="auth-form" onSubmit={(e) => void submit(e)} noValidate={false}>
        <Field label="Email" type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
        <Field
          label="Password"
          type="password"
          autoComplete="current-password"
          required
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />
        <ErrorText message={error} />
        {unconfirmed && (
          <p className="auth-note" role="status">
            {resent ? (
              "If that email still needs confirming, we've sent a new link."
            ) : (
              <button type="button" className="text-button" onClick={() => void resend()}>
                Send the confirmation email again
              </button>
            )}
          </p>
        )}
        <button type="submit" className="button button--primary auth-submit" disabled={busy}>
          {busy ? "Signing in…" : "Sign in"}
        </button>
      </form>
      <p className="auth-links">
        <Link href="/forgot-password">Forgot your password?</Link>
        <span>
          New here? <Link href={`/signup${next !== "/account" ? `?next=${encodeURIComponent(next)}` : ""}`}>Create an account</Link>
        </span>
      </p>
    </AuthCard>
  );
}

export function SignUpPage() {
  const { session, ready } = useAuth();
  const next = nextFromUrl();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [sentTo, setSentTo] = useState<string | null>(null);

  useEffect(() => {
    if (ready && session) navigate(next, { replace: true });
  }, [ready, session, next]);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!auth) return;
    if (password.length < MIN_PASSWORD) {
      setError(`Use at least ${MIN_PASSWORD} characters for your password.`);
      return;
    }
    setBusy(true);
    setError(null);
    rememberNext(next);
    const { data, error: e } = await auth.signUp({
      email: email.trim(),
      password,
      options: { emailRedirectTo: callbackUrl() },
    });
    setBusy(false);
    if (e) {
      setError(authErrorMessage(e));
      return;
    }
    if (data.session) {
      navigate(next, { replace: true });
      return;
    }
    // Same answer whether or not the email already has an account.
    setSentTo(email.trim());
  }

  if (sentTo) {
    return (
      <AuthCard title="Check your inbox">
        <div className="auth-notice" role="status">
          <p>
            We sent a confirmation link to <strong>{sentTo}</strong>. Open it in this browser to finish creating your account.
          </p>
          <p className="muted small">
            Nothing there after a few minutes? Check spam. If you already have an account with this email, <Link href="/signin">sign in</Link>{" "}
            or <Link href="/forgot-password">reset your password</Link> instead.
          </p>
        </div>
      </AuthCard>
    );
  }

  return (
    <AuthCard title="Create your account" lede="Free. One account for VeriFact, NewsFact, LegalFact and ResearchFact.">
      <GoogleButton next={next} />
      <form className="auth-form" onSubmit={(e) => void submit(e)}>
        <Field label="Email" type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
        <Field
          label="Password"
          type="password"
          autoComplete="new-password"
          required
          minLength={MIN_PASSWORD}
          hint={`At least ${MIN_PASSWORD} characters. A short phrase you don't use elsewhere works well.`}
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />
        <ErrorText message={error} />
        <button type="submit" className="button button--primary auth-submit" disabled={busy}>
          {busy ? "Creating your account…" : "Create account"}
        </button>
      </form>
      <p className="auth-links">
        <span>
          Already have an account? <Link href={`/signin${next !== "/account" ? `?next=${encodeURIComponent(next)}` : ""}`}>Sign in</Link>
        </span>
      </p>
    </AuthCard>
  );
}

export function ForgotPasswordPage() {
  const [email, setEmail] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [sentTo, setSentTo] = useState<string | null>(null);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!auth) return;
    setBusy(true);
    setError(null);
    const { error: e } = await auth.resetPasswordForEmail(email.trim(), { redirectTo: resetUrl() });
    setBusy(false);
    // Only rate limits are shown; anything else gets the same answer, so emails can't be probed.
    if (e && (e.code === "over_email_send_rate_limit" || e.code === "over_request_rate_limit")) {
      setError(authErrorMessage(e));
      return;
    }
    setSentTo(email.trim());
  }

  if (sentTo) {
    return (
      <AuthCard title="Check your inbox">
        <div className="auth-notice" role="status">
          <p>
            If <strong>{sentTo}</strong> has an account, we&apos;ve sent a link to set a new password. Open it in this browser; it works once.
          </p>
          <p className="muted small">
            Signed up with Google? Use <Link href="/signin">Continue with Google</Link> instead.
          </p>
        </div>
      </AuthCard>
    );
  }

  return (
    <AuthCard title="Reset your password" lede="Enter your account's email and we'll send you a link to choose a new password.">
      <form className="auth-form" onSubmit={(e) => void submit(e)}>
        <Field label="Email" type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
        <ErrorText message={error} />
        <button type="submit" className="button button--primary auth-submit" disabled={busy}>
          {busy ? "Sending…" : "Send reset link"}
        </button>
      </form>
      <p className="auth-links">
        <Link href="/signin">Back to sign in</Link>
      </p>
    </AuthCard>
  );
}

export function ResetPasswordPage() {
  const { session, ready } = useAuth();
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(urlError());
  const [done, setDone] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!auth) return;
    if (password.length < MIN_PASSWORD) {
      setError(`Use at least ${MIN_PASSWORD} characters for your password.`);
      return;
    }
    setBusy(true);
    setError(null);
    const { error: e } = await auth.updateUser({ password });
    setBusy(false);
    if (e) {
      setError(authErrorMessage(e));
      return;
    }
    setDone(true);
  }

  if (!ready) return <AuthCard title="Reset your password" lede="Checking your link…">{null}</AuthCard>;
  if (done) {
    return (
      <AuthCard title="Password changed">
        <p className="auth-notice" role="status">
          Your new password is set and you&apos;re signed in.
        </p>
        <Link href="/account" className="button button--primary">
          Go to your account
        </Link>
      </AuthCard>
    );
  }
  if (!session) {
    return (
      <AuthCard title="Reset your password">
        <ErrorText message={error ?? "This reset link has expired, was already used, or was opened in a different browser."} />
        <Link href="/forgot-password" className="button button--primary">
          Send a new link
        </Link>
      </AuthCard>
    );
  }
  return (
    <AuthCard title="Choose a new password">
      <form className="auth-form" onSubmit={(e) => void submit(e)}>
        <Field
          label="New password"
          type="password"
          autoComplete="new-password"
          required
          minLength={MIN_PASSWORD}
          hint={`At least ${MIN_PASSWORD} characters.`}
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />
        <ErrorText message={error} />
        <button type="submit" className="button button--primary auth-submit" disabled={busy}>
          {busy ? "Saving…" : "Save new password"}
        </button>
      </form>
    </AuthCard>
  );
}

/** Where Google and the confirmation email send people back; the client has already exchanged the code. */
export function AuthCallbackPage() {
  const { session, ready } = useAuth();
  const [error] = useState(urlError);

  useEffect(() => {
    if (ready && session) navigate(takeNext(), { replace: true });
  }, [ready, session]);

  if (!ready || (session && !error)) return <AuthCard title="Signing you in…">{null}</AuthCard>;
  return (
    <AuthCard title="Almost there">
      <ErrorText message={error} />
      <p className="auth-notice">
        If you were confirming your email, it may already be confirmed: sign in to continue. Links work in the browser where you
        signed up.
      </p>
      <Link href="/signin" className="button button--primary">
        Sign in
      </Link>
    </AuthCard>
  );
}
