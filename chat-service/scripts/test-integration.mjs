import { execFileSync, spawn } from 'node:child_process';
import { createServer } from 'node:net';
import { randomUUID } from 'node:crypto';
const suffix = randomUUID().slice(0, 8);
const mongo = `reallive-chat-test-mongo-${suffix}`;
const redis = `reallive-chat-test-redis-${suffix}`;
const created = [];
const docker = (...args) => execFileSync('docker', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
async function port() {
  const server = createServer();
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const value = server.address().port;
  await new Promise(resolve => server.close(resolve));
  return value;
}
try {
  const mongoPort = await port(), redisPort = await port();
  docker('run', '-d', '--rm', '--name', mongo, '-p', `127.0.0.1:${mongoPort}:${mongoPort}`, 'mongo:8.0.29', '--replSet', 'rs0', '--bind_ip_all', '--port', String(mongoPort));
  created.push(mongo);
  docker('run', '-d', '--rm', '--name', redis, '-p', `127.0.0.1:${redisPort}:6379`, 'redis:7-alpine');
  created.push(redis);
  for (let attempt = 0; ; attempt++) {
    try { docker('exec', mongo, 'mongosh', '--port', String(mongoPort), '--quiet', '--eval', 'db.adminCommand({ping:1})'); break; }
    catch { if (attempt > 100) throw new Error('Test MongoDB did not start'); await new Promise(r => setTimeout(r, 250)); }
  }
  docker('exec', mongo, 'mongosh', '--port', String(mongoPort), '--quiet', '--eval',
    `rs.initiate({_id:'rs0',members:[{_id:0,host:'127.0.0.1:${mongoPort}'}]})`);
  for (let attempt = 0; ; attempt++) {
    try { docker('exec', mongo, 'mongosh', '--port', String(mongoPort), '--quiet', '--eval', 'quit(db.hello().isWritablePrimary ? 0 : 1)'); break; }
    catch { if (attempt > 100) throw new Error('Test replica set did not elect a primary'); await new Promise(r => setTimeout(r, 250)); }
  }
  const child = spawn(process.execPath, ['--experimental-vm-modules', './node_modules/jest/bin/jest.js', '--config', './test/jest-e2e.json', '--runInBand', ...process.argv.slice(2)], {
    stdio: 'inherit', env: { ...process.env, CHAT_TEST_MONGO_PORT: String(mongoPort), CHAT_TEST_REDIS_PORT: String(redisPort) },
  });
  process.exitCode = await new Promise(resolve => child.once('exit', code => resolve(code ?? 1)));
} catch (error) {
  console.error(error instanceof Error ? error.message : 'Integration setup failed');
  process.exitCode = 1;
} finally {
  for (const name of created.reverse()) {
    try { docker('stop', name); } catch { /* Containers have unique per-run names. */ }
  }
}
