// ===== 智聊 AI 前端：对接后端 /ask（流式）与 /retrieve（来源引用） =====
const API_BASE = 'http://localhost:8080';

const input = document.getElementById('input');
const sendBtn = document.getElementById('send');
const messages = document.getElementById('messages');

// 同一浏览器会话固定一个 convId，保证后端多轮记忆（ChatMemory）生效
let convId = sessionStorage.getItem('convId');
if (!convId) {
  convId = (crypto.randomUUID ? crypto.randomUUID() : 'c-' + Date.now());
  sessionStorage.setItem('convId', convId);
}

// textarea 自动撑高
input.addEventListener('input', () => {
  input.style.height = 'auto';
  input.style.height = Math.min(input.scrollHeight, 120) + 'px';
});

// Enter 发送，Shift+Enter 换行
input.addEventListener('keydown', (e) => {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault();
    send();
  }
});

sendBtn.addEventListener('click', send);

async function send() {
  const msg = input.value.trim();
  if (!msg || sendBtn.disabled) return;

  // 渲染用户消息
  appendMessage('user', msg);
  input.value = '';
  input.style.height = 'auto';

  // 创建助手气泡（流式追加文本），并挂一个来源容器
  const msgDiv = appendMessage('assistant', '');
  const contentEl = msgDiv.querySelector('.content');
  const sourcesBox = document.createElement('div');
  sourcesBox.className = 'sources';
  msgDiv.appendChild(sourcesBox);

  sendBtn.disabled = true;
  fetchSources(msg, sourcesBox);

  try {
    // /ask 返回的是 text/html;charset=utf-8 的纯文本流（非 SSE），用 fetch 读流
    const resp = await fetch(
      `${API_BASE}/ask?msg=${encodeURIComponent(msg)}&convId=${encodeURIComponent(convId)}`
    );
    if (!resp.ok) throw new Error('HTTP ' + resp.status);

    const reader = resp.body.getReader();
    const decoder = new TextDecoder('utf-8'); // 关键：utf-8 解码，避免中文乱码
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      contentEl.textContent += decoder.decode(value, { stream: true });
      scrollToBottom();
    }
    contentEl.textContent += decoder.decode(); // flush 剩余字节
  } catch (err) {
    contentEl.textContent = '⚠️ 出错了：' + err.message;
  } finally {
    sendBtn.disabled = false;
    scrollToBottom();
  }
}

// 来源引用：/retrieve 返回 List<SourceSnippet>{file, excerpt}
async function fetchSources(msg, box) {
  box.innerHTML = '<div class="sources-title">📚 参考来源加载中…</div>';
  try {
    const resp = await fetch(`${API_BASE}/retrieve?msg=${encodeURIComponent(msg)}`);
    if (!resp.ok) throw new Error('HTTP ' + resp.status);
    const list = await resp.json();
    if (!list || list.length === 0) {
      box.innerHTML = '<div class="sources-title">📚 未找到相关来源</div>';
      return;
    }
    box.innerHTML = '<div class="sources-title">📚 参考来源</div>';
    const ul = document.createElement('ul');
    list.forEach((s) => {
      const li = document.createElement('li');
      li.innerHTML =
        `<span class="src-file">${escapeHtml(s.file)}</span>` +
        `<span class="src-excerpt">${escapeHtml(s.excerpt)}</span>`;
      ul.appendChild(li);
    });
    box.appendChild(ul);
  } catch (err) {
    box.innerHTML = '<div class="sources-title">📚 来源获取失败</div>';
  }
}

function appendMessage(role, text) {
  const div = document.createElement('div');
  div.className = 'msg ' + role;

  const avatar = document.createElement('div');
  avatar.className = 'avatar';
  avatar.textContent = role === 'user' ? '我' : 'AI';

  const bubble = document.createElement('div');
  bubble.className = 'bubble';
  const content = document.createElement('div');
  content.className = 'content';
  content.textContent = text;
  bubble.appendChild(content);

  div.appendChild(avatar);
  div.appendChild(bubble);
  messages.appendChild(div);
  scrollToBottom();
  return div;
}

function escapeHtml(str) {
  return (str || '').replace(/[&<>"']/g, (c) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  }[c]));
}

function scrollToBottom() {
  messages.scrollTop = messages.scrollHeight;
}

// ===== 左上角黄金集自测面板 =====
const evalRunBtn = document.getElementById('eval-run');
const evalSummary = document.getElementById('eval-summary');
const evalDetail = document.getElementById('eval-detail');

// 页面加载时拉一次最近一次评测结果（后端 /eval/result 返回缓存）
window.addEventListener('load', () => {
  fetch(`${API_BASE}/eval/result`)
    .then((r) => (r.ok ? r.json() : null))
    .then((report) => { if (report) renderEval(report); })
    .catch(() => {});
});

evalRunBtn.addEventListener('click', runEval);

async function runEval() {
  evalRunBtn.disabled = true;
  evalRunBtn.textContent = '评测中…';
  evalSummary.textContent = '正在逐条评测（每条会调用模型，约需 30–60 秒）…';
  evalDetail.innerHTML = '';
  try {
    const resp = await fetch(`${API_BASE}/eval/run`);
    if (!resp.ok) throw new Error('HTTP ' + resp.status);
    const report = await resp.json();
    renderEval(report);
  } catch (err) {
    evalSummary.innerHTML =
      '⚠️ 评测失败：' + escapeHtml(err.message) +
      '<br>请确认后端已接入 <code>/eval/run</code> 接口（见 RagEval 改写说明）';
  } finally {
    evalRunBtn.disabled = false;
    evalRunBtn.textContent = '运行';
  }
}

function renderEval(r) {
  evalSummary.innerHTML =
    `混合召回 <b>${pct(r.hybridRecall)}</b> · 纯向量 <b>${pct(r.vectorRecall)}</b> · 忠实度 <b>${pct(r.faithfulness)}</b>（${r.total} 条）`;
  evalDetail.innerHTML = '';
  (r.details || []).forEach((d) => {
    const card = document.createElement('div');
    card.className = 'eval-case ' + (d.hybridRecallHit ? 'pass' : 'fail');
    card.innerHTML =
      `<div class="eval-q">${escapeHtml(d.question)}</div>` +
      `<div class="eval-tags">` +
        `<span class="tag ${d.hybridRecallHit ? 'ok' : 'no'}">混合${d.hybridRecallHit ? '✓' : '✗'}</span>` +
        `<span class="tag ${d.vectorRecallHit ? 'ok' : 'no'}">向量${d.vectorRecallHit ? '✓' : '✗'}</span>` +
        `<span class="tag ${d.faithful ? 'ok' : 'no'}">忠实${d.faithful ? '✓' : '✗'}</span>` +
      `</div>` +
      `<div class="eval-ans">${escapeHtml(truncate(d.answer, 120))}</div>`;
    evalDetail.appendChild(card);
  });
}

function pct(x) {
  return (Number(x || 0) * 100).toFixed(0) + '%';
}

function truncate(s, n) {
  s = s || '';
  return s.length > n ? s.slice(0, n) + '…' : s;
}
