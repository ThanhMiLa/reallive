const byId = (id) => document.getElementById(id);
const video = byId('video');
let hls;
let measurement;

function message(text) { byId('message').textContent = text; }

async function post(path, value) {
  const response = await fetch(path, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(value), cache: 'no-store', credentials: 'omit',
  });
  if (!response.ok) throw new Error(`Fixture HTTP ${response.status}`);
  return response.json();
}

function action(id, callback) {
  byId(id).addEventListener('click', async () => {
    byId(id).disabled = true;
    try { await callback(); }
    catch (error) { message(error.message); }
    finally { byId(id).disabled = false; }
  });
}

async function refresh() {
  const response = await fetch('/fixture/status', { cache: 'no-store', credentials: 'omit' });
  if (!response.ok) throw new Error(`Status HTTP ${response.status}`);
  const status = await response.json();
  byId('status').textContent = JSON.stringify(status, null, 2);
  const acceptance = await fetch('/fixture/acceptance', { cache: 'no-store', credentials: 'omit' });
  if (acceptance.ok) {
    const results = await acceptance.json();
    byId('acceptance').textContent = Object.entries(results).map(([name, result]) =>
      `${name}: ${result.passed ? 'PASS' : 'PENDING'}${result.missing?.length ? '\n  ' + result.missing.join('\n  ') : ''}`
    ).join('\n');
  }
  return status;
}

function destroy() {
  if (hls) hls.destroy();
  hls = undefined;
  video.pause();
  video.removeAttribute('src');
  video.load();
}

action('create', async () => {
  await post('/fixture/path', { action: 'create' });
  message('Path đã sẵn sàng. Lấy key rồi nhập vào OBS.');
  await refresh();
});
action('delete', async () => {
  await post('/fixture/path', { action: 'delete' });
  destroy();
  message('Đã yêu cầu đóng path. Giữ key trong OBS và thử phát lại để kiểm chứng bị từ chối.');
  await refresh();
});
action('copy-server', async () => {
  await navigator.clipboard.writeText(byId('server').value);
  message('Đã copy server.');
});
action('copy-key', async () => {
  const result = await post('/fixture/credentials', { kind: 'publish', case: byId('publish-case').value });
  byId('key').value = result.streamKey;
  try {
    await navigator.clipboard.writeText(result.streamKey);
    message('Đã copy stream key test. Dán vào OBS.');
  } catch {
    byId('key').focus();
    byId('key').select();
    message('Browser chưa cho copy tự động. Nhấn Ctrl/Cmd+C để copy key đang chọn.');
  }
});

action('play', async () => {
  destroy();
  byId('audio-confirmed').checked = false;
  if (!window.Hls || !Hls.isSupported()) throw new Error('MVP cần Chrome/Edge desktop hỗ trợ hls.js.');
  const selectedCase = byId('read-case').value;
  const access = await post('/fixture/credentials', { kind: 'read', case: selectedCase });
  const allowed = new URL(access.playbackUrl);
  const token = access.readToken;
  measurement = { kind: 'browser', case: selectedCase, frames: 0, seconds: 0,
    audioConfirmed: false, playlistLoads: 0, segmentLoads: 0, httpError: 0 };
  hls = new Hls({
    manifestLoadingMaxRetry: 0, levelLoadingMaxRetry: 0, fragLoadingMaxRetry: 0,
    xhrSetup(xhr, url) {
      const target = new URL(url, allowed);
      if (target.origin !== allowed.origin || !target.pathname.startsWith(`/${access.path}/`)) {
        throw new Error('Media URL ngoài origin/path fixture.');
      }
      if (xhr.readyState === 0) xhr.open('GET', url, true);
      xhr.withCredentials = false;
      if (token) xhr.setRequestHeader('Authorization', `Bearer ${token}`);
    },
  });
  const instance = hls;
  instance.on(Hls.Events.MANIFEST_LOADED, () => { measurement.playlistLoads += 1; });
  instance.on(Hls.Events.LEVEL_LOADED, () => { measurement.playlistLoads += 1; });
  instance.on(Hls.Events.FRAG_LOADED, () => { measurement.segmentLoads += 1; });
  instance.on(Hls.Events.ERROR, (_event, data) => {
    const status = data.response?.code;
    if (Number.isInteger(status)) measurement.httpError = status;
    byId('play-status').textContent = `Player: ${data.type}/${data.details}; HTTP ${status || '—'}`;
    if (data.fatal) {
      instance.destroy();
      if (hls === instance) hls = undefined;
    }
  });
  instance.on(Hls.Events.MANIFEST_PARSED, () => {
    video.play().catch(() => { byId('play-status').textContent = 'Nhấn Play trên video để cho phép phát.'; });
  });
  instance.attachMedia(video);
  instance.loadSource(access.playbackUrl);
  byId('play-status').textContent = 'Đang mở phiên HLS mới…';
});
action('stop', async () => { destroy(); byId('play-status').textContent = 'Đã dừng player.'; });
action('record-browser', async () => {
  if (!measurement) throw new Error('Mở player trước khi lưu kết quả.');
  measurement.audioConfirmed = byId('audio-confirmed').checked;
  await post('/fixture/observation', measurement);
  message('Đã lưu số đo và xác nhận nghe tiếng để review.');
});
for (const [id, step] of [['obs-publish', 'publish'], ['obs-reconnect', 'reconnect'], ['obs-old-key', 'old-key-rejected']]) {
  action(id, async () => {
    await post('/fixture/observation', { kind: 'obs', step });
    message('Đã ghi nhận quan sát OBS của bạn kèm source hiện tại.');
    await refresh();
  });
}

const timer = setInterval(() => {
  if (measurement && video.readyState >= 2) {
    measurement.frames = video.getVideoPlaybackQuality?.().totalVideoFrames || 0;
    measurement.seconds = Number.isFinite(video.currentTime) ? video.currentTime : 0;
    if (measurement.frames > 0) byId('play-status').textContent =
      `${measurement.frames} frame · ${measurement.seconds.toFixed(1)} giây · ${measurement.playlistLoads} playlist · ${measurement.segmentLoads} fragment`;
  }
  if (!document.hidden) refresh().catch(() => message('Chưa kết nối được fixture/MediaMTX.'));
}, 2000);
window.addEventListener('pagehide', () => { clearInterval(timer); destroy(); }, { once: true });
refresh().catch(() => message('Chưa kết nối được fixture/MediaMTX.'));
