import { Body, Controller, Get, HttpCode, Param, Post, Req } from '@nestjs/common';
import type { Request } from 'express';

import { conflict, invalid, notFound } from '../apierr/apierr.js';
import type { User } from '../domain/domain.js';
import { IdFactory } from '../idgen/idgen.js';
import { isUniqueViolation, Store } from '../store/store.js';

const HANDLE_PATTERN = /^[a-z0-9_]+$/;

interface UserDto {
  id: string;
  handle: string;
  displayName: string;
  createdAt: string;
}

function toUserDto(u: User): UserDto {
  return { id: u.id, handle: u.handle, displayName: u.displayName, createdAt: u.createdAt.toISOString() };
}

@Controller()
export class UsersController {
  constructor(
    private readonly store: Store,
    private readonly ids: IdFactory,
  ) {}

  @Post('users')
  @HttpCode(201)
  async createUser(@Body() body: { handle?: string; displayName?: string }): Promise<UserDto> {
    const handle = body.handle ?? '';
    const displayName = body.displayName ?? '';
    if (handle.length < 3 || handle.length > 32 || !HANDLE_PATTERN.test(handle)) {
      throw invalid('user:handle:invalid', 'handle must be 3–32 chars of [a-z0-9_].');
    }
    if (displayName.length < 1 || displayName.length > 64) {
      throw invalid('user:display_name:invalid', 'displayName must be 1–64 chars.');
    }
    const user: User = { id: this.ids.create(), handle, displayName, createdAt: new Date() };
    try {
      await this.store.insertUser(user);
    } catch (err) {
      if (isUniqueViolation(err)) {
        throw conflict('user:handle:taken', 'That handle is taken.');
      }
      throw err;
    }
    return toUserDto(user);
  }

  @Get('users/:id')
  async getUser(@Param('id') id: string, @Req() _req: Request): Promise<UserDto> {
    const user = await this.store.userById(id);
    if (!user) {
      throw notFound('user:not_found', 'User not found.');
    }
    return toUserDto(user);
  }
}
