import type { HistoryPage } from "./types";

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080";

async function handleTextResponse(response: Response): Promise<string> {
  const text = await response.text();
  if (!response.ok) {
    throw new Error(text || `Request failed with status ${response.status}`);
  }
  return text;
}

export async function checkText(news: string): Promise<string> {
  const response = await fetch(`${API_BASE_URL}/api/v1/isFakeNews`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ news }),
  });
  return handleTextResponse(response);
}

export async function checkImage(file: File): Promise<string> {
  const formData = new FormData();
  formData.append("file", file);
  const response = await fetch(`${API_BASE_URL}/api/v1/analyzeImage`, {
    method: "POST",
    body: formData,
  });
  return handleTextResponse(response);
}

export async function checkAudio(file: File): Promise<string> {
  const formData = new FormData();
  formData.append("file", file);
  const response = await fetch(`${API_BASE_URL}/api/v1/analyzeAudio`, {
    method: "POST",
    body: formData,
  });
  return handleTextResponse(response);
}

export async function fetchHistory(page: number, size = 10): Promise<HistoryPage> {
  const response = await fetch(`${API_BASE_URL}/api/v1/history?page=${page}&size=${size}`);
  if (!response.ok) {
    throw new Error(`Failed to load history (status ${response.status})`);
  }
  return response.json();
}
