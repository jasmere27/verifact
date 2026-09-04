export type InputType = "TEXT" | "URL" | "IMAGE" | "AUDIO";

export interface FactCheckResult {
  id: number;
  inputType: InputType;
  originalInput: string;
  classification: string | null;
  confidenceScore: number | null;
  sources: string | null;
  cybersecurityTips: string | null;
  fullResponse: string;
  createdAt: string;
}

export interface HistoryPage {
  content: FactCheckResult[];
  number: number;
  totalPages: number;
  totalElements: number;
}
