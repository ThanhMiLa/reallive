export interface Principal {
  userId: string;

  token: string;

  expiresAt: number;
}

export interface SessionContext {
  sessionId: string;

  channelId: string;

  visibility: string;

  status: string;
}

export interface Permission {
  userId: string;

  canView: boolean;

  canRead: boolean;

  canSend: boolean;
}

export interface Snapshot {
  userId: string;

  displayName: string;

  avatarUrl: string | null;
}
