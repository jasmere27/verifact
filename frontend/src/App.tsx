import { useState } from "react";
import CheckPanel from "./components/CheckPanel";
import HistoryPanel from "./components/HistoryPanel";
import "./App.css";

type View = "check" | "history";

function App() {
  const [view, setView] = useState<View>("check");

  return (
    <div className="app-shell">
      <header className="app-header">
        <h1>VeriFact</h1>
        <p className="tagline">AI-powered fact-checking for text, URLs, images, and audio.</p>
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
            className={view === "history" ? "view-tab active" : "view-tab"}
            onClick={() => setView("history")}
          >
            History
          </button>
        </nav>
      </header>

      <main>{view === "check" ? <CheckPanel /> : <HistoryPanel />}</main>
    </div>
  );
}

export default App;
