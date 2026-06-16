// Renders any thrown error to the pinned ProblemDetails-style JSON body
// (02-api.md §0): { status, code, message }. An ApiError carries the pinned
// status+code; anything else becomes a generic 500 so an unexpected throw never
// leaks internals. Existence-hiding is a property of the ApiError codes, not of
// this filter (an unknown-id 404 and an unauthorized 404 raise identical bodies).

import {
  type ArgumentsHost,
  Catch,
  type ExceptionFilter,
} from '@nestjs/common';
import type { Response } from 'express';

import { toBody } from '../apierr/apierr.js';

@Catch()
export class ErrorFilter implements ExceptionFilter {
  catch(err: unknown, host: ArgumentsHost): void {
    const res = host.switchToHttp().getResponse<Response>();
    const body = toBody(err);
    res.status(body.status).json(body);
  }
}
