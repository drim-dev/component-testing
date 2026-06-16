// apierr is Relay's error model. Every deliberately-raised error carries an HTTP
// status and the pinned area:entity:reason code the acceptance catalog asserts.
// Handlers throw these; the exception filter renders the pinned JSON body
// (02-api.md §0).
//
// Existence-hiding (01-domain.md §5) is a property of the codes, not of special
// routing: an unknown-id 404 and an unauthorized 404 raise the SAME
// code+message, so their JSON bodies are byte-identical — exactly what the
// G-IDOR / G-BOLA-READ / G-S3 catches assert.

// ApiError is a Relay API error: an HTTP status, a pinned code, and human text.
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

// 401 — the only identity failures (missing / unknown X-User-Id).
export const unauthorized = (code: string, message: string): ApiError => new ApiError(401, code, message);

// 403 — the caller can see the resource but lacks the membership/role.
export const forbidden = (code: string, message: string): ApiError => new ApiError(403, code, message);

// 404 — resource absent OR existence hidden from an unauthorized caller.
export const notFound = (code: string, message: string): ApiError => new ApiError(404, code, message);

// 409 — state conflict (duplicate handle, already a member, owner cannot leave).
export const conflict = (code: string, message: string): ApiError => new ApiError(409, code, message);

// 413 — attachment over the size limit.
export const tooLarge = (code: string, message: string): ApiError => new ApiError(413, code, message);

// 422 — input failed validation or a business rule (no silent clamping).
export const invalid = (code: string, message: string): ApiError => new ApiError(422, code, message);

// 502 — an upstream (model / unfurl / presence stream) violated its contract.
export const upstream = (code: string, message: string): ApiError => new ApiError(502, code, message);

// 503 — required infrastructure (the event broker) is unavailable.
export const unavailable = (code: string, message: string): ApiError => new ApiError(503, code, message);

// The pinned existence-hiding errors — defined once so the unknown-id and the
// unauthorized paths raise byte-identical bodies.
export const notFoundConversation = (): ApiError =>
  notFound('dm:conversation:not_found', 'Conversation not found.');
export const notFoundChannel = (): ApiError => notFound('channel:not_found', 'Channel not found.');
export const notFoundAttachment = (): ApiError =>
  notFound('attachment:not_found', 'Attachment not found.');

export interface ErrorBody {
  status: number;
  code: string;
  message: string;
}

export function toBody(err: unknown): ErrorBody {
  if (err instanceof ApiError) {
    return { status: err.status, code: err.code, message: err.message };
  }
  // Multer aborts an over-limit upload stream before the handler runs, and Nest's
  // platform-express wraps that as a PayloadTooLargeException, so the handler's
  // size check never runs — translate the framework's 413 here to the pinned code.
  const e = err as { name?: string; code?: string; status?: number; getStatus?: () => number } | undefined;
  const status = typeof e?.getStatus === 'function' ? e.getStatus() : e?.status;
  if (e?.name === 'PayloadTooLargeException' || status === 413 || (e?.name === 'MulterError' && e.code === 'LIMIT_FILE_SIZE')) {
    return { status: 413, code: 'attachment:too_large', message: 'The attachment exceeds the 1 MiB limit.' };
  }
  return { status: 500, code: 'internal:error', message: 'An unexpected error occurred.' };
}
