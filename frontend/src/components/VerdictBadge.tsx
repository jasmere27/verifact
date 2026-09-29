import { verdictMeta } from "../verdicts";
import VerdictIcon from "./VerdictIcon";

interface Props {
  verdict: string;
  size?: "sm" | "md";
}

export default function VerdictBadge({ verdict, size = "md" }: Props) {
  const meta = verdictMeta(verdict);
  return (
    <span className={`verdict-badge verdict-badge--${size} tone-${meta.tone}`}>
      <VerdictIcon tone={meta.tone} size={size === "sm" ? 14 : 16} />
      {meta.label}
    </span>
  );
}
