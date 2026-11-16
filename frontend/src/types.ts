export interface User {
  id: string;
  username: string;
  displayName: string;
  role: "ADMIN" | "MEMBER";
}
export interface KnowledgeBase {
  id: string;
  name: string;
  description: string;
  ownerId: string;
  documentCount: number;
  readyCount: number;
  canManage: boolean;
}
export interface Document {
  id: string;
  knowledgeBaseId: string;
  name: string;
  sizeBytes: number;
  status: "QUEUED" | "PROCESSING" | "READY" | "FAILED";
  errorMessage?: string;
  chunkCount: number;
  createdAt: string;
}
export interface Source {
  number: number;
  chunkId: string;
  documentId: string;
  documentName: string;
  pageNumber?: number;
  ordinal: number;
  excerpt: string;
  score: number;
}
export interface Message {
  id: string;
  role: "user" | "assistant";
  content: string;
  sources: Source[];
  createdAt?: string;
  error?: string;
  phase?: string;
  context?: { enabled: boolean; used: boolean; query: string; turns: number };
}
export interface Conversation {
  id: string;
  knowledgeBaseId: string;
  title: string;
  createdAt: string;
}
export interface System {
  name: string;
  demo: boolean;
  formats: string[];
  maxFileSizeMb: number;
  model: string;
  chatConfigured: boolean;
  embeddingModel: string;
}
export interface Chunk {
  id: string;
  documentId: string;
  documentName: string;
  ordinal: number;
  pageNumber?: number;
  content: string;
}
export interface DocumentPreview {
  id: string;
  name: string;
  format: string;
  pages: { page?: number; content: string }[];
}
