import { useState } from "react";
import { openInChromeHref, promptInstall, useInstall } from "../install";
import type { InstallState } from "../install";
import Link from "./Link";
import ShareSite from "./ShareSite";

/** /install: the link to send to group chats. Shows the one way to install that works on this device. */
export default function InstallPage() {
  const state = useInstall();
  return (
    <section className="card install" aria-labelledby="install-heading">
      <img className="install-icon" src="/icon-192.png" width={72} height={72} alt="" />
      <h1 id="install-heading">Get the VeriFact app</h1>
      <p className="install-lede">
        Free, no app store needed. Open it from your home screen like any app
        {state.platform === "android" ? ", and share posts straight to it from Facebook, TikTok or Messenger" : ""}.
      </p>
      <Instructions state={state} />
      <div className="install-foot">
        <p className="muted small">Know someone who should check before they share?</p>
        <ShareSite className="button button--secondary button--small" label="Send this page to a group chat" />
        <p className="muted small">
          Or just use it in your browser: <Link href="/check">check a post now</Link>.
        </p>
      </div>
    </section>
  );
}

function Instructions({ state }: { state: InstallState }) {
  const [result, setResult] = useState<"idle" | "installed" | "dismissed">("idle");

  if (state.installed || result === "installed") {
    return (
      <div className="install-box install-box--done" role="status">
        <p>
          <strong>VeriFact is installed.</strong> Open it from your home screen or app list.
        </p>
        <Link href="/check" className="button button--primary">
          Check a post
        </Link>
      </div>
    );
  }

  if (state.inApp) {
    return state.platform === "android" ? (
      <div className="install-box">
        <p>
          <strong>You&apos;re inside another app&apos;s browser</strong>, which can&apos;t install apps. Open this page in Chrome:
        </p>
        <a className="button button--primary" href={openInChromeHref("/install")}>
          Open in Chrome
        </a>
        <p className="muted small">
          If nothing happens, tap the <strong>⋮</strong> menu at the top right, then <strong>Open in browser</strong> (or{" "}
          <strong>Open in Chrome</strong>).
        </p>
      </div>
    ) : (
      <div className="install-box">
        <p>
          <strong>You&apos;re inside another app&apos;s browser</strong>, which can&apos;t add apps to your home screen. Open this
          page in Safari first:
        </p>
        <ol className="install-steps">
          <li>
            Tap the <strong>•••</strong> menu (or the compass or Share icon), usually at the bottom or top right.
          </li>
          <li>
            Choose <strong>Open in Safari</strong> (or <strong>Open in browser</strong>).
          </li>
          <li>Then follow the steps on this page in Safari.</li>
        </ol>
      </div>
    );
  }

  if (state.canPrompt && result !== "dismissed") {
    return (
      <div className="install-box">
        <button
          type="button"
          className="button button--primary install-button"
          onClick={() => void promptInstall().then((ok) => setResult(ok ? "installed" : "dismissed"))}
        >
          Install VeriFact
        </button>
        <p className="muted small">Your browser will ask you to confirm. It takes a few seconds.</p>
      </div>
    );
  }

  if (state.platform === "ios") {
    return (
      <div className="install-box">
        <ol className="install-steps">
          <li>
            Tap the <strong>Share</strong> button <ShareGlyph /> (at the bottom in Safari, or top right in Chrome).
          </li>
          <li>
            Scroll down and tap <strong>Add to Home Screen</strong>.
          </li>
          <li>
            Tap <strong>Add</strong>. VeriFact appears on your home screen.
          </li>
        </ol>
      </div>
    );
  }

  if (state.platform === "android") {
    return (
      <div className="install-box">
        <ol className="install-steps">
          <li>
            Tap the <strong>⋮</strong> menu at the top right of Chrome.
          </li>
          <li>
            Tap <strong>Install app</strong> (or <strong>Add to Home screen</strong>), then <strong>Install</strong>.
          </li>
        </ol>
        {result === "dismissed" && <p className="muted small">Changed your mind? You can install it any time from that menu.</p>}
      </div>
    );
  }

  return (
    <div className="install-box">
      <p>
        <strong>On a computer:</strong> in Chrome or Edge, click the install icon at the right end of the address bar, or open
        the browser menu and choose <strong>Install VeriFact</strong>.
      </p>
      <p className="muted small">On your phone, open this same page (verifact-ai.pages.dev/install) to get the app there.</p>
    </div>
  );
}

const ShareGlyph = () => (
  <svg
    className="install-glyph"
    width="16"
    height="16"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth="2"
    strokeLinecap="round"
    strokeLinejoin="round"
    role="img"
    aria-label="(a square with an arrow pointing up)"
  >
    <path d="M12 3v12M8 7l4-4 4 4" />
    <path d="M5 12v7a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-7" />
  </svg>
);
