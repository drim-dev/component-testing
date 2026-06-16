import {
  Controller,
  Get,
  HttpCode,
  Inject,
  Param,
  Post,
  Req,
  Res,
  UploadedFile,
  UseInterceptors,
} from '@nestjs/common';
import { FileInterceptor } from '@nestjs/platform-express';
import type { Response } from 'express';

import { invalid, tooLarge } from '../apierr/apierr.js';
import type { Attachment } from '../domain/domain.js';
import { Role } from '../domain/domain.js';
import { IdFactory } from '../idgen/idgen.js';
import {
  ATTACHMENT_ACCESS,
  ATTACHMENT_STORE,
  CHANNEL_ROLE_GATE,
  type AttachmentAccess,
  type AttachmentStore,
  type ChannelRoleGate,
} from '../seams/seams.js';
import { Store } from '../store/store.js';
import { currentUser, type RequestWithUser } from './identity.middleware.js';

const MAX_ATTACHMENT_BYTES = 1 << 20; // 1 MiB

interface UploadedMulterFile {
  originalname: string;
  buffer: Buffer;
  size: number;
}

interface AttachmentDto {
  id: string;
  channelId: string;
  filename: string;
  sizeBytes: number;
  createdAt: string;
}

@Controller()
export class AttachmentsController {
  constructor(
    private readonly store: Store,
    private readonly ids: IdFactory,
    @Inject(CHANNEL_ROLE_GATE) private readonly roleGate: ChannelRoleGate,
    @Inject(ATTACHMENT_STORE) private readonly objects: AttachmentStore,
    @Inject(ATTACHMENT_ACCESS) private readonly access: AttachmentAccess,
  ) {}

  @Post('channels/:id/attachments')
  @HttpCode(201)
  // Multer caps the field at 1 MiB + 1 so an over-limit upload is detected, not
  // truncated; the handler turns the cap and the empty case into the pinned codes.
  @UseInterceptors(FileInterceptor('file', { limits: { fileSize: MAX_ATTACHMENT_BYTES + 1 } }))
  async upload(
    @Req() req: RequestWithUser,
    @Param('id') channelId: string,
    @UploadedFile() file?: UploadedMulterFile,
  ): Promise<AttachmentDto> {
    const caller = currentUser(req);
    await this.roleGate.authorizeRole(channelId, caller.id, Role.Member);
    if (!file) {
      throw invalid('attachment:invalid', 'A file field is required.');
    }
    if (file.size > MAX_ATTACHMENT_BYTES) {
      throw tooLarge('attachment:too_large', 'The attachment exceeds the 1 MiB limit.');
    }
    if (file.size === 0) {
      throw invalid('attachment:empty', 'The attachment is empty.');
    }
    const id = this.ids.create();
    const storageKey = `${channelId}/${id}`;
    await this.objects.put(storageKey, file.buffer);
    const att: Attachment = {
      id,
      channelId,
      uploaderId: caller.id,
      messageId: null,
      filename: file.originalname,
      sizeBytes: file.size,
      storageKey,
      createdAt: new Date(),
    };
    await this.store.insertAttachment(att);
    return { id: att.id, channelId, filename: att.filename, sizeBytes: att.sizeBytes, createdAt: att.createdAt.toISOString() };
  }

  @Get('attachments/:id')
  async download(@Req() req: RequestWithUser, @Res() res: Response, @Param('id') id: string): Promise<void> {
    const caller = currentUser(req);
    const att = await this.access.authorize(id, caller.id);
    const content = await this.objects.get(att.storageKey);
    res.setHeader('Content-Type', 'application/octet-stream');
    res.setHeader('Content-Disposition', `attachment; filename="${att.filename}"`);
    res.status(200).send(content);
  }
}
