// Flow definitions + scripted visitor actions. Each scenario is run by the REAL web machine (runtime.mjs) to record the expected trace.
const n = (id, kind, data = {}) => ({ id, type: 'flowNode', position: { x: 0, y: 0 }, data: { kind, ...data } });
const e = (source, target, h = null) => ({ id: `e_${source}_${target}${h ? `_${h}` : ''}`, source, target, sourceHandle: h });
const doc = (nodes, edges, extra = {}) => ({ schema: 2, nodes, edges, require_flow: false, menu_order: [], settings: {}, revision: 3, ...extra });
const A = {
  start: nodeId => ({ op: 'start', nodeId }),
  sel: id => ({ op: 'selectOption', id }),
  choose: id => ({ op: 'chooseOption', id }),
  text: (t, submit = true) => (submit ? [{ op: 'setInputValue', text: t }, { op: 'submitInput' }] : [{ op: 'setInputValue', text: t }]),
};
const flat = list => list.flat();

export const scenarios = [];
const add = sc => scenarios.push(sc);

// 1 ─ menu navigation, back, restart (topic, subtopic, question, answer)
add({
  name: 'menu-basic',
  description: 'welcome + topics + subtopic + question/answer, back and start over',
  flow: doc(
    [
      n('t1', 'topic', { label: 'پرداخت', prompt: 'کدام مورد؟', emoji: '💳' }),
      n('t2', 'topic', { label: 'حساب > کاربری' }),
      n('s1', 'subtopic', { label: 'کارمزد', prompt: 'درباره کارمزد' }),
      n('q1', 'question', { label: 'کارمزد چقدر است؟' }),
      n('a1', 'answer', { label: 'کارمزد **۰٫۲ درصد** است.', feedback: 'off' }),
      n('q2', 'question', { label: 'چطور واریز کنم؟' }),
      n('a2', 'answer', { label: 'از بخش واریز.', feedback: 'off' }),
    ],
    [e('t1', 's1'), e('s1', 'q1'), e('s1', 'q2'), e('q1', 'a1'), e('q2', 'a2')],
    { settings: { welcome: 'سلام! چه کمکی می‌خواهید؟', botName: 'هدهد' } },
  ),
  bot: { name: 'Bot', avatar_url: 'https://cdn.test/a.png' },
  actions: [A.start(), A.sel('t1'), A.sel('s1'), A.sel('q1'), { op: 'goBack' }, { op: 'goBack' }, A.sel('s1'), A.sel('q2'), { op: 'restart' }, A.sel('t2')],
});

// 2 ─ feedback yes → resolved, then start over rotates the session
add({
  name: 'answer-feedback-yes',
  description: 'answer feedback helpful -> resolved terminal + glad message, restart rotates session',
  flow: doc([n('t', 'topic', { label: 'T' }), n('q', 'question', { label: 'Q' }), n('a', 'answer', { label: 'جواب {{contact.name|default:"دوست"}}' })], [e('t', 'q'), e('q', 'a')], {
    settings: { feedback: { enabled: true, question: 'مفید بود؟', yesLabel: 'آره', noLabel: 'نه', gladMessage: 'خوشحالیم 🌟' } },
  }),
  actions: [A.start(), A.sel('t'), { op: 'answerFeedback', helpful: true }, { op: 'restart', via: 'resolved' }, A.sel('t'), { op: 'answerFeedback', helpful: true }],
});

// 3 ─ feedback no → reason → handoff live
add({
  name: 'answer-feedback-no-reasons-handoff',
  description: 'unhelpful answer, ask reason, not_helpful handoff (live) with variables in payload',
  flow: doc([n('t', 'topic', { label: 'T' }), n('a', 'answer', { label: 'Answer' })], [e('t', 'a')], {
    settings: { feedback: { enabled: true, askReason: true, reasonPrompt: 'چه شد؟', reasons: [{ id: 'r1', label: 'نامرتبط' }, { id: 'r2', label: 'ناقص' }] } },
  }),
  agent: ['ok'],
  actions: [A.start(), A.sel('t'), { op: 'answerFeedback', helpful: false }, { op: 'goBack' }, { op: 'answerFeedback', helpful: false }, { op: 'chooseReason', id: 'r2' }],
});

// 4 ─ message node: media, buttons, autoContinue, end(restart)
add({
  name: 'message-media-buttons',
  description: 'message with image + link buttons (unsafe filtered), autoContinue chain, end node restart',
  flow: doc(
    [
      n('m1', 'message', { label: 'خوش آمدید {{contact.name|default:"مهمان"}}', image: 'https://cdn.test/{{locale}}.png', imageAlt: 'لوگو {{locale}}', buttons: [
        { id: 'b1', label: 'سایت', url: 'https://x.test/?q={{locale}}' },
        { id: 'b2', label: 'خراب', url: 'javascript:alert(1)' },
        { id: 'b3', label: 'تماس', url: 'tel:+98911' },
        { id: 'b4', label: '', url: 'https://empty.test' },
      ], continueLabel: 'ادامه بده' }),
      n('m2', 'message', { label: 'پیام خودکار', autoContinue: true }),
      n('m3', 'message', { label: 'پایان **قطعی**' }),
      n('en', 'end', { label: 'تمام شد', then: 'restart' }),
    ],
    [e('m1', 'm2'), e('m2', 'm3'), e('m3', 'en')],
    { settings: { welcome: '' } },
  ),
  actions: [A.start('m1'), { op: 'markLinkClick', kind: 'button', buttonId: 'b1', url: 'https://x.test/' }, { op: 'continueNext' }, { op: 'continueNext' }, { op: 'restart', via: 'end_button' }],
});

// 5 ─ input text: validation, confirm, edit, templating into next message
add({
  name: 'input-text-confirm',
  description: 'text input min/max length, required error, confirm stage, edit, variable used in next message',
  flow: doc(
    [
      n('t', 'topic', { label: 'T' }),
      n('i1', 'input', { label: 'نام شما؟', inputType: 'text', variable: 'full_name', minLength: 3, maxLength: 10, confirm: true, placeholder: 'نام' }),
      n('m', 'message', { label: 'سلام {{full_name}} عزیز' }),
    ],
    [e('t', 'i1'), e('i1', 'm')],
  ),
  actions: flat([
    [A.start(), A.sel('t'), { op: 'submitInput' }],
    A.text('ab'),
    A.text('x'.repeat(11)),
    A.text('علی'),
    [{ op: 'editInput' }],
    A.text('  محمد  '),
    [{ op: 'goBack' }, { op: 'confirmInput' }, { op: 'submitInput' }, { op: 'confirmInput' }],
  ]),
});

// 6 ─ all input types incl. Persian digits
add({
  name: 'input-types-persian-digits',
  description: 'email/phone/number/date/url/longtext validation with Persian digits, ranges and failures',
  flow: doc(
    [
      n('t', 'topic', { label: 'T' }),
      n('e1', 'input', { label: 'ایمیل', inputType: 'email', variable: 'em' }),
      n('p1', 'input', { label: 'تلفن', inputType: 'phone', variable: 'ph', saveTo: 'contact_phone' }),
      n('n1', 'input', { label: 'تعداد', inputType: 'number', min: 1, max: 100, variable: 'cnt' }),
      n('d1', 'input', { label: 'تاریخ', inputType: 'date', min: '2026-01-01', max: '2026-12-31', variable: 'dt' }),
      n('u1', 'input', { label: 'سایت', inputType: 'url', variable: 'site' }),
      n('l1', 'input', { label: 'توضیح', inputType: 'longtext', maxLength: 30, variable: 'desc' }),
      n('m', 'message', { label: '{{em}}|{{ph}}|{{cnt}}|{{dt}}|{{site}}|{{desc}}' }),
    ],
    [e('t', 'e1'), e('e1', 'p1'), e('p1', 'n1'), e('n1', 'd1'), e('d1', 'u1'), e('u1', 'l1'), e('l1', 'm')],
  ),
  actions: flat([
    [A.start(), A.sel('t')],
    A.text('bad@'), A.text('  Ali@Example.COM '),
    A.text('0912'), A.text('۰۹۱۲-۳۴۵ ۶۷۸۹'),
    A.text('abc'), A.text('۰'), A.text('۱۰۱'), A.text('۴۲'),
    A.text('2025-13-01'), A.text('2025-12-31'), A.text('۲۰۲۶-۰۲-۳۰'), A.text('2026-06-15'),
    A.text('ftp://x'), A.text('https://هدهد.test/path'),
    A.text('سلام\nدنیا'),
  ]),
});

// 7 ─ regex guard (safe pattern with Persian digits, unsafe pattern ignored)
add({
  name: 'input-regex-guard',
  description: 'safe regex on Persian digits with cue, nested quantifier / lookahead patterns are ignored by the guard',
  flow: doc(
    [
      n('t', 'topic', { label: 'T' }),
      n('i1', 'input', { label: 'کد ملی', inputType: 'text', regexPattern: '/^\\d{10}$/', regexCue: 'ده رقم', variable: 'code' }),
      n('i2', 'input', { label: 'خطرناک', inputType: 'text', regexPattern: '/(a+)+$/', variable: 'bad' }),
      n('i3', 'input', { label: 'موبایل', inputType: 'phone', regexPattern: '/^09[0-9]{9}$/i', regexCue: 'با ۰۹ شروع شود' }),
      n('i4', 'input', { label: 'lookahead', inputType: 'text', regexPattern: '/^(?=a)b/', variable: 'la' }),
      n('i5', 'input', { label: 'بدون cue', inputType: 'number', regexPattern: '^[1-9]\\d*$' }),
      n('m', 'message', { label: 'ok {{code}} {{bad}} {{la}}' }),
    ],
    [e('t', 'i1'), e('i1', 'i2'), e('i2', 'i3'), e('i3', 'i4'), e('i4', 'i5'), e('i5', 'm')],
  ),
  actions: flat([
    [A.start(), A.sel('t')],
    A.text('123'), A.text('۱۲۳۴۵۶۷۸۹۰'),
    A.text('aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa!'),
    A.text('0912'), A.text('۰۹۱۲۳۴۵۶۷۸۹'),
    A.text('zzz'),
    A.text('0'), A.text('۷'),
  ]),
});

// 8 ─ optional input skip
add({
  name: 'input-optional-skip',
  description: 'optional input: explicit skip, empty submit -> skip event, saveTo attribute',
  flow: doc(
    [n('t', 'topic', { label: 'T' }), n('i1', 'input', { label: 'اختیاری ۱', required: false, skipLabel: 'بی‌خیال', variable: 'o1' }), n('i2', 'input', { label: 'اختیاری ۲', required: false, variable: 'o2' }), n('m', 'message', { label: 'o1=[{{o1|default:"-"}}] o2=[{{o2|default:"-"}}]' })],
    [e('t', 'i1'), e('i1', 'i2'), e('i2', 'm')],
  ),
  actions: [A.start(), A.sel('t'), { op: 'skipInput' }, { op: 'submitInput' }, { op: 'goBack' }, ...A.text('یک مقدار')],
});

// 9 ─ single choice with other + chips
add({
  name: 'choice-single-other',
  description: 'single choice (value vs label), other option with free text, chips style, unknown option ignored',
  flow: doc(
    [
      n('t', 'topic', { label: 'T' }),
      n('c1', 'choice', { label: 'ارز؟', variable: 'coin', allowOther: true, otherLabel: 'دیگر', options: [{ id: 'o1', label: 'تتر', value: 'USDT' }, { id: 'o2', label: 'بیت‌کوین' }] }),
      n('c2', 'choice', { label: 'سبک؟', style: 'chips', options: [{ id: 'x1', label: 'الف' }, { id: 'x2', label: 'ب' }] }),
      n('m', 'message', { label: 'coin={{coin}}' }),
      n('m2', 'message', { label: 'chosen b' }),
    ],
    [e('t', 'c1'), e('c1', 'c2', 'o1'), e('c1', 'c2', 'o2'), e('c1', 'c2', 'other'), e('c2', 'm', 'x1'), e('c2', 'm2', 'x2'), e('m', 'm2')],
  ),
  actions: [A.start(), A.sel('t'), A.choose('nope'), A.choose('other'), { op: 'setOtherText', text: '  لایت‌کوین  ' }, { op: 'submitOther' }, { op: 'goBack' }, A.choose('o1'), A.choose('x2')],
});

// 10 ─ multichoice
add({
  name: 'choice-multi',
  description: 'multi choice with min/max, other row, toggles, canSubmit gating, done',
  flow: doc(
    [
      n('t', 'topic', { label: 'T' }),
      n('c', 'choice', { label: 'چند مورد', multiple: true, minSelect: 2, maxSelect: 2, variable: 'picks', allowOther: true, doneLabel: 'ثبت', options: [{ id: 'a', label: 'A', value: 'va' }, { id: 'b', label: 'B' }, { id: 'c', label: 'C' }] }),
      n('m', 'message', { label: 'picks={{picks}}' }),
    ],
    [e('t', 'c'), e('c', 'm', 'next')],
  ),
  actions: [A.start(), A.sel('t'), { op: 'submitMulti' }, { op: 'toggleOption', id: 'a' }, { op: 'submitMulti' }, { op: 'toggleOption', id: 'b' }, { op: 'toggleOption', id: 'c' }, { op: 'toggleOption', id: 'b' },
    { op: 'toggleOption', id: 'other' }, { op: 'setOtherText', text: 'دیگری' }, { op: 'toggleOption', id: 'c' }, { op: 'submitMulti' }],
});

// 11 ─ yes/no, unconnected branch → agent
add({
  name: 'yesno-branches',
  description: 'yes/no with labels; the unconnected no-branch requests an agent (reason unconnected)',
  flow: doc([n('t', 'topic', { label: 'T' }), n('y', 'yesno', { label: 'مطمئنی؟', yesLabel: 'بله', noLabel: 'خیر' }), n('m', 'message', { label: 'پس ادامه' })], [e('t', 'y'), e('y', 'm', 'yes')]),
  agent: ['none'],
  actions: [A.start(), A.sel('t'), { op: 'answerYesNo', branch: 'maybe' }, { op: 'answerYesNo', branch: 'no' }],
});

// 12 ─ rating scales
add({
  name: 'rating-scales',
  description: 'stars5 + thanks prelude, emoji5, nps10 labels, thumbs without next + post thanks; out of range ignored',
  flow: doc(
    [
      n('t', 'topic', { label: 'T' }),
      n('r1', 'rating', { label: 'ستاره', scale: 'stars5', variable: 'stars', thanks: 'ممنون از {{stars}} ستاره', lowLabel: 'کم', highLabel: 'زیاد' }),
      n('r2', 'rating', { label: 'ایموجی', scale: 'emoji5' }),
      n('r3', 'rating', { label: 'NPS', scale: 'nps10', lowLabel: 'هرگز', highLabel: 'حتما' }),
      n('r4', 'rating', { label: 'شست', scale: 'thumbs', thanks: 'سپاس' }),
    ],
    [e('t', 'r1'), e('r1', 'r2'), e('r2', 'r3'), e('r3', 'r4')],
  ),
  actions: [A.start(), A.sel('t'), { op: 'submitRating', value: 9 }, { op: 'submitRating', value: 4 }, { op: 'submitRating', value: 3 }, { op: 'submitRating', value: 0 }, { op: 'submitRating', value: 8 }, { op: 'submitRating', value: 1 }, { op: 'restart' }],
});

// 13 ─ condition rule types
add({
  name: 'condition-context-rules',
  description: 'business_hours/agents_online/locale/page_url/returning/device/weekday/time_of_day with context changes',
  flow: doc(
    [
      n('t', 'topic', { label: 'T' }),
      n('c1', 'condition', { rules: [{ id: 'r1', type: 'business_hours', operator: 'is', value: 'closed' }, { id: 'r2', type: 'agents_online', operator: 'is', value: 'no' }] }),
      n('c2', 'condition', { rules: [{ id: 'l1', type: 'locale', operator: 'is_one_of', value: ['en', 'ar'] }, { id: 'l2', type: 'page_url', operator: 'contains', value: '/pricing' }] }),
      n('c3', 'condition', { rules: [{ id: 'w1', type: 'returning_visitor', operator: 'is', value: 'true' }, { id: 'w2', type: 'device', operator: 'is_one_of', value: ['mobile', 'tablet'] }] }),
      n('c4', 'condition', { rules: [{ id: 'k1', type: 'weekday', operator: 'is_one_of', value: ['sat', 'sun'] }, { id: 'k2', type: 'time_of_day', operator: 'between', value: { from: '09:00', to: '12:00' } }, { id: 'k3', type: 'time_of_day', operator: 'not_between', value: { from: '22:00', to: '06:00' } }] }),
      n('m1', 'message', { label: 'C1 closed' }), n('m1b', 'message', { label: 'C1 no agents' }), n('m1e', 'message', { label: 'C1 else' }),
      n('m2', 'message', { label: 'C2 locale' }), n('m2b', 'message', { label: 'C2 page' }), n('m2e', 'message', { label: 'C2 else' }),
      n('m3', 'message', { label: 'C3 returning' }), n('m3b', 'message', { label: 'C3 device' }), n('m3e', 'message', { label: 'C3 else' }),
      n('m4', 'message', { label: 'C4 weekend' }), n('m4b', 'message', { label: 'C4 morning' }), n('m4c', 'message', { label: 'C4 day' }), n('m4e', 'message', { label: 'C4 else' }),
    ],
    [
      e('t', 'c1'), e('c1', 'm1', 'r1'), e('c1', 'm1b', 'r2'), e('c1', 'm1e', 'else'),
      e('m1', 'c2'), e('m1b', 'c2'), e('m1e', 'c2'),
      e('c2', 'm2', 'l1'), e('c2', 'm2b', 'l2'), e('c2', 'm2e', 'else'),
      e('m2', 'c3'), e('m2b', 'c3'), e('m2e', 'c3'),
      e('c3', 'm3', 'w1'), e('c3', 'm3b', 'w2'), e('c3', 'm3e', 'else'),
      e('m3', 'c4'), e('m3b', 'c4'), e('m3e', 'c4'),
      e('c4', 'm4', 'k1'), e('c4', 'm4b', 'k2'), e('c4', 'm4c', 'k3'), e('c4', 'm4e', 'else'),
    ],
  ),
  context: { now: '2026-10-05T10:30:00Z', utcOffset: '+03:30' },
  actions: [
    A.start(), A.sel('t'),
    { op: 'setContext', patch: { businessOpen: false, locale: 'en', returning: true, device: 'mobile', now: '2026-10-03T08:00:00Z', utcOffset: '+00:00' } },
    { op: 'restart' }, A.sel('t'),
    { op: 'setContext', patch: { businessOpen: true, agentsOnline: false, locale: 'fa', returning: false, device: 'desktop', now: '2026-10-06T23:15:00Z', utcOffset: 'Asia/Tehran' } },
    { op: 'restart' }, A.sel('t'),
  ],
});

// 14 ─ variable conditions
add({
  name: 'condition-variables',
  description: 'variable operators: Persian digits gt/lte, equals with ي/ی normalisation, contains, starts/ends, is_set, matches (guarded)',
  flow: doc(
    [
      n('t', 'topic', { label: 'T' }),
      n('i', 'input', { label: 'مقدار', inputType: 'text', variable: 'v' }),
      n('c', 'condition', { rules: [
        { id: 'g', type: 'variable', variable: 'v', operator: 'gt', value: '10' },
        { id: 'e', type: 'variable', variable: 'v', operator: 'equals', value: 'علی' },
        { id: 'co', type: 'variable', variable: 'v', operator: 'contains', value: 'سلام' },
        { id: 'sw', type: 'variable', variable: 'v', operator: 'starts_with', value: 'ab' },
        { id: 'ew', type: 'variable', variable: 'v', operator: 'ends_with', value: 'xyz' },
        { id: 'ma', type: 'variable', variable: 'v', operator: 'matches', value: '/^09\\d+$/' },
        { id: 'un', type: 'variable', variable: 'nope', operator: 'is_not_set' },
      ] }),
      n('g', 'message', { label: 'gt' }), n('e', 'message', { label: 'eq' }), n('co', 'message', { label: 'contains' }), n('sw', 'message', { label: 'starts' }),
      n('ew', 'message', { label: 'ends' }), n('ma', 'message', { label: 'matches' }), n('un', 'message', { label: 'unset' }), n('el', 'message', { label: 'else' }),
    ],
    [e('t', 'i'), e('i', 'c'), e('c', 'g', 'g'), e('c', 'e', 'e'), e('c', 'co', 'co'), e('c', 'sw', 'sw'), e('c', 'ew', 'ew'), e('c', 'ma', 'ma'), e('c', 'un', 'un'), e('c', 'el', 'else')],
  ),
  actions: flat([
    [A.start(), A.sel('t')], A.text('۱۵'),
    [{ op: 'restart' }, A.sel('t')], A.text('علي'),
    [{ op: 'restart' }, A.sel('t')], A.text('ab سلام'),
    [{ op: 'restart' }, A.sel('t')], A.text('hello xyz'),
    [{ op: 'restart' }, A.sel('t')], A.text('۰۹۱۲۳'),
  ]),
});

// 15 ─ A/B split
add({
  name: 'split-ab',
  description: 'split assigns a stable variant per session/node, sets a variable, new bucket after the session rotates',
  flow: doc(
    [n('t', 'topic', { label: 'T' }), n('s', 'split', { variable: 'arm', variants: [{ id: 'va', label: 'A', weight: 30 }, { id: 'vb', label: 'B', weight: 70 }] }), n('ma', 'message', { label: 'arm A {{arm}}' }), n('mb', 'message', { label: 'arm B {{arm}}' }), n('en', 'end', { label: 'bye' })],
    [e('t', 's'), e('s', 'ma', 'va'), e('s', 'mb', 'vb'), e('ma', 'en'), e('mb', 'en')],
  ),
  sessionId: 'session-alpha',
  actions: [A.start(), A.sel('t'), { op: 'continueNext' }, { op: 'goBack' }, { op: 'restart' }, A.sel('t'), { op: 'continueNext' }, { op: 'restart' }, A.sel('t'), { op: 'continueNext' }, { op: 'restart' }, A.sel('t'), { op: 'continueNext' }],
});

// 16 ─ action set_variable
add({
  name: 'action-set-variable',
  description: 'action node: set_variable chain with templates and builtins; other action types only reported',
  flow: doc(
    [
      n('t', 'topic', { label: 'T' }),
      n('i', 'input', { label: 'نام', inputType: 'text', variable: 'nm' }),
      n('a', 'action', { types: ['set_variable', 'add_label'], actions: [{ id: 'a1', type: 'set_variable', variable: 'greet', value: 'سلام {{nm}}' }, { id: 'a2', type: 'set_variable', variable: 'both', value: '{{greet}}! {{locale}} \\{{raw}}' }] }),
      n('m', 'message', { label: '{{both}} / {{greet}}' }),
    ],
    [e('t', 'i'), e('i', 'a'), e('a', 'm')],
  ),
  actions: flat([[A.start(), A.sel('t')], A.text('زهرا'), [{ op: 'goBack' }]]),
});

// 17 ─ webhook success + cache
add({
  name: 'webhook-success-cached',
  description: 'webhook node via the proxy: success branch, mapped vars, response message, second pass served from cache',
  flow: doc(
    [
      n('t', 'topic', { label: 'T' }),
      n('i', 'input', { label: 'شماره سفارش', inputType: 'number', variable: 'order' }),
      n('w', 'webhook', { label: 'در حال بررسی...', responseMessage: 'وضعیت: {{status}}', errorMessage: 'خطا' }),
      n('ok', 'message', { label: 'نتیجه {{status}} برای {{order}}' }),
      n('bad', 'message', { label: 'ناموفق' }),
    ],
    [e('t', 'i'), e('i', 'w'), e('w', 'ok', 'success'), e('w', 'bad', 'error')],
  ),
  webhooks: { w: [{ ok: true, status: 'success', vars: { status: 'ارسال شد', skipme: { nested: 1 }, n: 5 }, cached: false }] },
  actions: flat([[A.start(), A.sel('t')], A.text('۱۲۳'), [{ op: 'restart' }, A.sel('t')], A.text('۱۲۳')]),
});

// 18 ─ webhook errors
add({
  name: 'webhook-error-branches',
  description: 'webhook failure reasons (timeout) -> error branch with default/custom message; network rejection -> config',
  flow: doc(
    [
      n('t', 'topic', { label: 'T' }),
      n('w1', 'webhook', { label: 'صبر کنید' }),
      n('w2', 'webhook', { errorMessage: 'خطای سفارشی' }),
      n('ok', 'message', { label: 'ok' }), n('bad', 'message', { label: 'bad1' }), n('bad2', 'message', { label: 'bad2' }), n('fin', 'end', { label: 'fin' }),
    ],
    [e('t', 'w1'), e('w1', 'ok', 'success'), e('w1', 'w2', 'error'), e('w2', 'bad2', 'error'), e('w2', 'ok', 'success'), e('bad2', 'fin')],
  ),
  webhooks: { w1: [{ ok: false, status: 'error', reason: 'timeout' }], w2: ['reject'] },
  actions: [A.start(), A.sel('t'), { op: 'goBack' }],
});

// 19 ─ goto
add({
  name: 'goto-targets',
  description: 'goto to a node, to $menu, to a note (dead end) and a missing target',
  flow: doc(
    [
      n('t1', 'topic', { label: 'اول' }), n('t2', 'topic', { label: 'دوم' }), n('t3', 'topic', { label: 'سوم' }), n('t4', 'topic', { label: 'چهارم' }),
      n('g1', 'goto', { targetId: 'dest' }), n('dest', 'message', { label: 'مقصد' }),
      n('g2', 'goto', { targetId: '$menu' }),
      n('g3', 'goto', { targetId: 'note1' }), n('note1', 'note', { label: 'یادداشت', color: 'amber' }),
      n('g4', 'goto', { targetId: 'missing' }),
    ],
    [e('t1', 'g1'), e('t2', 'g2'), e('t3', 'g3'), e('t4', 'g4')],
    { settings: { welcome: 'منو' } },
  ),
  agent: ['none'],
  actions: [A.start(), A.sel('t1'), { op: 'restart' }, A.sel('t2'), A.sel('t3'), { op: 'restart' }, A.sel('t4'), { op: 'requestAgent', reason: 'dead_end' }],
});

// 20 ─ loop guard
add({
  name: 'loop-guard',
  description: 'goto cycle between pass-through nodes trips the chain guard into a dead end',
  flow: doc([n('t', 'topic', { label: 'T' }), n('a', 'action', { actions: [] }), n('g', 'goto', { targetId: 'a' })], [e('t', 'a'), e('a', 'g')]),
  actions: [A.start(), A.sel('t')],
});

// 21 ─ handoff live via agent node
add({
  name: 'handoff-live-agent-node',
  description: 'agent node: payload (path, summary, trace, vars) and handoff_created after the promise resolves; saved session cleared',
  flow: doc(
    [n('t', 'topic', { label: 'پشتیبانی' }), n('i', 'input', { label: 'کد؟', inputType: 'text', variable: 'code' }), n('c', 'choice', { label: 'نوع', options: [{ id: 'o1', label: 'الف' }, { id: 'o2', label: 'ب' }] }), n('ag', 'agent', { teamId: 5 })],
    [e('t', 'i'), e('i', 'c'), e('c', 'ag', 'o1')],
  ),
  persist: true,
  agent: ['ok'],
  actions: flat([[A.start(), A.sel('t')], A.text('XY-1'), [A.choose('o1')], [{ op: 'goBack' }]]),
});

// 22 ─ handoff ticket
add({
  name: 'handoff-ticket-agent-node',
  description: 'agent node in ticket mode skips the offline step even when closed; kind=ticket in handoff_created',
  flow: doc(
    [n('t', 'topic', { label: 'T' }), n('i', 'input', { label: 'موضوع', inputType: 'text', variable: 'subject' }), n('ag', 'agent', { mode: 'ticket', ticketMessage: 'تیکت ثبت شد', ticketPriority: 'high', ticketCategoryId: '4' })],
    [e('t', 'i'), e('i', 'ag')],
    { settings: { offline: { mode: 'form', trigger: 'either', message: 'آفلاین' } } },
  ),
  context: { businessOpen: false },
  actions: flat([[A.start(), A.sel('t')], A.text('مشکل پرداخت')]),
});

// 23 ─ handoff failure + retry
add({
  name: 'handoff-failure-retry',
  description: 'rejected handoff shows handoff-error, back clears it, retry succeeds',
  flow: doc([n('t', 'topic', { label: 'T' }), n('ag', 'agent', {})], [e('t', 'ag')]),
  agent: ['reject', 'reject', 'ok'],
  actions: [A.start(), A.sel('t'), A.sel('ag'), { op: 'goBack' }, A.sel('ag'), { op: 'retryHandoff' }, { op: 'retryHandoff' }],
});

// 24 ─ offline form
add({
  name: 'handoff-offline-form',
  description: 'closed hours: offline form validation (channel missing, bad email), submit -> offline handoff with offline fields; back',
  flow: doc([n('t', 'topic', { label: 'T' }), n('ag', 'agent', {})], [e('t', 'ag')], {
    settings: { offline: { mode: 'form', trigger: 'closed', message: 'خارج از ساعت کاری', collect: ['name', 'email', 'phone'], required: ['email'] } },
  }),
  context: { businessOpen: false, contact: { name: 'سارا', email: '', phone: '' } },
  agent: ['ok'],
  actions: [A.start(), A.sel('t'), { op: 'submitOffline', fields: { message: 'سلام' } }, { op: 'submitOffline', fields: { email: 'nope', message: 'x' } },
    { op: 'goBack' }, { op: 'requestAgent' }, { op: 'submitOffline', fields: { email: 'S@A.IR', phone: '۰۹۱۲۳۴۵۶۷۸۹', message: 'لطفا تماس بگیرید' } }],
});

// 25 ─ offline message mode + no_agents trigger
add({
  name: 'handoff-offline-message',
  description: 'no agents online + message mode: leave a message proceeds with the original reason',
  flow: doc([n('t', 'topic', { label: 'T' }), n('a', 'answer', { label: 'جواب', feedback: 'on' })], [e('t', 'a')], {
    settings: { offline: { mode: 'message', trigger: 'no_agents', message: 'کسی آنلاین نیست' }, feedback: { enabled: true } },
  }),
  context: { agentsOnline: false },
  agent: ['ok'],
  actions: [A.start(), A.sel('t'), { op: 'answerFeedback', helpful: false }, { op: 'leaveMessage' }],
});

// 26 ─ direct start
add({
  name: 'direct-start-manual',
  description: 'direct start card: markDirectStart payload with partial trace; second call returns null',
  flow: doc([n('t', 'topic', { label: 'T' }), n('i', 'input', { label: 'q', inputType: 'text', variable: 'q' })], [e('t', 'i')]),
  actions: [A.start(), { op: 'markDirectStart' }, A.sel('t'), ...A.text('hello'), { op: 'markDirectStart' }],
});

// 27 ─ persistence/resume
add({
  name: 'persistence-resume',
  description: 'reload resumes the saved path (revisit, no duplicate events); TTL expiry starts fresh',
  flow: doc(
    [n('t', 'topic', { label: 'T' }), n('i', 'input', { label: 'نام', inputType: 'text', variable: 'nm' }), n('c', 'choice', { label: 'کدام', options: [{ id: 'o1', label: 'الف' }, { id: 'o2', label: 'ب' }] }), n('m', 'message', { label: 'سلام {{nm}}' })],
    [e('t', 'i'), e('i', 'c'), e('c', 'm', 'o1')],
  ),
  persist: true,
  actions: flat([[A.start(), A.sel('t')], A.text('ندا'), [{ op: 'reload' }, A.choose('o1'), { op: 'advance', ms: 26 * 60 * 1000 }, { op: 'reload' }]]),
});

// 28 ─ idle
add({
  name: 'idle-nudge',
  description: 'inactivity nudge after 10 s (nudge) and offer_agent variant; activity resets the timer',
  flow: doc([n('t', 'topic', { label: 'T' }), n('y', 'yesno', { label: 'سؤال' })], [e('t', 'y')], { settings: { idle: { enabled: true, afterSec: 10, message: 'هنوز اینجایید؟', action: 'offer_agent' } } }),
  actions: [A.start(), { op: 'advance', ms: 9000 }, { op: 'markActivity' }, { op: 'advance', ms: 9000 }, { op: 'advance', ms: 2000 }, A.sel('t'), { op: 'advance', ms: 11000 }, { op: 'requestAgent', reason: 'idle' }],
});

// 29 ─ typing delays
add({
  name: 'typing-delay',
  description: 'instant=false: welcome typing, per-node typingMs, control hidden while typing, progressive reveal',
  flow: doc([n('t', 'topic', { label: 'T', prompt: 'پیام اول', typingMs: 700 }), n('m', 'message', { label: 'پیام ربات', typingMs: 400, autoContinue: true }), n('en', 'end', { label: 'پایان' })], [e('t', 'm'), e('m', 'en')], { settings: { welcome: 'خوش آمدید', typingMs: 500 } }),
  instant: false,
  actions: [{ ...A.start(), noSettle: true }, { op: 'advance', ms: 300 }, { op: 'advance', ms: 300 }, { ...A.sel('t'), noSettle: true }, { op: 'advance', ms: 600 }, { op: 'advance', ms: 100 }, { op: 'advance', ms: 400 }, { op: 'advance', ms: 400 }, { op: 'advance', ms: 400 }],
});

// 30 ─ templating & markdown edge cases
add({
  name: 'templating-markdown-edges',
  description: 'Persian digits, ZWNJ, defaults, escapes, markdown lite (bold/italic/code/links/lists), url encoding, no re-expansion',
  flow: doc(
    [
      n('t', 'topic', { label: 'T' }),
      n('i', 'input', { label: 'q', inputType: 'longtext', variable: 'raw' }),
      n('m1', 'message', { label: 'نام: {{ contact.name | default : "مهمان‌عزیز" }} (می‌روم) ۱۲۳ {{unknown}}|{{ x.y }}|\\{{escaped}}|{{bad syntax}} **پررنگ** *کج* _کج۲_ snake_case_word `کد {{raw}}`' }),
      n('m2', 'message', { label: '- مورد اول\n- مورد دوم {{raw}}\n\n1. یک\n2. دو\n\n[لینک](https://a.test/p?q={{raw}}) و https://b.test/x. و [بد](javascript:alert(1)) [ایمیل](mailto:a@b.co)' }),
      n('m3', 'message', { label: 'page {{page.path}} {{page.host}} {{page.url}} {{locale}} {{device}} {{visitor.returning}} {{hours.state}} {{agents.online}} {{bot.name}} {{session.id}}' }),
      n('m4', 'message', { label: '**تو در تو *کج* پررنگ** و **ناتمام و `کد` [الف](https://x.test) و ***' }),
    ],
    [e('t', 'i'), e('i', 'm1'), e('m1', 'm2'), e('m2', 'm3'), e('m3', 'm4')],
  ),
  context: { contact: { name: 'shy-cloud-123', email: 'a@b.co', phone: '' } },
  bot: { name: 'هدهد‌بات', avatar_url: null },
  actions: flat([[A.start(), A.sel('t')], A.text('{{contact.email}} **x** & <b>y</b> a b\nسطر دوم ۱۲'), [{ op: 'continueNext' }, { op: 'continueNext' }, { op: 'continueNext' }]]),
});

// 31 ─ start from url_rule node + note target
add({
  name: 'trigger-start-node',
  description: 'start({nodeId}) enters the target (trigger via) ; note targets fall back to the menu',
  flow: doc([n('t', 'topic', { label: 'T' }), n('m', 'message', { label: 'deep' }), n('nt', 'note', { label: 'n' })], [e('t', 'm')]),
  actions: [A.start('m')],
});
add({
  name: 'trigger-start-note',
  description: 'start with a note node id shows the menu',
  flow: doc([n('t', 'topic', { label: 'T' }), n('nt', 'note', { label: 'n' })], []),
  actions: [A.start('nt'), A.sel('t')],
});

// 32 ─ end node variants
add({
  name: 'end-stay-and-restart-rotation',
  description: 'end(stay): resolved once, link to agent; later actions rotate the session id (flow_start restart)',
  flow: doc([n('t1', 'topic', { label: 'اول' }), n('t2', 'topic', { label: 'دوم' }), n('e1', 'end', { label: 'پایان **اول**' }), n('m', 'message', { label: 'پیام' })], [e('t1', 'e1'), e('t2', 'm')]),
  actions: [A.start(), A.sel('t1'), { op: 'goBack' }, A.sel('t2'), { op: 'continueNext' }, { op: 'restart' }, A.sel('t1')],
});

// 33 ─ back navigation substates
add({
  name: 'back-navigation',
  description: 'back out of confirm stage, reason stage, offline step; back past auto entries to the menu',
  flow: doc(
    [
      n('t', 'topic', { label: 'T' }), n('a', 'answer', { label: 'A', feedback: 'on' }),
      n('t2', 'topic', { label: 'T2' }), n('i', 'input', { label: 'ورودی', inputType: 'text', confirm: true }), n('ac', 'action', { actions: [] }), n('m', 'message', { label: 'پایان' }),
    ],
    [e('t', 'a'), e('t2', 'i'), e('i', 'ac'), e('ac', 'm')],
    { settings: { feedback: { enabled: true, askReason: true, reasons: [{ id: 'r', label: 'دلیل' }] }, offline: { mode: 'message', trigger: 'closed' } } },
  ),
  context: { businessOpen: false },
  actions: flat([
    [A.start(), A.sel('t'), { op: 'answerFeedback', helpful: false }, { op: 'goBack' }, { op: 'requestAgent', reason: 'dead_end' }, { op: 'goBack' }, { op: 'goBack' }, A.sel('t2')],
    A.text('abc'),
    [{ op: 'goBack' }, { op: 'goBack' }],
  ]),
});

// 34 ─ link click events
add({
  name: 'link-click-events',
  description: 'markLinkClick inline vs button, invalid host',
  flow: doc([n('t', 'topic', { label: 'T' }), n('m', 'message', { label: 'پیام', buttons: [{ id: 'bx', label: 'برو', url: 'https://Example.COM:8443/a?b#c' }] })], [e('t', 'm')]),
  actions: [A.start(), A.sel('t'), { op: 'markLinkClick', kind: 'inline', url: 'https://docs.test/x' }, { op: 'markLinkClick', kind: 'button', buttonId: 'bx', url: 'https://Example.COM:8443/a?b#c' }, { op: 'markLinkClick', kind: 'inline', url: 'not a url' }, { op: 'markLinkClick', kind: 'inline', url: 'mailto:a@b.co' }],
});

// 35 ─ menu details
add({
  name: 'menu-order-hidden-auto-open',
  description: 'menu_order, inMenu=false, single auto-open child, topic with no children, choice without options, question without answer',
  flow: doc(
    [
      n('t1', 'topic', { label: 'اول' }), n('t2', 'topic', { label: 'دوم' }), n('t3', 'topic', { label: 'پنهان', inMenu: false }), n('t4', 'topic', { label: 'خالی' }),
      n('t5', 'topic', { label: 'بدون گزینه' }), n('c5', 'choice', { label: 'گزینه؟', options: [] }), n('t6', 'topic', { label: 'سؤال بی‌جواب' }), n('q6', 'question', { label: 'Q' }),
      n('m2', 'message', { label: 'تک فرزند' }), n('s1', 'subtopic', { label: 'زیر' }),
    ],
    [e('t2', 'm2'), e('t5', 'c5'), e('t6', 'q6'), e('t1', 's1')],
    { menu_order: ['t2', 'ghost', 't1'] },
  ),
  agent: ['none'],
  actions: [A.start(), A.sel('t3'), A.sel('t2'), { op: 'goBack' }, A.sel('t4'), { op: 'goBack' }, A.sel('t5'), { op: 'goBack' }, A.sel('t6'), { op: 'goBack' }, A.sel('t1'), A.sel('s1')],
});

// 36 ─ v1 shaped definition (migration)
add({
  name: 'v1-migration',
  description: 'schema-less v1 document: confirm=true for inputs, teamId string, messages -> feedback settings',
  flow: {
    nodes: [n('t', 'topic', { label: 'T' }), n('i', 'input', { label: 'نام', inputType: 'text' }), n('a', 'answer', { label: 'جواب' }), n('ag', 'agent', { teamId: '5' })],
    edges: [e('t', 'i'), e('i', 'a'), e('a', 'ag', 'no')],
    messages: { did_this_help: 'کمک شد؟', yes_resolved: 'آره', no_need_agent: 'نه، کارشناس' },
  },
  actions: flat([[A.start(), A.sel('t')], A.text('رضا'), [{ op: 'confirmInput' }, { op: 'answerFeedback', helpful: false }]]),
});

// 37 ─ too new schema (engine must not run) – handled by loader; engine fixture is empty
add({
  name: 'schema-too-new',
  description: 'schema > 2: the loader refuses the flow',
  flow: { schema: 3, nodes: [n('t', 'topic', { label: 'T' })], edges: [] },
  actions: [],
});

// 38 ─ all in one: requested e2e-like flow (greeting -> choice -> input -> condition -> handoff ticket)
add({
  name: 'e2e-greeting-choice-input-condition-ticket',
  description: 'the emulator test flow: greeting, choice, input, condition on a variable, handoff to ticket',
  flow: doc(
    [
      n('t', 'topic', { label: 'مشکل دارم', prompt: 'سلام! مشکل شما چیست؟' }),
      n('c', 'choice', { label: 'کدام بخش؟', variable: 'area', options: [{ id: 'pay', label: 'پرداخت', value: 'payment' }, { id: 'acc', label: 'حساب' }] }),
      n('i', 'input', { label: 'مبلغ را وارد کنید', inputType: 'number', variable: 'amount', min: 0 }),
      n('cond', 'condition', { rules: [{ id: 'big', type: 'variable', variable: 'amount', operator: 'gt', value: '1000' }] }),
      n('live', 'agent', { teamId: 2 }),
      n('tk', 'agent', { mode: 'ticket', ticketMessage: 'تیکت شما ثبت شد', ticketSubject: '{{area}} {{amount}}' }),
    ],
    [e('t', 'c'), e('c', 'i', 'pay'), e('c', 'i', 'acc'), e('i', 'cond'), e('cond', 'live', 'big'), e('cond', 'tk', 'else')],
    { settings: { welcome: 'به هدهد خوش آمدید', botName: 'هدهد' } },
  ),
  agent: ['ok'],
  actions: flat([[A.start(), A.sel('t'), A.choose('pay')], A.text('۵۰۰')]),
});
add({
  name: 'e2e-greeting-choice-input-condition-live',
  description: 'same flow, big amount goes to the live agent branch',
  flow: scenarios.find(s => s.name === 'e2e-greeting-choice-input-condition-ticket').flow,
  agent: ['ok'],
  actions: flat([[A.start(), A.sel('t'), A.choose('acc')], A.text('۲۵۰۰۰')]),
});
