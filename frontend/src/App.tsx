import { useState } from "react";
import CheckPanel from "./components/CheckPanel";
import HistoryPanel from "./components/HistoryPanel";
import "./App.css";

type View = "check" | "log";

function App() {
  const [view, setView] = useState<View>("check");

  return (
    <div className="app-shell">
      <header className="app-header">
        <div className="brand-plate">
          <span className="brand-mark">VF</span>
          <div>
            <span className="eyebrow">Verification instrument</span>
            <h1>VeriFact</h1>
          </div>
        </div>
        <p className="tagline">
          Feed it a claim. <em>Watch the needle move.</em>
        </p>
        <nav className="view-tabs">
          <button
            type="button"
            className={view === "check" ? "view-tab active" : "view-tab"}
            onClick={() => setView("check")}
          >
            Check
          </button>
          <button
            type="button"
            className={view === "log" ? "view-tab active" : "view-tab"}
            onClick={() => setView("log")}
          >
            Log
          </button>
        </nav>
      </header>

      <main>{view === "check" ? <CheckPanel /> : <HistoryPanel />}</main>

      <footer className="app-footer">
        Reads text, links, images, and audio against BBC, Reuters, AP, and other credible sources.
      </footer>
    </div>
  );
}

export default App;
