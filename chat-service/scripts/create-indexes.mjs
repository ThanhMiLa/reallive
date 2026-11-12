import mongoose from 'mongoose';
const uri = process.env.MONGODB_URI;
if (!uri) throw new Error('MONGODB_URI is required');
const connection = await mongoose.createConnection(uri).asPromise();
try {
  const db = connection.db;
  for (const name of ['chat_messages', 'chat_counters']) {
    if (!(await db.listCollections({ name }).hasNext())) await db.createCollection(name);
  }
  await db.collection('chat_messages').createIndexes([
    { key: { stream_id: 1, sequence: 1 }, name: 'stream_sequence', unique: true },
    { key: { stream_id: 1, user_id: 1, client_message_id: 1 }, name: 'stream_sender_request', unique: true },
  ]);
  console.log('Chat collections and indexes are ready');
} finally { await connection.close(); }
