'use strict';

const origin = 'https://profiler.firefox.com';
const viewerUrl = origin + '/from-post-message/';
const main = document.querySelector('main');
const status = document.querySelector('#status');
const newTab = document.querySelector('#open-tab');
const frame = document.querySelector('iframe');
const zh = document.documentElement.lang === 'zh-CN';
const loading = zh ? '正在打开 Firefox Profiler 并导入当前报告…'
                   : 'Opening Firefox Profiler and importing the current profile…';
const failed = zh ? '未能交接报告。可尝试在新标签页打开，或从 APS 导出后手动导入。'
                  : 'Could not hand off the profile. Try a new tab, or export from APS and import it manually.';
const timeoutMs = 30_000;

function waitUntilReady(viewer) {
  return new Promise((resolve, reject) => {
    const cleanup = () => {
      window.removeEventListener('message', receive);
      clearInterval(poll);
      clearTimeout(timeout);
    };
    const receive = (event) => {
      if (event.origin !== origin || event.source !== viewer ||
          event.data?.name !== 'ready:response') {
        return;
      }
      cleanup();
      resolve();
    };
    window.addEventListener('message', receive);
    const poll = setInterval(() => {
      if (viewer.closed) {
        cleanup();
        reject(new Error('Viewer closed'));
        return;
      }
      viewer.postMessage({ name: 'ready:request' }, origin);
    }, 250);
    const timeout = setTimeout(() => {
      cleanup();
      reject(new Error('Viewer did not respond'));
    }, timeoutMs);
  });
}

async function handoff(viewer) {
  if (!viewer) {
    throw new Error('Viewer blocked');
  }
  await waitUntilReady(viewer);
  const response = await fetch('./profile.json.gz', {
    mode: 'same-origin',
    cache: 'no-store',
    signal: AbortSignal.timeout(timeoutMs),
  });
  if (!response.ok) {
    throw new Error('Current APS profile unavailable');
  }
  const profile = await response.arrayBuffer();
  // The browser transfers the gzip bytes directly to the official tab/frame.
  // They are not placed in a URL or sent to any server by this relay.
  viewer.postMessage({ name: 'inject-profile', profile }, origin, [profile]);
}

function showFailure() {
  status.textContent = failed;
  newTab.hidden = false;
  newTab.disabled = false;
}

newTab.textContent = zh ? '在新标签页重试' : 'Retry in a new tab';
newTab.addEventListener('click', async () => {
  // Open synchronously from a user gesture in case embedding is blocked.
  const viewer = window.open(viewerUrl, '_blank');
  newTab.disabled = true;
  status.textContent = loading;
  try {
    await handoff(viewer);
    status.textContent = zh ? '报告已交给新标签页中的 Firefox Profiler。'
                            : 'Profile handed off to Firefox Profiler in the new tab.';
    newTab.hidden = true;
  } catch {
    showFailure();
  }
});

status.textContent = loading;
// An iframe avoids needing popup permission for automatic imports from APS.
frame.src = viewerUrl;
handoff(frame.contentWindow).then(() => {
  frame.hidden = false;
  main.hidden = true;
}, showFailure);
