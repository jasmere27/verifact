export interface ParsedVerdict {
  classification: string | null;
  confidenceScore: number | null;
}

const CLASSIFICATION = /\*\*Classification:\*\*\s*([A-Za-z]+)/i;
const CONFIDENCE = /\*\*Confidence Score:\*\*\s*(\d{1,3})\s*%/;

export function parseVerdict(response: string): ParsedVerdict {
  const classificationMatch = response.match(CLASSIFICATION);
  const confidenceMatch = response.match(CONFIDENCE);
  return {
    classification: classificationMatch ? classificationMatch[1].toLowerCase() : null,
    confidenceScore: confidenceMatch ? Number(confidenceMatch[1]) : null,
  };
}
