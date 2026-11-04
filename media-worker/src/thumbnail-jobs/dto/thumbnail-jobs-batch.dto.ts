import { Type } from 'class-transformer';
import {
  ArrayNotEmpty,
  IsArray,
  IsISO8601,
  IsNotEmpty,
  IsString,
  IsUUID,
  Matches,
  MaxLength,
  ValidateNested,
} from 'class-validator';
import type { ThumbnailJobInput } from '../../common/contracts/thumbnail-contract';

const RFC3339_TIMESTAMP =
  /^\d{4}-\d{2}-\d{2}T(?:[01]\d|2[0-3]):[0-5]\d:[0-5]\d(?:\.\d+)?(?:Z|[+-](?:[01]\d|2[0-3]):[0-5]\d)$/i;

export class ThumbnailJobDto implements ThumbnailJobInput {
  @IsUUID()
  jobId!: string;

  @IsUUID()
  sessionId!: string;

  @IsString()
  @IsNotEmpty()
  @MaxLength(2_048)
  playbackUrl!: string;

  @IsString()
  @IsNotEmpty()
  @MaxLength(8_192)
  readToken!: string;

  @IsISO8601({ strict: true, strictSeparator: true })
  @Matches(RFC3339_TIMESTAMP, {
    message: '$property must be a full timestamp with Z or a timezone offset',
  })
  readExpiresAt!: string;

  @IsISO8601({ strict: true, strictSeparator: true })
  @Matches(RFC3339_TIMESTAMP, {
    message: '$property must be a full timestamp with Z or a timezone offset',
  })
  jobDeadlineAt!: string;
}

export class ThumbnailJobsBatchDto {
  @IsArray()
  @ArrayNotEmpty()
  @ValidateNested({ each: true })
  @Type(() => ThumbnailJobDto)
  jobs!: ThumbnailJobDto[];
}
