import { useState } from "react";

const SHARE = {
  title: "VeriFact",
  text: "Saw something viral? Check it first: VeriFact shows what fact-checkers, news and reference sources say about a post, link or screenshot.",
};

/**
 * "Share VeriFact" for group chats: the phone's share sheet (Messenger, Viber, Telegram…) where supported,
 * otherwise copies the link. The preview card comes from the homepage's Open Graph tags (index.html).
 */
export default function ShareSite({ className = "text-button", label = "Share VeriFact" }: { className?: string; label?: string }) {
  const [status, setStatus] = useState<"idle" | "copied" | "failed">("idle");
  const url = `${window.location.origin}/`;

  async function share() {
    const data = { ...SHARE, url };
    if (typeof navigator.share === "function" && (!navigator.canShare || navigator.canShare(data))) {
      try {
        await navigator.share(data);
        return;
      } catch (err) {
        if (err instanceof DOMException && err.name === "AbortError") return; // closed the share sheet
      }
    }
    try {
      await navigator.clipboard.writeText(`${SHARE.text} ${url}`);
      setStatus("copied");
    } catch {
      setStatus("failed");
    }
    setTimeout(() => setStatus("idle"), 2500);
  }

  return (
    <>
      <button type="button" className={className} onClick={() => void share()}>
        {status === "copied" ? "Link copied" : status === "failed" ? url : label}
      </button>
      <span className="visually-hidden" aria-live="polite">
        {status === "copied" ? "Link to VeriFact copied. Paste it in your group chat." : ""}
      </span>
    </>
  );
}
