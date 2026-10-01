// Apply a saved Light/Dark choice before first paint (see src/theme.ts).
try {
  var t = localStorage.getItem("verifact.theme");
  if (t === "light" || t === "dark") {
    document.documentElement.setAttribute("data-theme", t);
    document.querySelectorAll('meta[name="theme-color"]').forEach(function (m) {
      m.content = t === "dark" ? "#0a0f1d" : "#f4f6fb";
    });
  }
} catch {
  // Storage blocked: keep the system theme.
}
