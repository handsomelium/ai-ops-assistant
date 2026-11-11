/* ============================================================================
 * AI 应用开发实战 Demo —— 前端逻辑
 *
 * 关键点：POST + SSE。浏览器原生 EventSource 只支持 GET，
 * 而对话内容要放在请求体里，所以用 fetch + ReadableStream 手动解析 SSE 帧。
 * ==========================================================================*/
'use strict';

const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));

const state = {
    tab: 'chat',
    conversationId: 'conv-' + Date.now().toString(36),
    userId: 'demo-user',
    busy: false,
    status: null,
};

const ENDPOINTS = {
    chat: '/api/chat',
    agent: '/api/agent',
    multi: '/api/multi-agent',
};

const TITLES = {
    chat: '基础问答',
    agent: 'Agent 工具调用',
    multi: '多智能体协同',
};

const QUICK = {
    chat: [
        '什么是 MCP 协议？它解决什么问题？',
        'Agent 和直接调用大模型有什么区别？',
        '我叫张三，是设备部主管，负责 A 座和 B 座，回答请尽量简短',
        '我负责哪几座楼？我是什么岗位？',
        '现在园区里有哪些设备是异常的？',
    ],
    agent: [
        '现在有多少张待处理工单？',
        'B1 低压配电柜现在是什么情况？',
        '园区里有哪些异常设备？各自什么问题？',
        '给 1 号冷水机组的能效问题开一张高优先级工单，并派给合适的人',
        '把 WO-2026-0003 派给张建国',
        '把园区东门摄像头的问题关单，处理结果写"已更换 PoE 交换机端口"',
    ],
    multi: [
        '全面评估园区当前运维状况，重点分析 B1 配电柜温度告警和 1 号冷水机组能效下降，给出应急处置方案',
        '生成今天的运维日报',
        '现在整体情况怎么样？有哪些风险需要优先处理？',
    ],
    data: [],
};

/* ---------------------------------------------------------------- 初始化 */

document.addEventListener('DOMContentLoaded', async () => {
    bindTabs();
    bindComposer();
    bindButtons();
    renderQuick();
    renderContextPanel();
    await loadStatus();
});

function bindTabs() {
    $$('.tab').forEach(btn => btn.addEventListener('click', () => switchTab(btn.dataset.tab)));
}

function switchTab(tab) {
    state.tab = tab;
    $$('.tab').forEach(b => b.classList.toggle('active', b.dataset.tab === tab));
    $('#panel-chat').classList.toggle('active', tab !== 'data');
    $('#panel-data').classList.toggle('active', tab === 'data');
    if (tab === 'data') {
        loadData();
    } else {
        $('#chat-title').textContent = TITLES[tab];
        $('#messages').innerHTML = '';
        clearTimeline();
        renderQuick();
        renderContextPanel();
        appendSystemHint(tab);
    }
}

function bindComposer() {
    const input = $('#input');
    input.addEventListener('keydown', e => {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            send();
        }
    });
    $('#btn-send').addEventListener('click', send);
}

function bindButtons() {
    $('#btn-clear-conv').addEventListener('click', async () => {
        if (state.busy) return;
        await fetch(`/api/chat/session-memory?conversationId=${encodeURIComponent(state.conversationId)}`,
            { method: 'DELETE' });
        state.conversationId = 'conv-' + Date.now().toString(36);
        $('#messages').innerHTML = '';
        clearTimeline();
        renderContextPanel();
        appendSystemHint(state.tab);
    });
    $('#btn-clear-trace').addEventListener('click', clearTimeline);
    $('#btn-refresh-data').addEventListener('click', loadData);
}

function renderQuick() {
    const box = $('#quick');
    box.innerHTML = '';
    (QUICK[state.tab] || []).forEach(text => {
        const b = document.createElement('button');
        b.className = 'chip';
        b.textContent = text.length > 34 ? text.slice(0, 34) + '…' : text;
        b.title = text;
        b.addEventListener('click', () => {
            if (state.busy) return;
            $('#input').value = text;
            send();
        });
        box.appendChild(b);
    });
}

function appendSystemHint(tab) {
    const hints = {
        chat: '当前是<b>基础问答模式</b>：只挂了提示词和记忆，没有任何业务工具。'
            + '试着问它园区里的设备状态，它会拒绝回答并提示切换模式 —— 这就是提示词里的"能力边界"在起作用。',
        agent: '当前是<b>单体 Agent 模式</b>：模型拿到了 11 个业务工具，可以自己决定调哪个、调几次。'
            + '右侧「执行过程」会实时显示每一次工具调用的入参和返回。',
        multi: '当前是<b>多智能体协同模式</b>：调度中枢先拆解任务，再分派给专家并行执行，最后由报告 Agent 汇总。'
            + '右侧可以看到完整的流转链路。',
    };
    if (!hints[tab]) return;
    const el = document.createElement('div');
    el.className = 'msg system-hint';
    el.innerHTML = `<div class="bubble" style="background:#f7fbfb;border-style:dashed;width:100%">
        <div class="body" style="font-size:12.6px;color:var(--text-soft)">${hints[tab]}</div></div>`;
    $('#messages').appendChild(el);
}

/* ---------------------------------------------------------------- 状态 */

async function loadStatus() {
    try {
        const r = await fetch('/api/system/status');
        state.status = await r.json();
        const ok = state.status.apiKeyConfigured;
        $('#status-dot').className = 'dot ' + (ok ? 'ok' : 'err');
        $('#status-text').textContent = ok
            ? `${state.status.chatModel} · 已就绪`
            : 'API Key 未配置';
        renderContextPanel();
        if (!ok) {
            showBanner('err', 'API Key 未配置：请编辑 <code>src/main/resources/application.yml</code> 里的 '
                + '<code>spring.ai.openai.api-key</code>，或设置环境变量 <code>DASHSCOPE_API_KEY</code> 后重启。'
                + '注意 Token Plan（sk-sp- 开头）必须配 token-plan 网关，普通百炼 Key（sk- 开头）必须配 dashscope 网关。');
        }
    } catch (e) {
        $('#status-dot').className = 'dot err';
        $('#status-text').textContent = '后端未连通';
    }
}

function showBanner(type, html) {
    let el = $('.banner');
    if (!el) {
        el = document.createElement('div');
        el.className = 'banner';
        $('main').prepend(el);
    }
    el.className = `banner show ${type}`;
    el.innerHTML = `<span>${type === 'err' ? '⚠' : 'ℹ'}</span><div>${html}</div>`;
}

/* ---------------------------------------------------------------- 发送 */

async function send() {
    if (state.busy) return;
    const input = $('#input');
    const text = input.value.trim();
    if (!text) return;
    if (state.tab === 'data') return;

    input.value = '';
    setBusy(true);
    clearTimeline();

    appendMessage('user', text);
    const aiEl = appendMessage('ai', '', true);
    const t0 = performance.now();

    const body = state.tab === 'multi'
        ? { message: text }
        : { message: text, conversationId: state.conversationId, userId: state.userId };

    const ctx = { buffer: '', aiEl: aiEl, body: aiEl.querySelector('.body'), t0, finalText: null, toolCount: 0 };

    try {
        await streamSSE(ENDPOINTS[state.tab], body, ev => handleEvent(ev, ctx));
    } catch (e) {
        tlError('请求失败', String(e && e.message ? e.message : e));
        ctx.body.innerHTML = `<span style="color:var(--err)">请求失败：${escapeHtml(String(e))}</span>`;
    } finally {
        aiEl.querySelector('.body').classList.remove('typing');
        setBusy(false);
        const meta = aiEl.querySelector('.meta');
        if (meta) {
            meta.textContent = `耗时 ${((performance.now() - t0) / 1000).toFixed(1)}s`
                + (ctx.toolCount ? ` · 工具调用 ${ctx.toolCount} 次` : '')
                + ` · ${state.status ? state.status.chatModel : ''}`;
        }
        if (state.tab !== 'data') loadDataSilently();
    }
}

function setBusy(busy) {
    state.busy = busy;
    $('#btn-send').disabled = busy;
    $('#btn-send').textContent = busy ? '思考中…' : '发送';
    $('#input').disabled = busy;
}

/* ---------------------------------------------------------------- SSE */

/**
 * 用 fetch 读取 SSE 流。
 * SSE 帧以空行分隔，每帧可能有多行 data:，需要拼接后再 JSON.parse。
 */
async function streamSSE(url, body, onEvent) {
    const resp = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Accept': 'text/event-stream' },
        body: JSON.stringify(body),
    });
    if (!resp.ok) {
        let detail = '';
        try { detail = await resp.text(); } catch (e) { /* ignore */ }
        throw new Error(`HTTP ${resp.status} ${detail.slice(0, 300)}`);
    }
    if (!resp.body) throw new Error('浏览器不支持流式响应');

    const reader = resp.body.getReader();
    const decoder = new TextDecoder('utf-8');
    let buf = '';

    while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buf += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n');

        let idx;
        while ((idx = buf.indexOf('\n\n')) >= 0) {
            const frame = buf.slice(0, idx);
            buf = buf.slice(idx + 2);
            const data = frame.split('\n')
                .filter(l => l.startsWith('data:'))
                .map(l => l.slice(5).replace(/^ /, ''))
                .join('\n');
            if (!data) continue;
            try {
                onEvent(JSON.parse(data));
            } catch (e) {
                console.warn('无法解析的 SSE 数据:', data, e);
            }
        }
    }
}

/* ---------------------------------------------------------------- 事件处理 */

function handleEvent(ev, ctx) {
    switch (ev.type) {
        case 'delta':
            ctx.buffer += ev.content || '';
            ctx.body.innerHTML = mdToHtml(ctx.buffer);
            ctx.body.classList.add('typing');
            scrollMessages();
            break;

        case 'final':
            ctx.finalText = ev.content || ctx.buffer;
            if (ctx.finalText && ctx.finalText !== ctx.buffer) {
                ctx.body.innerHTML = mdToHtml(ctx.finalText);
            }
            ctx.body.classList.remove('typing');
            break;

        case 'session_memory':
            renderSessionMemory(ev);
            tlInfo('会话记忆', `已载入 ${ev.size} 条历史消息（conversationId=${ev.conversationId}）`);
            break;

        case 'memory':
            renderLongTermMemory(ev);
            if (ev.facts && ev.facts.length) {
                tlInfo('长期记忆', `当前记住 ${ev.facts.length} 条：${ev.facts.join('；')}`);
            }
            break;

        case 'agent_start':
            tlAgentStart(ev);
            break;

        case 'agent_end':
            tlAgentEnd(ev);
            break;

        case 'tool_call':
            ctx.toolCount++;
            tlToolCall(ev);
            break;

        case 'tool_result':
            tlToolResult(ev);
            break;

        case 'plan':
            tlPlan(ev);
            break;

        case 'phase':
            tlInfo('编排', `进入专家执行阶段 · ${ev.count} 个任务 · ${ev.parallel ? '并行' : '串行'}`);
            break;

        case 'warning':
            tlError('警告', ev.message);
            break;

        case 'error':
            tlError('错误', ev.message);
            ctx.body.innerHTML += `<div style="color:var(--err);margin-top:8px">⚠ ${escapeHtml(ev.message)}</div>`;
            break;

        case 'done':
            ctx.body.classList.remove('typing');
            tlInfo('完成', `全流程耗时 ${(ev.totalMs / 1000).toFixed(1)}s`);
            break;

        default:
            console.debug('未处理的事件', ev);
    }
}

function scrollMessages() {
    const box = $('#messages');
    box.scrollTop = box.scrollHeight;
}

function appendMessage(role, text, streaming = false) {
    const wrap = document.createElement('div');
    wrap.className = 'msg ' + role;
    wrap.innerHTML = `
        <div class="avatar">${role === 'user' ? '我' : 'AI'}</div>
        <div class="bubble" style="flex:1">
            <div class="body${streaming ? ' typing' : ''}">${role === 'user' ? escapeHtml(text) : mdToHtml(text)}</div>
            ${role === 'ai' ? '<div class="meta"></div>' : ''}
        </div>`;
    $('#messages').appendChild(wrap);
    scrollMessages();
    return wrap;
}

/* ---------------------------------------------------------------- 时间线 */

function clearTimeline() {
    $('#timeline').innerHTML = '<p class="empty">发起对话后，这里会实时显示 AI 的每一步动作。</p>';
}

function tlRoot() {
    const tl = $('#timeline');
    const empty = tl.querySelector('.empty');
    if (empty) empty.remove();
    return tl;
}

function tlAdd(cls, tag, name, bodyHtml, ms) {
    const el = document.createElement('div');
    el.className = 'tl-item ' + cls;
    el.innerHTML = `
        <div class="tl-head">
            <span class="tl-tag">${escapeHtml(tag)}</span>
            <span class="tl-name">${escapeHtml(name)}</span>
            ${ms != null ? `<span class="tl-ms">${ms}ms</span>` : ''}
        </div>
        ${bodyHtml ? `<div class="tl-body">${bodyHtml}</div>` : ''}`;
    tlRoot().appendChild(el);
    el.scrollIntoView({ block: 'nearest' });
    return el;
}

function tlInfo(tag, text) {
    tlAdd('', tag, text);
}

function tlError(tag, text) {
    tlAdd('err', tag, `<span class="fail">${escapeHtml(String(text))}</span>`);
}

function tlAgentStart(ev) {
    const el = tlAdd('agent running', 'AGENT', `${ev.agentName}`,
        `<div>角色：${escapeHtml(ev.role || '')}</div><pre>${escapeHtml(ev.input || '')}</pre>`);
    el.dataset.agentId = ev.agentId;
}

function tlAgentEnd(ev) {
    const items = $$('#timeline .tl-item.agent.running');
    const el = items.reverse().find(i => i.dataset.agentId === ev.agentId);
    if (!el) {
        tlAdd('agent ok', 'AGENT', `${ev.agentName} 完成`, `<pre>${escapeHtml(ev.output || '')}</pre>`, ev.elapsedMs);
        return;
    }
    el.classList.remove('running');
    el.classList.add('ok');
    const head = el.querySelector('.tl-head');
    if (!head.querySelector('.tl-ms')) {
        head.insertAdjacentHTML('beforeend', `<span class="tl-ms">${ev.elapsedMs}ms</span>`);
    }
    el.querySelector('.tl-body').insertAdjacentHTML('beforeend',
        `<div style="margin-top:6px"><b style="color:var(--ok);font-size:11.5px">产出</b><pre>${escapeHtml(ev.output || '')}</pre></div>`);
}

function tlToolCall(ev) {
    const el = tlAdd('tool running', 'TOOL', ev.tool,
        `<pre>${escapeHtml(prettyJson(ev.args))}</pre>`);
    el.dataset.tool = ev.tool;
    if (ev.agent) {
        el.querySelector('.tl-body').insertAdjacentHTML('afterbegin',
            `<div style="color:var(--text-dim);font-size:11px">调用方：${escapeHtml(ev.agent)}</div>`);
    }
}

function tlToolResult(ev) {
    const items = $$('#timeline .tl-item.tool.running');
    const el = items.reverse().find(i => i.dataset.tool === ev.tool);
    if (!el) return;
    el.classList.remove('running');
    el.classList.add(ev.success ? 'ok' : 'err');
    const head = el.querySelector('.tl-head');
    if (!head.querySelector('.tl-ms')) {
        head.insertAdjacentHTML('beforeend', `<span class="tl-ms">${ev.elapsedMs}ms</span>`);
    }
    el.querySelector('.tl-body').insertAdjacentHTML('beforeend',
        `<div style="margin-top:6px"><b style="color:${ev.success ? 'var(--ok)' : 'var(--err)'};font-size:11.5px">`
        + `${ev.success ? '返回' : '被拒绝'}</b><pre>${escapeHtml(prettyJson(ev.summary))}</pre></div>`);
}

function tlPlan(ev) {
    const tasks = (ev.tasks || []).map(t => `
        <div class="plan-task">
            <b>${escapeHtml(t.agentName || t.agentId)}</b>
            <span>${escapeHtml(t.instruction || '')}</span>
        </div>`).join('');
    tlAdd('agent ok', 'PLAN', '调度中枢已拆解任务', `
        <div class="plan">
            <span class="plan-intent">${escapeHtml(ev.intent || '')}</span>
            ${ev.needReport ? '<span class="plan-intent" style="background:var(--teal);margin-left:5px">需要汇总报告</span>' : ''}
            <div class="plan-analysis">${escapeHtml(ev.analysis || '')}</div>
            ${tasks || '<div class="plan-analysis">无子任务，由调度中枢直接回答</div>'}
        </div>`);
}

/* ---------------------------------------------------------------- 侧栏 */

function renderContextPanel() {
    const card = $('#card-context');
    if (!card) return;
    const s = state.status;

    if (state.tab === 'chat') {
        card.innerHTML = `<div class="card-head"><h3>记忆状态</h3>
            <button class="btn ghost sm" id="btn-clear-ltm">清空长期记忆</button></div>
            <div class="kv">
                <div class="kv-row"><span class="kv-k">会话记忆</span><span class="kv-v" id="sm-info">窗口 ${s ? s.memoryWindow : '-'} 条</span></div>
                <div class="kv-row"><span class="kv-k">长期记忆</span><span class="kv-v">${s && s.longTermMemoryEnabled ? '已开启' : '已关闭'}</span></div>
            </div>
            <div class="card-head" style="border-top:1px solid var(--line-soft)"><h3>已记住的用户信息</h3></div>
            <div class="kv" id="ltm-box"><p class="empty">还没有长期记忆。试着告诉它你的姓名和岗位。</p></div>`;
        const btn = $('#btn-clear-ltm');
        if (btn) btn.addEventListener('click', async () => {
            await fetch(`/api/chat/long-term-memory?userId=${encodeURIComponent(state.userId)}`, { method: 'DELETE' });
            renderLongTermMemory({ userId: state.userId, facts: [] });
        });
        loadLongTermMemory();
    } else if (state.tab === 'agent') {
        const tools = s ? s.agentTools : [];
        const writeTools = ['createWorkOrder', 'assignWorkOrder', 'closeWorkOrder',
            'changeWorkOrderPriority', 'updateDeviceStatus'];
        card.innerHTML = `<div class="card-head"><h3>Agent 被授权的工具</h3>
            <span class="hint">${tools.length} 个</span></div>
            <div class="kv">
                <div class="kv-row"><span class="kv-k">模型</span><span class="kv-v mono">${s ? s.chatModel : '-'}</span></div>
                <div class="kv-row"><span class="kv-k">思考模式</span><span class="kv-v">${s && s.thinkingDisabled ? '已关闭（省时间）' : '开启'}</span></div>
                <div class="kv-row"><span class="kv-k">网关</span><span class="kv-v mono" style="font-size:11px">${s ? s.baseUrl : '-'}</span></div>
            </div>
            <div class="card-head" style="border-top:1px solid var(--line-soft)"><h3>工具清单</h3></div>
            <div class="taglist" style="padding:12px 16px">
                ${tools.map(t => `<span class="tag${writeTools.includes(t) ? ' write' : ''}">${t}</span>`).join('')}
            </div>
            <div class="kv" style="padding-top:0"><p class="empty">橙色 = 写操作工具，会真实修改业务数据</p></div>`;
    } else if (state.tab === 'multi') {
        const experts = s ? s.experts : [];
        card.innerHTML = `<div class="card-head"><h3>专家名册</h3>
            <span class="hint">${experts.length} 位</span></div>
            <div class="kv">
                <div class="kv-row"><span class="kv-k">并行执行</span><span class="kv-v">${s && s.parallelExperts ? '开启' : '关闭'}</span></div>
                <div class="kv-row"><span class="kv-k">汇总模型</span><span class="kv-v mono">${s ? s.reportModel : '-'}</span></div>
            </div>
            <div class="list">
                ${experts.map(e => `<div class="list-item">
                    <h5>${escapeHtml(e.displayName)} <code>${escapeHtml(e.id)}</code></h5>
                    <p>${escapeHtml(e.capability)}</p>
                    <div class="taglist">${(e.tools || []).map(t => `<span class="tag">${escapeHtml(t)}</span>`).join('')}</div>
                </div>`).join('')}
            </div>`;
    } else {
        card.innerHTML = '';
    }
}

function renderSessionMemory(ev) {
    const el = $('#sm-info');
    if (el) el.textContent = `窗口 ${state.status ? state.status.memoryWindow : '-'} 条 · 当前 ${ev.size} 条`;
}

function renderLongTermMemory(ev) {
    const box = $('#ltm-box');
    if (!box) return;
    const facts = ev.facts || [];
    box.innerHTML = facts.length
        ? facts.map(f => `<div class="fact">${escapeHtml(f)}</div>`).join('')
        : '<p class="empty">还没有长期记忆。试着告诉它你的姓名和岗位。</p>';
}

async function loadLongTermMemory() {
    try {
        const r = await fetch(`/api/chat/long-term-memory?userId=${encodeURIComponent(state.userId)}`);
        renderLongTermMemory(await r.json());
    } catch (e) { /* ignore */ }
}

/* ---------------------------------------------------------------- 业务数据 */

async function loadDataSilently() {
    if (state.tab === 'data') loadData();
}

async function loadData() {
    try {
        const [ov, orders, devices, engineers] = await Promise.all([
            fetch('/api/ops/overview').then(r => r.json()),
            fetch('/api/ops/work-orders').then(r => r.json()),
            fetch('/api/ops/devices').then(r => r.json()),
            fetch('/api/ops/engineers').then(r => r.json()),
        ]);

        $('#overview').innerHTML = [
            ['工单总数', ov.total, ''],
            ['待处理', ov.open, ov.open > 0 ? 'warn' : ''],
            ['处理中', ov.processing, ''],
            ['已关闭', ov.closed, ''],
            ['高优先级未关闭', ov.highActive, ov.highActive > 0 ? 'err' : ''],
            ['平均关单时长', ov.avgCloseHours + 'h', ''],
            ['设备总数', ov.deviceTotal, ''],
            ['异常设备', ov.deviceAbnormal, ov.deviceAbnormal > 0 ? 'err' : ''],
        ].map(([l, n, c]) => `<div class="stat"><div class="num ${c}">${n}</div><div class="lbl">${l}</div></div>`).join('');

        $('#wo-count').textContent = `${orders.length} 条`;
        $('#tbl-orders').innerHTML = `
            <thead><tr><th>工单号</th><th>标题</th><th>优先级</th><th>状态</th><th>设备</th><th>处理人</th></tr></thead>
            <tbody>${orders.map(o => `<tr>
                <td class="mono">${escapeHtml(o.id)}</td>
                <td>${escapeHtml(o.title)}</td>
                <td><span class="pill ${o.priority}">${escapeHtml(o.priority)}</span></td>
                <td><span class="pill ${o.status}">${escapeHtml(o.statusLabel)}</span></td>
                <td>${escapeHtml(o.deviceName || o.deviceId || '—')}</td>
                <td>${escapeHtml(o.assignee || '未派单')}</td>
            </tr>`).join('')}</tbody>`;

        $('#dev-count').textContent = `${devices.length} 台`;
        $('#tbl-devices').innerHTML = `
            <thead><tr><th>编号</th><th>名称</th><th>位置</th><th>状态</th><th>关键指标</th></tr></thead>
            <tbody>${devices.map(d => `<tr>
                <td class="mono">${escapeHtml(d.id)}</td>
                <td>${escapeHtml(d.name)}</td>
                <td>${escapeHtml(d.location)}</td>
                <td><span class="pill ${d.status}">${escapeHtml(d.statusLabel)}</span></td>
                <td style="max-width:340px">${escapeHtml(d.keyMetrics || '—')}</td>
            </tr>`).join('')}</tbody>`;

        $('#tbl-engineers').innerHTML = `
            <thead><tr><th>姓名</th><th>电话</th><th>技能</th><th>在岗</th><th>在手工单</th></tr></thead>
            <tbody>${engineers.map(e => `<tr>
                <td>${escapeHtml(e.name)}</td>
                <td class="mono">${escapeHtml(e.phone)}</td>
                <td>${(e.skills || []).map(s => `<span class="tag">${escapeHtml(s)}</span>`).join(' ')}</td>
                <td>${e.onDuty ? '是' : '否'}</td>
                <td><b style="color:${e.activeOrders >= 3 ? 'var(--err)' : 'var(--indigo)'}">${e.activeOrders}</b></td>
            </tr>`).join('')}</tbody>`;
    } catch (e) {
        console.error('加载业务数据失败', e);
    }
}

/* ---------------------------------------------------------------- 工具函数 */

function escapeHtml(s) {
    return String(s == null ? '' : s)
        .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

function prettyJson(s) {
    if (s == null) return '';
    const str = String(s);
    try {
        return JSON.stringify(JSON.parse(str), null, 2);
    } catch (e) {
        return str;
    }
}

/** 极简 Markdown 渲染：够用于展示模型输出，不追求完备。 */
function mdToHtml(src) {
    if (!src) return '';
    const codeBlocks = [];
    let text = String(src).replace(/```(\w*)\n?([\s\S]*?)```/g, (m, lang, code) => {
        codeBlocks.push(`<pre style="background:#f6f9f9;padding:10px;border-radius:6px;overflow-x:auto;font-size:12.3px"><code>${escapeHtml(code)}</code></pre>`);
        return `\u0000CB${codeBlocks.length - 1}\u0000`;
    });

    text = escapeHtml(text);

    const lines = text.split('\n');
    const out = [];
    let listType = null;
    let tableRows = null;

    const closeList = () => { if (listType) { out.push(`</${listType}>`); listType = null; } };
    const closeTable = () => {
        if (!tableRows) return;
        const [head, ...body] = tableRows;
        out.push('<table><thead><tr>' + head.map(c => `<th>${inline(c)}</th>`).join('') + '</tr></thead><tbody>'
            + body.map(r => '<tr>' + r.map(c => `<td>${inline(c)}</td>`).join('') + '</tr>').join('')
            + '</tbody></table>');
        tableRows = null;
    };

    function inline(s) {
        return s
            .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
            .replace(/(^|[^*])\*([^*\n]+)\*/g, '$1<em>$2</em>')
            .replace(/`([^`]+)`/g, '<code>$1</code>');
    }

    for (let raw of lines) {
        const line = raw.trimEnd();

        if (/^\u0000CB\d+\u0000$/.test(line.trim())) {
            closeList(); closeTable();
            out.push(line.trim());
            continue;
        }
        if (/^\s*\|.*\|\s*$/.test(line)) {
            closeList();
            const cells = line.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map(c => c.trim());
            if (cells.every(c => /^:?-{2,}:?$/.test(c))) continue;   // 分隔行
            (tableRows = tableRows || []).push(cells);
            continue;
        }
        closeTable();

        let m;
        if ((m = line.match(/^(#{1,6})\s+(.*)$/))) {
            closeList();
            out.push(`<h4>${inline(m[2])}</h4>`);
        } else if ((m = line.match(/^\s*[-*+]\s+(.*)$/))) {
            if (listType !== 'ul') { closeList(); out.push('<ul>'); listType = 'ul'; }
            out.push(`<li>${inline(m[1])}</li>`);
        } else if ((m = line.match(/^\s*(\d+)[.)]\s+(.*)$/))) {
            if (listType !== 'ol') { closeList(); out.push('<ol>'); listType = 'ol'; }
            out.push(`<li>${inline(m[2])}</li>`);
        } else if (line.trim() === '') {
            closeList();
        } else {
            closeList();
            out.push(`<p style="margin:5px 0">${inline(line)}</p>`);
        }
    }
    closeList(); closeTable();

    let html = out.join('');
    html = html.replace(/\u0000CB(\d+)\u0000/g, (m, i) => codeBlocks[Number(i)]);
    return html;
}
