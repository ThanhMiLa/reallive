import { isAbsolute } from 'node:path';

export const WORKER_CONFIG = Symbol('MEDIA_WORKER_CONFIG');

export interface RedisConfig {
  host: string;
  port: number;
  password?: string;
  db: number;
}

export interface WorkerConfig {
  environment: string;
  http: {
    port: number;
    internalToken: string;
    mediamtxBaseUrl: string;
    maxBatchSize: number;
    maxBodyBytes: number;
  };
  job: {
    deadlineMs: number;
    readTokenTtlMs: number;
    width: number;
    height: number;
    tempDir: string;
    ffmpegPath: string;
    ffprobePath: string;
    ffmpegThreads: number;
    maxJpegBytes: number;
    processOutputBytes: number;
    terminationGraceMs: number;
  };
  timeouts: {
    ffmpegMs: number;
    uploadMs: number;
    callbackMs: number;
  };
  queue: {
    concurrency: number;
    processorEnabled: boolean;
    connectTimeoutMs: number;
    commandTimeoutMs: number;
    batchTimeoutMs: number;
    shutdownTimeoutMs: number;
    maxPendingJobs: number;
    retentionMs: number;
    prefix: string;
    redis: RedisConfig;
  };
  cleanup: {
    intervalMs: number;
    minAgeMs: number;
  };
  storage: {
    endpoint?: string;
    region: string;
    bucket?: string;
    accessKeyId?: string;
    secretAccessKey?: string;
  };
  callback: {
    baseUrl: string;
    internalToken?: string;
  };
}

export const WORKER_ENV_KEYS = [
  'NODE_ENV',
  'PORT',
  'MEDIA_WORKER_INTERNAL_TOKEN',
  'MEDIA_WORKER_MEDIAMTX_BASE_URL',
  'MEDIA_WORKER_MAX_BATCH_SIZE',
  'MEDIA_WORKER_MAX_BODY_BYTES',
  'MEDIA_WORKER_JOB_DEADLINE_SECONDS',
  'MEDIA_WORKER_READ_TOKEN_TTL_SECONDS',
  'MEDIA_WORKER_FFMPEG_TIMEOUT_MS',
  'MEDIA_WORKER_UPLOAD_TIMEOUT_MS',
  'MEDIA_WORKER_CALLBACK_TIMEOUT_MS',
  'MEDIA_WORKER_QUEUE_CONCURRENCY',
  'MEDIA_WORKER_PROCESSOR_ENABLED',
  'MEDIA_WORKER_REDIS_CONNECT_TIMEOUT_MS',
  'MEDIA_WORKER_REDIS_COMMAND_TIMEOUT_MS',
  'MEDIA_WORKER_BATCH_TIMEOUT_MS',
  'MEDIA_WORKER_SHUTDOWN_TIMEOUT_MS',
  'MEDIA_WORKER_MAX_PENDING_JOBS',
  'MEDIA_WORKER_JOB_RETENTION_SECONDS',
  'MEDIA_WORKER_QUEUE_PREFIX',
  'REDIS_HOST',
  'REDIS_PORT',
  'REDIS_PASSWORD',
  'REDIS_DB',
  'MEDIA_WORKER_TEMP_DIR',
  'MEDIA_WORKER_FFMPEG_PATH',
  'MEDIA_WORKER_FFPROBE_PATH',
  'MEDIA_WORKER_FFMPEG_THREADS',
  'MEDIA_WORKER_MAX_JPEG_BYTES',
  'MEDIA_WORKER_PROCESS_OUTPUT_BYTES',
  'MEDIA_WORKER_TERMINATION_GRACE_MS',
  'MEDIA_WORKER_THUMBNAIL_WIDTH',
  'MEDIA_WORKER_THUMBNAIL_HEIGHT',
  'MEDIA_WORKER_CLEANUP_INTERVAL_SECONDS',
  'MEDIA_WORKER_CLEANUP_MIN_AGE_SECONDS',
  'MEDIA_WORKER_R2_ENDPOINT',
  'MEDIA_WORKER_R2_REGION',
  'MEDIA_WORKER_R2_BUCKET',
  'MEDIA_WORKER_R2_ACCESS_KEY_ID',
  'MEDIA_WORKER_R2_SECRET_ACCESS_KEY',
  'STREAM_SERVICE_BASE_URL',
  'STREAM_SERVICE_INTERNAL_TOKEN',
] as const;

export class ConfigurationError extends Error {
  readonly issues: readonly string[];

  constructor(issues: readonly string[]) {
    super(`Invalid media-worker configuration:\n- ${issues.join('\n- ')}`);
    this.name = 'ConfigurationError';
    this.issues = issues;
  }
}

const DEFAULTS = {
  port: 8090,
  mediamtxBaseUrl: 'http://media-server:8888',
  maxBatchSize: 50,
  maxBodyBytes: 262_144,
  jobDeadlineSeconds: 240,
  readTokenTtlSeconds: 300,
  ffmpegTimeoutMs: 20_000,
  uploadTimeoutMs: 10_000,
  callbackTimeoutMs: 5_000,
  queueConcurrency: 2,
  maxPendingJobs: 1_000,
  jobRetentionSeconds: 86_400,
  queuePrefix: 'media-worker:thumbnail',
  redisHost: 'localhost',
  redisPort: 6_379,
  redisDb: 0,
  tempDir: '/tmp/media-worker',
  ffmpegPath: 'ffmpeg',
  ffprobePath: 'ffprobe',
  thumbnailWidth: 640,
  thumbnailHeight: 360,
  cleanupIntervalSeconds: 3_600,
  cleanupMinAgeSeconds: 86_400,
  r2Region: 'auto',
  streamServiceBaseUrl: 'http://stream-service:8082',
} as const;

const MIN_INTERNAL_TOKEN_LENGTH = 32;
const MAX_BODY_BYTES = 10 * 1024 * 1024;

class Reader {
  readonly issues: string[] = [];

  constructor(private readonly env: NodeJS.ProcessEnv) {}

  string(
    name: string,
    options: {
      defaultValue?: string;
      required?: boolean;
      minLength?: number;
      trim?: boolean;
    } = {},
  ): string | undefined {
    const raw = this.env[name];
    const empty = raw === undefined || raw.trim() === '';

    if (empty) {
      if (options.required) {
        this.issues.push(`${name} is required`);
        return undefined;
      }
      return options.defaultValue;
    }

    const value = options.trim === false ? raw : raw.trim();
    if (options.minLength && value.length < options.minLength) {
      this.issues.push(
        `${name} must be at least ${options.minLength} characters`,
      );
    }
    return value;
  }

  integer(
    name: string,
    options: {
      defaultValue?: number;
      min?: number;
      max?: number;
    } = {},
  ): number | undefined {
    const raw = this.env[name];
    if (raw === undefined || raw.trim() === '') {
      return options.defaultValue;
    }

    if (!/^\d+$/.test(raw.trim())) {
      this.issues.push(`${name} must be a non-negative integer`);
      return options.defaultValue;
    }

    const value = Number(raw);
    if (!Number.isSafeInteger(value)) {
      this.issues.push(`${name} must be a safe integer`);
      return options.defaultValue;
    }
    if (options.min !== undefined && value < options.min) {
      this.issues.push(`${name} must be at least ${options.min}`);
    }
    if (options.max !== undefined && value > options.max) {
      this.issues.push(`${name} must be at most ${options.max}`);
    }
    return value;
  }

  url(
    name: string,
    value: string | undefined,
    options: { allowPath: boolean },
  ): string | undefined {
    if (value === undefined) {
      return undefined;
    }

    let parsed: URL;
    try {
      parsed = new URL(value);
    } catch {
      this.issues.push(`${name} must be an absolute URL`);
      return undefined;
    }

    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
      this.issues.push(`${name} must use http or https`);
    }
    if (parsed.username !== '' || parsed.password !== '') {
      this.issues.push(`${name} must not contain credentials`);
    }
    if (parsed.search !== '' || parsed.hash !== '') {
      this.issues.push(`${name} must not contain query or fragment`);
    }
    if (!options.allowPath && parsed.pathname !== '/') {
      this.issues.push(`${name} must be an origin without a path`);
    }
    return value;
  }
}

export function loadWorkerConfig(
  env: NodeJS.ProcessEnv = process.env,
): WorkerConfig {
  const reader = new Reader(env);

  const environment = reader.string('NODE_ENV', {
    defaultValue: 'development',
  }) as string;
  const port = reader.integer('PORT', {
    defaultValue: DEFAULTS.port,
    min: 1,
    max: 65_535,
  }) as number;
  const internalToken = reader.string('MEDIA_WORKER_INTERNAL_TOKEN', {
    required: true,
    minLength: MIN_INTERNAL_TOKEN_LENGTH,
    trim: false,
  });
  const mediamtxBaseUrl = reader.url(
    'MEDIA_WORKER_MEDIAMTX_BASE_URL',
    reader.string('MEDIA_WORKER_MEDIAMTX_BASE_URL', {
      defaultValue: DEFAULTS.mediamtxBaseUrl,
    }),
    { allowPath: false },
  );
  const maxBatchSize = reader.integer('MEDIA_WORKER_MAX_BATCH_SIZE', {
    defaultValue: DEFAULTS.maxBatchSize,
    min: 1,
    max: 500,
  }) as number;
  const maxBodyBytes = reader.integer('MEDIA_WORKER_MAX_BODY_BYTES', {
    defaultValue: DEFAULTS.maxBodyBytes,
    min: 1_024,
    max: MAX_BODY_BYTES,
  }) as number;

  const jobDeadlineSeconds = reader.integer(
    'MEDIA_WORKER_JOB_DEADLINE_SECONDS',
    { defaultValue: DEFAULTS.jobDeadlineSeconds, min: 1 },
  ) as number;
  const readTokenTtlSeconds = reader.integer(
    'MEDIA_WORKER_READ_TOKEN_TTL_SECONDS',
    { defaultValue: DEFAULTS.readTokenTtlSeconds, min: 1 },
  ) as number;
  const ffmpegTimeoutMs = reader.integer('MEDIA_WORKER_FFMPEG_TIMEOUT_MS', {
    defaultValue: DEFAULTS.ffmpegTimeoutMs,
    min: 1,
  }) as number;
  const uploadTimeoutMs = reader.integer('MEDIA_WORKER_UPLOAD_TIMEOUT_MS', {
    defaultValue: DEFAULTS.uploadTimeoutMs,
    min: 1,
  }) as number;
  const callbackTimeoutMs = reader.integer('MEDIA_WORKER_CALLBACK_TIMEOUT_MS', {
    defaultValue: DEFAULTS.callbackTimeoutMs,
    min: 1,
  }) as number;
  const thumbnailWidth = reader.integer('MEDIA_WORKER_THUMBNAIL_WIDTH', {
    defaultValue: DEFAULTS.thumbnailWidth,
    min: 16,
    max: 8_192,
  }) as number;
  const thumbnailHeight = reader.integer('MEDIA_WORKER_THUMBNAIL_HEIGHT', {
    defaultValue: DEFAULTS.thumbnailHeight,
    min: 16,
    max: 8_192,
  }) as number;
  const tempDir = reader.string('MEDIA_WORKER_TEMP_DIR', {
    defaultValue: DEFAULTS.tempDir,
  }) as string;
  const ffmpegPath = reader.string('MEDIA_WORKER_FFMPEG_PATH', {
    defaultValue: DEFAULTS.ffmpegPath,
  }) as string;
  const ffprobePath = reader.string('MEDIA_WORKER_FFPROBE_PATH', {
    defaultValue: DEFAULTS.ffprobePath,
  }) as string;

  const ffmpegThreads = reader.integer('MEDIA_WORKER_FFMPEG_THREADS', {
    defaultValue: 1,
    min: 1,
    max: 64,
  }) as number;
  const maxJpegBytes = reader.integer('MEDIA_WORKER_MAX_JPEG_BYTES', {
    defaultValue: 5242880,
    min: 1024,
  }) as number;
  const processOutputBytes = reader.integer(
    'MEDIA_WORKER_PROCESS_OUTPUT_BYTES',
    { defaultValue: 65536, min: 1024 },
  ) as number;
  const terminationGraceMs = reader.integer(
    'MEDIA_WORKER_TERMINATION_GRACE_MS',
    { defaultValue: 1000, min: 1 },
  ) as number;

  const queueConcurrency = reader.integer('MEDIA_WORKER_QUEUE_CONCURRENCY', {
    defaultValue: DEFAULTS.queueConcurrency,
    min: 1,
    max: 64,
  }) as number;
  const enabledValue = reader.string('MEDIA_WORKER_PROCESSOR_ENABLED', {
    defaultValue: 'false',
  });
  if (enabledValue !== 'true' && enabledValue !== 'false')
    reader.issues.push('MEDIA_WORKER_PROCESSOR_ENABLED must be true or false');
  const connectTimeoutMs = reader.integer(
    'MEDIA_WORKER_REDIS_CONNECT_TIMEOUT_MS',
    { defaultValue: 1000, min: 1 },
  ) as number;
  const commandTimeoutMs = reader.integer(
    'MEDIA_WORKER_REDIS_COMMAND_TIMEOUT_MS',
    { defaultValue: 1000, min: 1 },
  ) as number;
  const batchTimeoutMs = reader.integer('MEDIA_WORKER_BATCH_TIMEOUT_MS', {
    defaultValue: 3000,
    min: 1,
  }) as number;
  const shutdownTimeoutMs = reader.integer('MEDIA_WORKER_SHUTDOWN_TIMEOUT_MS', {
    defaultValue: 45000,
    min: 1,
  }) as number;
  const maxPendingJobs = reader.integer('MEDIA_WORKER_MAX_PENDING_JOBS', {
    defaultValue: DEFAULTS.maxPendingJobs,
    min: 1,
    max: 1_000_000,
  }) as number;
  const jobRetentionSeconds = reader.integer(
    'MEDIA_WORKER_JOB_RETENTION_SECONDS',
    { defaultValue: DEFAULTS.jobRetentionSeconds, min: 86_400 },
  ) as number;
  const queuePrefix = reader.string('MEDIA_WORKER_QUEUE_PREFIX', {
    defaultValue: DEFAULTS.queuePrefix,
  }) as string;
  const redisHost = reader.string('REDIS_HOST', {
    defaultValue: DEFAULTS.redisHost,
  }) as string;
  const redisPort = reader.integer('REDIS_PORT', {
    defaultValue: DEFAULTS.redisPort,
    min: 1,
    max: 65_535,
  }) as number;
  const redisPassword = reader.string('REDIS_PASSWORD');
  const redisDb = reader.integer('REDIS_DB', {
    defaultValue: DEFAULTS.redisDb,
    min: 0,
    max: 15,
  }) as number;

  const cleanupIntervalSeconds = reader.integer(
    'MEDIA_WORKER_CLEANUP_INTERVAL_SECONDS',
    { defaultValue: DEFAULTS.cleanupIntervalSeconds, min: 1 },
  ) as number;
  const cleanupMinAgeSeconds = reader.integer(
    'MEDIA_WORKER_CLEANUP_MIN_AGE_SECONDS',
    { defaultValue: DEFAULTS.cleanupMinAgeSeconds, min: 1 },
  ) as number;

  const r2Endpoint = reader.url(
    'MEDIA_WORKER_R2_ENDPOINT',
    reader.string('MEDIA_WORKER_R2_ENDPOINT'),
    { allowPath: true },
  );
  const r2Region = reader.string('MEDIA_WORKER_R2_REGION', {
    defaultValue: DEFAULTS.r2Region,
  }) as string;
  const r2Bucket = reader.string('MEDIA_WORKER_R2_BUCKET');
  const r2AccessKeyId = reader.string('MEDIA_WORKER_R2_ACCESS_KEY_ID');
  const r2SecretAccessKey = reader.string('MEDIA_WORKER_R2_SECRET_ACCESS_KEY');

  const streamServiceBaseUrl = reader.url(
    'STREAM_SERVICE_BASE_URL',
    reader.string('STREAM_SERVICE_BASE_URL', {
      defaultValue: DEFAULTS.streamServiceBaseUrl,
    }),
    { allowPath: false },
  );
  const streamServiceInternalToken = reader.string(
    'STREAM_SERVICE_INTERNAL_TOKEN',
    { minLength: MIN_INTERNAL_TOKEN_LENGTH, trim: false },
  );

  if (!isAbsolute(tempDir)) {
    reader.issues.push('MEDIA_WORKER_TEMP_DIR must be an absolute path');
  }

  const r2Values = [r2Endpoint, r2Bucket, r2AccessKeyId, r2SecretAccessKey];
  const configuredR2Values = r2Values.filter(
    (value) => value !== undefined,
  ).length;
  if (configuredR2Values > 0 && configuredR2Values < r2Values.length) {
    reader.issues.push(
      'MEDIA_WORKER_R2_ENDPOINT, MEDIA_WORKER_R2_BUCKET, MEDIA_WORKER_R2_ACCESS_KEY_ID and MEDIA_WORKER_R2_SECRET_ACCESS_KEY must be configured together',
    );
  }

  const jobDeadlineMs = jobDeadlineSeconds * 1_000;
  const retentionMs = jobRetentionSeconds * 1_000;
  const readTokenTtlMs = readTokenTtlSeconds * 1_000;
  if (jobDeadlineMs <= ffmpegTimeoutMs + uploadTimeoutMs + callbackTimeoutMs) {
    reader.issues.push(
      'MEDIA_WORKER_JOB_DEADLINE_SECONDS must exceed the sum of MEDIA_WORKER_FFMPEG_TIMEOUT_MS, MEDIA_WORKER_UPLOAD_TIMEOUT_MS and MEDIA_WORKER_CALLBACK_TIMEOUT_MS',
    );
  }
  if (retentionMs < jobDeadlineMs) {
    reader.issues.push(
      'MEDIA_WORKER_JOB_RETENTION_SECONDS must be greater than or equal to MEDIA_WORKER_JOB_DEADLINE_SECONDS',
    );
  }
  if (readTokenTtlMs < jobDeadlineMs) {
    reader.issues.push(
      'MEDIA_WORKER_READ_TOKEN_TTL_SECONDS must be greater than or equal to MEDIA_WORKER_JOB_DEADLINE_SECONDS',
    );
  }

  if (reader.issues.length > 0) {
    throw new ConfigurationError(reader.issues);
  }

  return {
    environment,
    http: {
      port,
      internalToken: internalToken as string,
      mediamtxBaseUrl: mediamtxBaseUrl as string,
      maxBatchSize,
      maxBodyBytes,
    },
    job: {
      deadlineMs: jobDeadlineMs,
      readTokenTtlMs,
      width: thumbnailWidth,
      height: thumbnailHeight,
      tempDir,
      ffmpegPath,
      ffprobePath,
      ffmpegThreads,
      maxJpegBytes,
      processOutputBytes,
      terminationGraceMs,
    },
    timeouts: {
      ffmpegMs: ffmpegTimeoutMs,
      uploadMs: uploadTimeoutMs,
      callbackMs: callbackTimeoutMs,
    },
    queue: {
      concurrency: queueConcurrency,
      processorEnabled: enabledValue === 'true',
      connectTimeoutMs,
      commandTimeoutMs,
      batchTimeoutMs,
      shutdownTimeoutMs,
      maxPendingJobs,
      retentionMs,
      prefix: queuePrefix,
      redis: {
        host: redisHost,
        port: redisPort,
        password: redisPassword,
        db: redisDb,
      },
    },
    cleanup: {
      intervalMs: cleanupIntervalSeconds * 1_000,
      minAgeMs: cleanupMinAgeSeconds * 1_000,
    },
    storage: {
      endpoint: r2Endpoint,
      region: r2Region,
      bucket: r2Bucket,
      accessKeyId: r2AccessKeyId,
      secretAccessKey: r2SecretAccessKey,
    },
    callback: {
      baseUrl: streamServiceBaseUrl as string,
      internalToken: streamServiceInternalToken,
    },
  };
}
