// Deterministic runtime around the REAL web flow machine (useChatbotFlow.js): fake clock/timers/uuid, scripted webhook + handoff results,
// recorded snapshots. The Kotlin parity test replays the same scenario against the Kotlin port and compares every snapshot.
import { register } from 'node:module';

register('./loader.mjs', import.meta.url);

const WEB = process.env.HODHOD_WEB_JS || '/Users/nobitex/bolbol/chatwoot/app/javascript';
const { ref, computed } = await import('/Users/nobitex/bolbol/chatwoot/node_modules/vue/index.mjs').catch(() => import('vue'));
const { useChatbotFlow } = await import(`${WEB}/shared/composables/useChatbotFlow.js`);
const { normalizeFlow } = await import(`${WEB}/shared/helpers/chatbotFlow/normalize.js`);
const { migrateFlow, isTooNew } = await import(`${WEB}/shared/helpers/chatbotFlow/migrate.js`);
const { createMapperState, mapFlowEvent } = await import(`${WEB}/widget/helpers/flowEventMapper.js`);

export const BASE_NOW = 1790000000000;
export const BASE_CONTEXT = {
  now: '2026-10-03T10:00:00Z',
  utcOffset: '+00:00',
  businessOpen: true,
  agentsOnline: true,
  locale: 'fa',
  pageUrl: 'https://shop.test/pricing?x=1',
  device: 'desktop',
  returning: false,
  contact: { name: '', email: '', phone: '' },
  inboxId: 3,
  botId: 7,
  referrerHost: '',
};

const J = x => JSON.parse(JSON.stringify(x === undefined ? null : x));
export const tFn = (key, params) => (params ? `${key}:${JSON.stringify(params)}` : key);
const flush = () => new Promise(r => setImmediate(r));

export const runScenario = async sc => {
  const clock = { now: BASE_NOW };
  let uuidCounter = 0;
  Date.now = () => clock.now;
  Object.defineProperty(globalThis, 'crypto', {
    configurable: true,
    value: { randomUUID: () => `00000000-0000-4000-8000-${String((uuidCounter += 1)).padStart(12, '0')}` },
  });

  // ---- fake scheduler ----
  let timerSeq = 0;
  const timers = new Map();
  const scheduler = {
    setTimeout: (fn, ms) => {
      timerSeq += 1;
      timers.set(timerSeq, { id: timerSeq, at: clock.now + ms, fn });
      return timerSeq;
    },
    clearTimeout: id => timers.delete(id),
  };
  const nextTimer = limit => {
    let best = null;
    timers.forEach(t => {
      if (t.at <= limit && (!best || t.at < best.at || (t.at === best.at && t.id < best.id))) best = t;
    });
    return best;
  };
  const runUntil = async limit => {
    await flush();
    for (let guard = 0; guard < 10000; guard += 1) {
      const t = nextTimer(limit);
      if (!t) break;
      timers.delete(t.id);
      clock.now = Math.max(clock.now, t.at);
      t.fn();
      await flush();
    }
    clock.now = Math.max(clock.now, limit);
  };
  // settle = run timers due within the grace window (idle timers are >= 10 s and stay pending).
  const settle = async () => {
    await flush();
    const start = clock.now;
    for (let guard = 0; guard < 10000; guard += 1) {
      const t = nextTimer(start + 9000);
      if (!t) break;
      timers.delete(t.id);
      clock.now = Math.max(clock.now, t.at);
      t.fn();
      await flush();
    }
  };

  // ---- inputs ----
  const raw = sc.flow;
  const tooNew = isTooNew(raw);
  const doc = tooNew ? null : normalizeFlow(migrateFlow(raw));
  const out = { name: sc.name, description: sc.description || '', flow: raw, options: {}, doc: null, actions: [] };
  if (!doc) {
    out.tooNew = true;
    return out;
  }
  out.doc = J({ nodes: doc.nodes, edges: doc.edges, menu_order: doc.menu_order, require_flow: doc.require_flow, revision: doc.revision ?? null });
  const ctxRef = ref({ ...BASE_CONTEXT, ...(sc.context || {}) });
  const flowId = sc.flowId ?? 7;
  const revision = sc.revision ?? 3;
  const bot = sc.bot ?? null;
  const instant = sc.instant ?? true;
  const persist = !!sc.persist;
  out.options = { context: ctxRef.value, flowId, revision, bot, instant, persist, sessionId: sc.sessionId ?? 's-1', agent: sc.agent ?? ['ok'], webhooks: sc.webhooks ?? {} };

  const store = { saved: null };
  const persistence = persist
    ? { load: () => store.saved || null, save: s => { store.saved = JSON.parse(JSON.stringify(s)); }, clear: () => { store.saved = null; } }
    : null;
  const toCtx = c => ({ ...c, now: new Date(c.now), botId: flowId });
  const context = computed(() => toCtx(ctxRef.value));

  let events = [];
  let wire = [];
  let mapper = createMapperState();
  const agentCalls = [];
  const webhookCalls = [];
  let agentIdx = 0;
  const hookIdx = {};
  const agentScript = out.options.agent;
  const webhookScript = out.options.webhooks;

  const makeMachine = initialSession => useChatbotFlow({
    nodes: ref(doc.nodes), edges: ref(doc.edges), settings: ref(doc.settings), menuOrder: ref(doc.menu_order), context, t: tFn,
    sessionId: initialSession, flowId, revision,
    onRequestAgent: p => {
      agentCalls.push(J(p));
      const mode = agentScript[Math.min(agentIdx, agentScript.length - 1)];
      agentIdx += 1;
      if (mode === 'none') return undefined;
      if (mode === 'reject') return Promise.reject(new Error('x'));
      return Promise.resolve({ ok: true, conversationId: 42 });
    },
    onEvent: e => {
      events.push(J(e));
      const res = mapFlowEvent(mapper, e);
      wire.push(...J(res.events));
    },
    runWebhook: (node, vars) => {
      webhookCalls.push({ nodeId: node.id, vars: J(vars) });
      const list = webhookScript[node.id] || [{ ok: false, reason: 'config' }];
      const i = hookIdx[node.id] || 0;
      hookIdx[node.id] = i + 1;
      const r = list[Math.min(i, list.length - 1)];
      if (r === 'reject') return Promise.reject(new Error('net'));
      return Promise.resolve(r);
    },
    persistence, scheduler, reducedMotion: false, instant, bot,
  });

  let m = makeMachine(out.options.sessionId);

  const snapshot = (action, returned) => {
    const view = m.view.value;
    const snap = {
      view: J(view),
      variables: J(m.variables.value),
      trail: J(m.debug.trail.value),
      handoffState: m.handoffState.value,
      flowPath: m.flowPath.value,
      session: { id: m.session.value.id, restarts: m.session.value.restarts },
      debug: { values: J(m.debug.values.value), picks: J(m.debug.picks.value), varsSet: J(m.debug.varsSet.value) },
      events: J(events),
      wire: J(wire),
      agentCalls: J(agentCalls.splice(0)),
      webhookCalls: J(webhookCalls.splice(0)),
      persisted: J(store.saved),
      returned: J(returned),
      now: clock.now,
    };
    events = [];
    wire = [];
    out.actions.push({ action, expect: snap });
  };

  for (const a of sc.actions) {
    let returned;
    switch (a.op) {
      case 'start': m.start(a.nodeId ? { nodeId: a.nodeId } : undefined); break;
      case 'selectOption': m.selectOption(a.id); break;
      case 'chooseOption': m.chooseOption(a.id); break;
      case 'toggleOption': m.toggleOption(a.id); break;
      case 'setOtherText': m.setOtherText(a.text); break;
      case 'submitOther': m.submitOther(); break;
      case 'submitMulti': m.submitMulti(); break;
      case 'answerYesNo': m.answerYesNo(a.branch); break;
      case 'setInputValue': m.setInputValue(a.text); break;
      case 'submitInput': m.submitInput(); break;
      case 'editInput': m.editInput(); break;
      case 'confirmInput': m.confirmInput(); break;
      case 'skipInput': m.skipInput(); break;
      case 'submitRating': m.submitRating(a.value); break;
      case 'continueNext': m.continueNext(); break;
      case 'answerFeedback': m.answerFeedback(a.helpful); break;
      case 'chooseReason': m.chooseReason(a.id ?? null); break;
      case 'submitOffline': m.submitOffline(a.fields); break;
      case 'leaveMessage': m.leaveMessage(); break;
      case 'requestAgent': m.requestAgent({ reason: a.reason || 'manual' }); break;
      case 'retryHandoff': m.retryHandoff(); break;
      case 'goBack': m.goBack(); break;
      case 'restart': m.restart({ via: a.via || 'nav' }); break;
      case 'markLinkClick': m.markLinkClick({ kind: a.kind, buttonId: a.buttonId, url: a.url }); break;
      case 'markActivity': m.markActivity(); break;
      case 'markDirectStart': returned = m.markDirectStart(); break;
      case 'setContext': ctxRef.value = { ...ctxRef.value, ...a.patch }; break;
      case 'advance': await runUntil(clock.now + a.ms); break;
      case 'reload': {
        m.destroy();
        mapper = createMapperState();
        m = makeMachine(undefined);
        if (!m.hydrate()) m.start();
        break;
      }
      case 'destroy': m.destroy(); break;
      default: throw new Error(`unknown op ${a.op}`);
    }
    if (!a.noSettle && !['advance', 'setContext', 'markActivity'].includes(a.op)) await settle();
    snapshot(a, returned);
  }
  return out;
};
