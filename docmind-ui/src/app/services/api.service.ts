import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

// TypeScript interfaces mirroring Java DTO records
export interface QueryRequest {
  query: string;
  sourceFilter?: string;
  maxSources?: number;
}

export interface SourceChunk {
  documentId: string;
  sourceTag: string;
  content: string;
  score: number;
}

export interface QueryResponse {
  query: string;
  answer: string;
  validated: boolean;
  disclaimer?: string;
  sources: SourceChunk[];
  modelUsed: string;
  latencyMs: number;
  retryCount: number;
  generatedAt: string;
}

export interface DocumentMetadata {
  documentId: string;
  fileName: string;
  sourceTag: string;
  mimeType: string;
  fileSizeBytes: number;
  totalChunks: number;
  ingestedAt: string;
}

@Injectable({
  providedIn: 'root'
})
export class ApiService {
  // Requests are routed to the API Gateway running on port 8080
  private baseUrl = 'http://localhost:8080/api/v1';

  constructor(private http: HttpClient) {}

  /** Submit a grounded Q&A query to the gateway */
  query(request: QueryRequest): Observable<QueryResponse> {
    return this.http.post<QueryResponse>(`${this.baseUrl}/query`, request);
  }

  /** Upload a document to the ingestion pipeline */
  ingest(file: File, sourceTag?: string): Observable<DocumentMetadata> {
    const formData = new FormData();
    formData.append('file', file);
    if (sourceTag) {
      formData.append('sourceTag', sourceTag);
    }
    return this.http.post<DocumentMetadata>(`${this.baseUrl}/documents`, formData);
  }

  /** Retrieve all ingested documents */
  getDocuments(): Observable<DocumentMetadata[]> {
    return this.http.get<DocumentMetadata[]>(`${this.baseUrl}/documents`);
  }

  /** Delete an ingested document from the vector knowledge store */
  deleteDocument(documentId: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/documents/${documentId}`);
  }
}
