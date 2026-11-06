import { ThumbnailImageService } from './thumbnail-image.service';
import type { ThumbnailCaptureService } from '../ffmpeg/thumbnail-capture.service';
import type { ThumbnailStorage } from '../storage/thumbnail-storage.port';
import type { ThumbnailJobInput } from '../common/contracts/thumbnail-contract';

const image = {
  filePath: '/tmp/a/thumbnail.jpg',
  directory: '/tmp/a',
  width: 640,
  height: 360,
  sizeBytes: 123,
  contentType: 'image/jpeg' as const,
};
const result = {
  objectKey: 'key',
  width: 640,
  height: 360,
  sizeBytes: 123,
  contentType: 'image/jpeg' as const,
};
const job: ThumbnailJobInput = {
  jobId: 'a',
  sessionId: 'b',
  playbackUrl: 'http://localhost',
  readToken: 'token',
  readExpiresAt: new Date(Date.now() + 60000).toISOString(),
  jobDeadlineAt: new Date(Date.now() + 60000).toISOString(),
};
test('returns upload result even when cleanup fails', async () => {
  const capture = {
    capture: jest.fn().mockResolvedValue(image),
    release: jest.fn().mockRejectedValue(new Error('cleanup')),
  };
  const storage = { upload: jest.fn().mockResolvedValue(result) };
  const service = new ThumbnailImageService(
    capture as unknown as ThumbnailCaptureService,
    storage as ThumbnailStorage,
  );
  await expect(service.create(job)).resolves.toEqual(result);
  expect(capture.release).toHaveBeenCalledWith(image);
});
test('preserves upload error and releases file', async () => {
  const capture = {
    capture: jest.fn().mockResolvedValue(image),
    release: jest.fn().mockResolvedValue(undefined),
  };
  const storage = { upload: jest.fn().mockRejectedValue(new Error('upload')) };
  const service = new ThumbnailImageService(
    capture as unknown as ThumbnailCaptureService,
    storage as ThumbnailStorage,
  );
  await expect(service.create(job)).rejects.toThrow('upload');
  expect(capture.release).toHaveBeenCalledWith(image);
});
