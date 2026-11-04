import type { NextFunction, Request, Response } from 'express';
import { json } from 'express';

interface BodyParserError {
  status?: number;
  type?: string;
}

export function createJsonBodyParser(limitBytes: number) {
  const parser = json({
    limit: limitBytes,
    strict: true,
    type: ['application/json', 'application/*+json'],
  });

  return (request: Request, response: Response, next: NextFunction): void => {
    parser(request, response, (error?: BodyParserError) => {
      if (!error) {
        next();
        return;
      }

      const statusCode = error.status === 413 ? 413 : 400;
      response.status(statusCode).json({
        statusCode,
        code: statusCode === 413 ? 'REQUEST_BODY_TOO_LARGE' : 'INVALID_JSON',
        message:
          statusCode === 413
            ? 'Request body is too large'
            : 'Request body is not valid JSON',
      });
    });
  };
}
