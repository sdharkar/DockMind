import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService, QueryRequest, QueryResponse, DocumentMetadata, SourceChunk } from './services/api.service';

interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
  timestamp: Date;
  isThinking?: boolean;
  currentPhase?: string;
  sources?: SourceChunk[];
  stats?: {
    modelUsed: string;
    latencyMs: number;
    retryCount: number;
    validated: boolean;
    disclaimer?: string;
  };
}

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './app.component.html',
  styleUrl: './app.component.css'
})
export class AppComponent implements OnInit {
  title = 'DocMind';

  // Chat State
  messages: ChatMessage[] = [];
  queryText: string = '';
  sourceFilter: string = '';
  isGenerating: boolean = false;

  // Last Query Stats (for the observability panel)
  lastQueryStats: any = null;
  lastQuerySources: SourceChunk[] = [];

  // Ingestion State
  documents: DocumentMetadata[] = [];
  isLoadingDocs: boolean = false;
  isUploading: boolean = false;
  uploadTag: string = '';
  selectedFile: File | null = null;
  dragOver: boolean = false;
  uploadStatusMessage: string = '';
  uploadStatusType: 'success' | 'error' | '' = '';

  constructor(private apiService: ApiService) {}

  ngOnInit(): void {
    this.loadDocuments();
    
    // Add a welcome message
    this.messages.push({
      role: 'assistant',
      content: 'Welcome to **DocMind**! I am your multi-agent RAG knowledge assistant. Ingest your documents on the left panel, and ask questions. I will retrieve context, generate grounded answers, and fact-check them using LLM-as-Judge orchestration.',
      timestamp: new Date()
    });
  }

  // Load Ingested Documents
  loadDocuments(): void {
    this.isLoadingDocs = true;
    this.apiService.getDocuments().subscribe({
      next: (docs) => {
        // Sort documents by ingestion time (descending)
        this.documents = docs.sort((a, b) => 
          new Date(b.ingestedAt).getTime() - new Date(a.ingestedAt).getTime()
        );
        this.isLoadingDocs = false;
      },
      error: (err) => {
        console.error('Failed to load documents', err);
        this.isLoadingDocs = false;
      }
    });
  }

  // Send Chat Query
  sendQuery(): void {
    if (!this.queryText.trim() || this.isGenerating) return;

    const userQuery = this.queryText.trim();
    this.queryText = '';
    this.isGenerating = true;

    // 1. Add User Message
    this.messages.push({
      role: 'user',
      content: userQuery,
      timestamp: new Date()
    });

    // 2. Add Assistant Thinking Placeholder
    const thinkingMessage: ChatMessage = {
      role: 'assistant',
      content: '',
      timestamp: new Date(),
      isThinking: true,
      currentPhase: 'Retrieving relevant documents from ChromaDB...'
    };
    this.messages.push(thinkingMessage);

    // 3. Simulate Pipeline Phase Transitions for rich UX (LangGraph Orchestration)
    let phaseTimer1 = setTimeout(() => {
      if (thinkingMessage.isThinking) {
        thinkingMessage.currentPhase = 'Generating factual grounded answer via OpenAI GPT-4o...';
      }
    }, 1200);

    let phaseTimer2 = setTimeout(() => {
      if (thinkingMessage.isThinking) {
        thinkingMessage.currentPhase = 'Fact-checking response (LLM-as-Judge) via Ollama Mistral...';
      }
    }, 2800);

    // Prepare request
    const request: QueryRequest = {
      query: userQuery,
      maxSources: 5
    };
    if (this.sourceFilter.trim()) {
      request.sourceFilter = this.sourceFilter.trim();
    }

    // 4. Fire API Request
    this.apiService.query(request).subscribe({
      next: (res) => {
        clearTimeout(phaseTimer1);
        clearTimeout(phaseTimer2);

        // Update thinking placeholder to final response
        thinkingMessage.isThinking = false;
        thinkingMessage.content = res.answer;
        thinkingMessage.sources = res.sources;
        thinkingMessage.stats = {
          modelUsed: res.modelUsed,
          latencyMs: res.latencyMs,
          retryCount: res.retryCount,
          validated: res.validated,
          disclaimer: res.disclaimer
        };

        // Update Observability Panel
        this.lastQueryStats = thinkingMessage.stats;
        this.lastQuerySources = res.sources;
        this.isGenerating = false;
      },
      error: (err) => {
        clearTimeout(phaseTimer1);
        clearTimeout(phaseTimer2);

        thinkingMessage.isThinking = false;
        thinkingMessage.content = 'Sorry, I encountered an error while processing your request. Please check that the API Gateway (port 8080) and backends are running.';
        this.isGenerating = false;
        console.error(err);
      }
    });
  }

  // Handle Document Ingestion File Select
  onFileSelected(event: any): void {
    const files = event.target.files;
    if (files && files.length > 0) {
      this.selectedFile = files[0];
      this.uploadStatusMessage = '';
    }
  }

  // Handle Drag & Drop
  onDragOver(event: DragEvent): void {
    event.preventDefault();
    this.dragOver = true;
  }

  onDragLeave(event: DragEvent): void {
    event.preventDefault();
    this.dragOver = false;
  }

  onDrop(event: DragEvent): void {
    event.preventDefault();
    this.dragOver = false;
    const files = event.dataTransfer?.files;
    if (files && files.length > 0) {
      const file = files[0];
      const ext = file.name.split('.').pop()?.toLowerCase();
      if (ext === 'pdf' || ext === 'txt') {
        this.selectedFile = file;
        this.uploadStatusMessage = '';
      } else {
        this.uploadStatusMessage = 'Only PDF or TXT files are supported.';
        this.uploadStatusType = 'error';
      }
    }
  }

  // Ingest Selected Document
  ingestDocument(): void {
    if (!this.selectedFile) return;

    this.isUploading = true;
    this.uploadStatusMessage = `Uploading and ingesting ${this.selectedFile.name}...`;
    this.uploadStatusType = '';

    const tag = this.uploadTag.trim() ? this.uploadTag.trim() : undefined;

    this.apiService.ingest(this.selectedFile, tag).subscribe({
      next: (meta) => {
        this.isUploading = false;
        this.selectedFile = null;
        this.uploadTag = '';
        this.uploadStatusMessage = `Successfully ingested "${meta.fileName}" (${meta.totalChunks} chunks created).`;
        this.uploadStatusType = 'success';
        this.loadDocuments();
      },
      error: (err) => {
        this.isUploading = false;
        this.uploadStatusMessage = 'Failed to ingest document. Ensure the ingestion service is online.';
        this.uploadStatusType = 'error';
        console.error(err);
      }
    });
  }

  // Delete Document
  deleteDocument(docId: string): void {
    if (!confirm('Are you sure you want to delete this document from the vector store? All its chunks will be purged.')) {
      return;
    }

    this.apiService.deleteDocument(docId).subscribe({
      next: () => {
        this.loadDocuments();
      },
      error: (err) => {
        console.error('Failed to delete document', err);
        alert('Failed to delete document from the vector store.');
      }
    });
  }

  // Helper to format byte sizes
  formatBytes(bytes: number): string {
    if (bytes === 0) return '0 Bytes';
    const k = 1024;
    const sizes = ['Bytes', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
  }
}
