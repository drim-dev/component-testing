// Resolves X-User-Id into the request's caller. Missing → 401 identity:missing;
// unknown → 401 identity:unknown. POST /users (bootstrap) is the only exempt
// route. The resolved user is attached to the request; controllers read it via
// currentUser(). The decision lives in middleware so every route inherits it —
// authorization is a property of the assembled app, not of any one handler.

import { Injectable, type NestMiddleware } from '@nestjs/common';
import type { NextFunction, Request, Response } from 'express';

import { toBody, unauthorized } from '../apierr/apierr.js';
import type { User } from '../domain/domain.js';
import { Store } from '../store/store.js';

export interface RequestWithUser extends Request {
  user?: User;
}

export function currentUser(req: RequestWithUser): User {
  // Non-exempt routes always run after this middleware sets req.user; the cast is
  // safe because the exempt POST /users handler never calls currentUser().
  return req.user!;
}

@Injectable()
export class IdentityMiddleware implements NestMiddleware {
  constructor(private readonly store: Store) {}

  async use(req: RequestWithUser, res: Response, next: NextFunction): Promise<void> {
    // forRoutes('*') mounts this middleware so Express reports the route-relative
    // req.path (e.g. '/'); the absolute route is in originalUrl. Match the path
    // portion of originalUrl (drop any query string) for the bootstrap exemption.
    const routePath = (req.originalUrl.split('?')[0] || '/').replace(/\/+$/, '') || '/';
    if (req.method === 'POST' && routePath === '/users') {
      next();
      return;
    }
    const id = req.header('X-User-Id');
    if (!id) {
      this.deny(res, unauthorized('identity:missing', 'X-User-Id header is required.'));
      return;
    }
    const user = await this.store.userById(id);
    if (!user) {
      this.deny(res, unauthorized('identity:unknown', 'X-User-Id does not match a known user.'));
      return;
    }
    req.user = user;
    next();
  }

  private deny(res: Response, err: unknown): void {
    const body = toBody(err);
    res.status(body.status).json(body);
  }
}
