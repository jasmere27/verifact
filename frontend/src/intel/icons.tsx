/** Small line icons for the workspace navigation (decorative; every link has a text label). */
const P = { width: 16, height: 16, viewBox: "0 0 24 24", fill: "none", stroke: "currentColor", strokeWidth: 1.8, strokeLinecap: "round", strokeLinejoin: "round", "aria-hidden": true } as const;

export const Icons = {
  overview: <svg {...P}><rect x="3" y="3" width="7" height="9" rx="1" /><rect x="14" y="3" width="7" height="5" rx="1" /><rect x="14" y="12" width="7" height="9" rx="1" /><rect x="3" y="16" width="7" height="5" rx="1" /></svg>,
  monitoring: <svg {...P}><path d="M3 12h4l3-8 4 16 3-8h4" /></svg>,
  incidents: <svg {...P}><path d="M12 3 2 20h20L12 3z" /><path d="M12 10v4M12 17h.01" /></svg>,
  investigations: <svg {...P}><circle cx="11" cy="11" r="7" /><path d="m20 20-3.5-3.5" /></svg>,
  evidence: <svg {...P}><path d="M4 4h12l4 4v12H4z" /><path d="M8 12h8M8 16h5" /></svg>,
  responses: <svg {...P}><path d="M4 5h16v11H8l-4 4z" /><path d="M8 9h8M8 12h5" /></svg>,
  reports: <svg {...P}><path d="M6 3h9l3 3v15H6z" /><path d="M9 17v-4M12 17v-7M15 17v-2" /></svg>,
  settings: <svg {...P}><circle cx="12" cy="12" r="3" /><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1A2 2 0 1 1 4.3 17l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1A2 2 0 1 1 7 4.3l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1A2 2 0 1 1 19.7 7l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z" /></svg>,
  billing: <svg {...P}><rect x="2" y="5" width="20" height="14" rx="2" /><path d="M2 10h20" /></svg>,
  menu: <svg {...P}><path d="M4 6h16M4 12h16M4 18h16" /></svg>,
};
