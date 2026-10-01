// Writes dist/_headers for Cloudflare Pages: a strict Content-Security-Policy and other security headers.
// The API and Supabase origins come from the same build-time env as the app, so the policy matches the build.
import { writeFileSync } from "node:fs";

function origin(value) {
  if (!value) return null;
  try {
    return new URL(value).origin;
  } catch {
    throw new Error(`Not a URL: ${value}`);
  }
}

const connect = ["'self'", origin(process.env.VITE_API_BASE_URL), origin(process.env.VITE_SUPABASE_URL)].filter(Boolean);
const csp = [
  "default-src 'self'",
  "script-src 'self'",
  "style-src 'self' https://fonts.googleapis.com",
  "font-src 'self' https://fonts.gstatic.com",
  // YouTube thumbnails and frames for NewsFact supporting videos.
  "img-src 'self' data: https://i.ytimg.com",
  `connect-src ${connect.join(" ")}`,
  // The privacy-enhanced YouTube player (NewsFact "Play here").
  "frame-src https://www.youtube-nocookie.com",
  "object-src 'none'",
  "base-uri 'self'",
  "form-action 'self'",
  "frame-ancestors 'none'",
  "upgrade-insecure-requests",
].join("; ");

const headers = `/*
  Content-Security-Policy: ${csp}
  X-Content-Type-Options: nosniff
  Referrer-Policy: strict-origin-when-cross-origin
  Permissions-Policy: camera=(), microphone=(), geolocation=(), payment=()
  Cross-Origin-Opener-Policy: same-origin-allow-popups
`;
writeFileSync(new URL("../dist/_headers", import.meta.url), headers);
console.log("dist/_headers written; connect-src:", connect.join(" "));
