import { useState } from "react";
import { formatDate } from "../format";
import type { SupportingVideo, VideoKind, VideoStance } from "./types";

const STANCE: Record<VideoStance, { label: string; tone: string }> = {
  SUPPORTS: { label: "Supports the claim", tone: "news-flag--good" },
  CONTRADICTS: { label: "Contradicts the claim", tone: "news-flag--bad" },
  CONTEXT: { label: "Context", tone: "" },
};

const KIND: Record<VideoKind, string> = {
  NEWS_REPORT: "News report",
  OFFICIAL: "Official",
  EYEWITNESS: "Eyewitness / public",
  OTHER: "Other",
};

function clock(seconds: number): string {
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = seconds % 60;
  return h > 0 ? `${h}:${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}` : `${m}:${String(s).padStart(2, "0")}`;
}

/** Only YouTube watch links we built ourselves are linked (ids are validated server-side). */
function at(url: string, seconds: number): string {
  return `${url}&t=${seconds}s`;
}

const YOUTUBE_ID = /^[A-Za-z0-9_-]{11}$/;

/** YouTube's privacy-enhanced player; nothing loads from YouTube until the viewer presses play. */
function embedUrl(videoId: string, start: number): string {
  return `https://www.youtube-nocookie.com/embed/${videoId}?autoplay=1&rel=0&start=${Math.max(0, Math.floor(start))}`;
}

function VideoCard({ v }: { v: SupportingVideo }) {
  // Start time of the open player, or null when closed.
  const [playFrom, setPlayFrom] = useState<number | null>(null);
  // Unknown (checks saved before this was recorded) still gets the player; YouTube explains if it's blocked.
  const embeddable = v.platform === "youtube" && v.embeddable !== false && YOUTUBE_ID.test(v.videoId);
  return (
    <li className={`news-video${playFrom != null ? " news-video--playing" : ""}`}>
      {playFrom != null ? (
        <div className="news-video-player">
          <iframe
            key={playFrom}
            src={embedUrl(v.videoId, playFrom)}
            title={v.title}
            allow="autoplay; encrypted-media; picture-in-picture; fullscreen"
            allowFullScreen
            referrerPolicy="strict-origin-when-cross-origin"
          />
        </div>
      ) : embeddable ? (
        <button type="button" className="news-video-thumb" onClick={() => setPlayFrom(0)} aria-label={`Play “${v.title}” here`}>
          <img src={v.thumbnailUrl} alt="" loading="lazy" referrerPolicy="no-referrer" />
          <span className="news-video-play" aria-hidden="true">▶</span>
          {v.durationSeconds != null && v.durationSeconds > 0 && <span className="news-video-duration">{clock(v.durationSeconds)}</span>}
        </button>
      ) : (
        <a className="news-video-thumb" href={v.url} target="_blank" rel="noopener noreferrer nofollow" aria-label={`Watch “${v.title}” on YouTube`}>
          <img src={v.thumbnailUrl} alt="" loading="lazy" referrerPolicy="no-referrer" />
          {v.durationSeconds != null && v.durationSeconds > 0 && <span className="news-video-duration">{clock(v.durationSeconds)}</span>}
        </a>
      )}
      <div className="news-video-body">
        <div className="news-flags">
          <span className={`news-flag ${STANCE[v.stance].tone}`}>{STANCE[v.stance].label}</span>
          <span className="news-flag" title="Based on the channel name and description">
            {KIND[v.kind]}
          </span>
          {v.earliestFound && (
            <span className="news-flag news-flag--warn" title="The oldest upload among these results, not proof it's the original">
              Earliest upload found
            </span>
          )}
        </div>
        <p className="news-video-title">
          <a href={v.url} target="_blank" rel="noopener noreferrer nofollow">
            {v.title}
          </a>
        </p>
        <p className="muted small">
          {v.channel}
          {v.publishedAt ? ` · uploaded ${formatDate(v.publishedAt)}` : ""}
        </p>
        <p className="small">{v.why}</p>
        <p className="news-source-words">Description: “{v.quote}”</p>
        {v.claimsMade.length > 0 && (
          <div className="small">
            <span className="news-excerpt-label">Claims in the title/description</span>
            <ul className="news-video-claims">
              {v.claimsMade.map((c) => (
                <li key={c}>“{c}”</li>
              ))}
            </ul>
          </div>
        )}
        {v.relevantAt && (
          <p className="small">
            Relevant part:{" "}
            {embeddable ? (
              <button type="button" className="link-button" onClick={() => setPlayFrom(v.relevantAt!.seconds)}>
                ▶ {clock(v.relevantAt.seconds)} {v.relevantAt.label}
              </button>
            ) : (
              <a href={at(v.url, v.relevantAt.seconds)} target="_blank" rel="noopener noreferrer nofollow">
                {clock(v.relevantAt.seconds)} {v.relevantAt.label}
              </a>
            )}{" "}
            <span className="muted">(from the video&apos;s chapter list)</span>
          </p>
        )}
        {v.keyFrames.length > 0 && (
          <div className="news-video-frames" aria-label="Frames from the video">
            {v.keyFrames.map((f) => (
              <img key={f} src={f} alt="" loading="lazy" referrerPolicy="no-referrer" />
            ))}
            <span className="muted small">YouTube&apos;s automatic frames</span>
          </div>
        )}
        <p className="muted small">Transcript: not available (YouTube only lets a video&apos;s owner download captions).</p>
        <div className="news-video-actions">
          {embeddable &&
            (playFrom == null ? (
              <button type="button" className="button button--small" onClick={() => setPlayFrom(0)}>
                ▶ Play here
              </button>
            ) : (
              <button type="button" className="button button--secondary button--small" onClick={() => setPlayFrom(null)}>
                Close player
              </button>
            ))}
          <a className="button button--secondary button--small news-video-watch" href={v.url} target="_blank" rel="noopener noreferrer nofollow">
            Watch on YouTube ↗
          </a>
        </div>
      </div>
    </li>
  );
}

export default function SupportingVideos({ videos, query }: { videos: SupportingVideo[]; query: string }) {
  return (
    <details className="news-videos" open={videos.length > 0}>
      <summary>
        Supporting videos <span className="muted small">({videos.length === 0 ? "none relevant found" : videos.length})</span>
      </summary>
      {videos.length === 0 ? (
        <p className="muted small">No relevant video found for “{query}”.</p>
      ) : (
        <ul className="news-video-list">
          {videos.map((v) => (
            <VideoCard key={v.videoId} v={v} />
          ))}
        </ul>
      )}
    </details>
  );
}
