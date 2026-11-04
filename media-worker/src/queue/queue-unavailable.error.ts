export class QueueUnavailableError extends Error {
  constructor(message = 'Thumbnail queue is not available') {
    super(message);
    this.name = 'QueueUnavailableError';
  }
}
