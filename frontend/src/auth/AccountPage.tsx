import { useState, type FormEvent } from "react";
import Link from "../components/Link";
import { navigate } from "../router";
import { deleteAccount, updateDisplayName, type Role } from "./api";
import { AuthCard, Field } from "./AuthPages";
import { shownName, useAuth } from "./useAuth";
import { auth, authErrorMessage, MIN_PASSWORD } from "./client";

const ROLE_LABEL: Record<Role, string> = { OWNER: "Owner", ADMIN: "Admin", MEMBER: "Member" };

type Status = { kind: "ok" | "error"; text: string } | null;

function StatusText({ status }: { status: Status }) {
  if (!status) return null;
  return (
    <p className={status.kind === "ok" ? "auth-saved" : "alert auth-alert"} role={status.kind === "ok" ? "status" : "alert"}>
      {status.text}
    </p>
  );
}

export function AccountPage() {
  const { session, ready, account, accountError, setAccount, reloadAccount } = useAuth();
  const [deleted, setDeleted] = useState(false);

  if (deleted) {
    return (
      <AuthCard title="Your account was deleted">
        <p className="auth-notice" role="status">
          Your account and personal workspace are gone. You can keep using VeriFact without an account.
        </p>
        <Link href="/" className="button button--primary">
          Go to the homepage
        </Link>
      </AuthCard>
    );
  }
  if (!ready) return <AuthCard title="Your account">{null}</AuthCard>;
  if (!session) {
    return (
      <AuthCard title="Your account" lede="Sign in to see your account.">
        <Link href="/signin?next=%2Faccount" className="button button--primary">
          Sign in
        </Link>
      </AuthCard>
    );
  }

  const email = account?.email ?? session.user.email ?? "";
  const usesPassword = (session.user.identities ?? []).some((i) => i.provider === "email") || session.user.app_metadata.provider === "email";

  return (
    <section className="account-page" aria-labelledby="account-heading">
      <header className="account-head">
        <span className="account-avatar account-avatar--large" aria-hidden="true">
          {shownName(account, session).charAt(0).toUpperCase()}
        </span>
        <div>
          <h1 id="account-heading" className="auth-title">
            {shownName(account, session)}
          </h1>
          <p className="muted">{email}</p>
        </div>
      </header>

      {accountError && (
        <div className="alert" role="alert">
          <p>{accountError}</p>
          <button type="button" className="text-button" onClick={() => void reloadAccount()}>
            Try again
          </button>
        </div>
      )}

      <ProfileSection
        key={account?.displayName ?? ""}
        initial={account?.displayName ?? ""}
        onSave={async (name) => setAccount(await updateDisplayName(name))}
      />

      <div className="card account-section">
        <h2 className="account-section-title">Workspaces</h2>
        <p className="muted small">Your work across VeriFact, NewsFact, LegalFact and ResearchFact belongs to a workspace. Teams come later.</p>
        <ul className="account-orgs">
          {(account?.organizations ?? []).map((o) => (
            <li key={o.id}>
              <span>{o.name}</span>
              <span className="account-role">{o.personal ? "Personal · " : ""}{ROLE_LABEL[o.role]}</span>
            </li>
          ))}
          {!account && !accountError && <li className="muted">Loading…</li>}
        </ul>
      </div>

      {usesPassword && <PasswordSection />}

      <div className="card account-section">
        <h2 className="account-section-title">Sessions</h2>
        <div className="form-actions">
          <button type="button" className="button button--secondary button--small" onClick={() => void signOut("local")}>
            Sign out
          </button>
          <button type="button" className="button button--quiet button--small" onClick={() => void signOut("global")}>
            Sign out on all devices
          </button>
        </div>
      </div>

      <DeleteSection email={email} onDeleted={() => setDeleted(true)} />
    </section>
  );
}

async function signOut(scope: "local" | "global") {
  await auth?.signOut({ scope });
  navigate("/", { replace: true });
}

function ProfileSection({ initial, onSave }: { initial: string; onSave: (name: string) => Promise<void> }) {
  const [name, setName] = useState(initial);
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState<Status>(null);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setStatus(null);
    try {
      await onSave(name);
      setStatus({ kind: "ok", text: "Saved." });
    } catch (e) {
      setStatus({ kind: "error", text: e instanceof Error ? e.message : "We couldn't save that." });
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="card account-section auth-form" onSubmit={(e) => void submit(e)}>
      <h2 className="account-section-title">Profile</h2>
      <Field
        label="Display name"
        autoComplete="name"
        maxLength={80}
        hint="Shown on your work. Leave empty to use your email."
        value={name}
        onChange={(e) => setName(e.target.value)}
      />
      <StatusText status={status} />
      <div className="form-actions">
        <button type="submit" className="button button--primary button--small" disabled={busy}>
          {busy ? "Saving…" : "Save"}
        </button>
      </div>
    </form>
  );
}

function PasswordSection() {
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState<Status>(null);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!auth) return;
    if (password.length < MIN_PASSWORD) {
      setStatus({ kind: "error", text: `Use at least ${MIN_PASSWORD} characters.` });
      return;
    }
    setBusy(true);
    setStatus(null);
    const { error } = await auth.updateUser({ password });
    setBusy(false);
    if (error) {
      setStatus({
        kind: "error",
        text:
          error.code === "reauthentication_needed"
            ? "For security, sign out and use “Forgot your password?” to choose a new one."
            : authErrorMessage(error),
      });
      return;
    }
    setPassword("");
    setStatus({ kind: "ok", text: "Password changed." });
  }

  return (
    <form className="card account-section auth-form" onSubmit={(e) => void submit(e)}>
      <h2 className="account-section-title">Password</h2>
      <Field
        label="New password"
        type="password"
        autoComplete="new-password"
        minLength={MIN_PASSWORD}
        hint={`At least ${MIN_PASSWORD} characters.`}
        value={password}
        onChange={(e) => setPassword(e.target.value)}
      />
      <StatusText status={status} />
      <div className="form-actions">
        <button type="submit" className="button button--secondary button--small" disabled={busy || password === ""}>
          {busy ? "Saving…" : "Change password"}
        </button>
      </div>
    </form>
  );
}

function DeleteSection({ email, onDeleted }: { email: string; onDeleted: () => void }) {
  const [confirm, setConfirm] = useState("");
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState<Status>(null);
  const matches = confirm.trim().toLowerCase() === email.toLowerCase() && email !== "";

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!matches) return;
    setBusy(true);
    setStatus(null);
    try {
      await deleteAccount();
      onDeleted();
      // The user no longer exists, so only this browser's session needs clearing.
      await auth?.signOut({ scope: "local" });
    } catch (e) {
      setStatus({ kind: "error", text: e instanceof Error ? e.message : "We couldn't delete your account." });
      setBusy(false);
    }
  }

  return (
    <form className="card account-section account-danger auth-form" onSubmit={(e) => void submit(e)}>
      <h2 className="account-section-title">Delete account</h2>
      <p className="small">
        This permanently deletes your account and personal workspace. Shared links you created keep working for whoever has them.
        It can&apos;t be undone.
      </p>
      <Field
        label={`Type ${email} to confirm`}
        type="email"
        autoComplete="off"
        value={confirm}
        onChange={(e) => setConfirm(e.target.value)}
      />
      <StatusText status={status} />
      <div className="form-actions">
        <button type="submit" className="button button--danger button--small" disabled={!matches || busy}>
          {busy ? "Deleting…" : "Delete my account"}
        </button>
      </div>
    </form>
  );
}
