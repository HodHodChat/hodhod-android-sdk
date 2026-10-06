// Pure-function vectors: inputs -> outputs of the REAL web helpers (template, markdown, validation, regex guard, conditions, triggers, split, URL).
import { register } from 'node:module';

register('./loader.mjs', import.meta.url);
const WEB = process.env.HODHOD_WEB_JS || '/Users/nobitex/bolbol/chatwoot/app/javascript';
const J = x => JSON.parse(JSON.stringify(x === undefined ? null : x));

export const buildVectors = async () => {
  const { renderTemplate, isAllowedUrl } = await import(`${WEB}/shared/helpers/chatbotFlow/template.js`);
  const { renderRich } = await import(`${WEB}/shared/helpers/chatbotFlow/rich.js`);
  const { validateInput } = await import(`${WEB}/shared/helpers/chatbotFlow/inputValidation.js`);
  const { analyzeRegex, compileSafe } = await import(`${WEB}/shared/helpers/chatbotFlow/regexGuard.js`);
  const { evaluateRule, evaluateTriggers, normalizeText, toAscii } = await import(`${WEB}/shared/helpers/chatbotFlow/evaluate.js`);
  const { bucketOf, pickVariant, fnv1a32 } = await import(`${WEB}/shared/helpers/chatbotFlow/split.js`);
  const { normalizeSettings } = await import(`${WEB}/shared/helpers/chatbotFlow/normalize.js`);

  const VARS = {
    'contact.name': 'علی', locale: 'fa', name: 'زهرا‌ی عزیز', empty: '', list: ['a', 'b'], n: 5, 'page.url': 'https://x.test/a b?q=1', nested: '{{name}}',
    long: 'x'.repeat(600), amp: 'a&b c/d?e=f#g', 'obj': { a: 1 }, 'fa_digits': '۱۲۳',
  };
  const templates = [
    'سلام {{contact.name}}', 'سلام {{ contact.name }}!', '{{unknown}}', '{{unknown|default:"پیش‌فرض"}}', '{{ unknown | default : "x y" }}', '{{empty|default:"E"}}',
    '{{list}}', '{{n}}', '\\{{name}}', '{{name}} {{nested}}', '{{long}}', '{{bad syntax}}', '{{ }}', '{{a.b.c}}', 'x {{ name', '{{NAME}}', '{{fa_digits}}۱۲۳', '{{obj}}',
    'متن\u200cبا\u200cنیم‌فاصله {{name}}', '{{name|default:"\u200c"}}', '{{amp}}', '{{contact.name}}{{locale}}', '{{n|default:"zz"}}', '{{default}}', '{{page.url}}',
  ];
  const tpl = [];
  templates.forEach(t => ['text', 'url'].forEach(ctx => tpl.push({ input: t, ctx, output: renderTemplate(t, VARS, ctx) })));

  const markdowns = [
    'ساده', '**bold**', '*it*', '_it_', 'snake_case_word', '**a *b* c**', '`code`', '[l](https://a.test)', '[l](javascript:alert(1))', '[l](mailto:a@b.co)', '[l]( https://a.test)',
    'https://a.test/x.', 'see https://a.test/x, and https://b.test/y!', 'اینجا https://a.test/ب', '- a\n- b', '1. a\n2. b\n\ntext', '- a\n\n1. b', 'line1\nline2', 'a\n\nb',
    '**unclosed', '*', '**', '`', '[x](', '{{name}} **{{name}}**', '[{{name}}](https://a.test/{{amp}})', '- {{n}}\r\n- b', '***x***', '__x__', 'a * b * c', '2*3*4', 'x_y_z _w_',
    'پیش **پررنگ** پس', 'ZWNJ\u200c**x**', '[a](https://a.test) [b](tel:+98911)', 'HTTPS://UPPER.TEST/x', 'a**b**c', '\n\n', '   ', 'h', 'http', 'http://',
    '**a**'.repeat(5), 'x'.repeat(4010),
  ];
  const md = markdowns.map(t => ({ input: t, output: J(renderRich(t, VARS)) }));

  const inputCases = [
    [{ inputType: 'text' }, 'a'], [{ inputType: 'text' }, '  '], [{ inputType: 'text', required: false }, '  '], [{ inputType: 'text', minLength: 3 }, 'ab'], [{ inputType: 'text', maxLength: 2 }, 'abc'],
    [{ inputType: 'text', maxLength: 2 }, '😀😀😀'], [{ inputType: 'text' }, 'x'.repeat(501)], [{ inputType: 'longtext' }, 'x'.repeat(2001)], [{ inputType: 'longtext', required: true }, 'a\nb'],
    [{ inputType: 'email' }, 'A@B.CO'], [{ inputType: 'email' }, 'a@b'], [{ inputType: 'email' }, 'a b@c.de'], [{ inputType: 'email' }, `${'a'.repeat(250)}@b.co`],
    [{ inputType: 'phone' }, '+98 (912) 345-6789'], [{ inputType: 'phone' }, '۰۹۱۲۳۴۵۶۷۸۹'], [{ inputType: 'phone' }, '١٢٣٤٥٦٧٨٩٠'], [{ inputType: 'phone' }, '123'], [{ inputType: 'phone' }, '0912345678901234'],
    [{ inputType: 'number' }, '۱۲٫۵'], [{ inputType: 'number' }, '12.5'], [{ inputType: 'number' }, '-3'], [{ inputType: 'number' }, '1e3'], [{ inputType: 'number', min: 5 }, '4'], [{ inputType: 'number', max: 5 }, '6'], [{ inputType: 'number', min: 0, max: 10 }, '10'],
    [{ inputType: 'date' }, '2026-02-29'], [{ inputType: 'date' }, '2024-02-29'], [{ inputType: 'date' }, '۲۰۲۶-۰۱-۰۱'], [{ inputType: 'date', min: '2026-06-01' }, '2026-05-31'], [{ inputType: 'date', max: '2026-06-01' }, '2026-06-02'],
    [{ inputType: 'url' }, 'https://a.test/x'], [{ inputType: 'url' }, 'ftp://a.test'], [{ inputType: 'url' }, 'http://'], [{ inputType: 'url' }, 'HTTP://A.TEST'],
    [{ inputType: 'text', regexPattern: '/^\\d{3}$/', regexCue: 'سه رقم' }, '۱۲۳'], [{ inputType: 'text', regexPattern: '/^\\d{3}$/', regexCue: 'سه رقم' }, '12'], [{ inputType: 'text', regexPattern: '/^[a-z]+$/i' }, 'ABC'],
    [{ inputType: 'text', regexPattern: '/^[a-z]+$/' }, 'ABC'], [{ inputType: 'text', regexPattern: '/^a.c$/' }, 'a\u2028c'], [{ inputType: 'text', regexPattern: '/^\\s+x$/' }, '\u00a0\u00a0x'], [{ inputType: 'longtext', regexPattern: '/^a/' }, 'a\nb'],
    [{ inputType: 'email', regexPattern: '/^a/' }, 'a@b.co'], [{ inputType: 'text', regexPattern: '^abc$' }, 'abc'], [{ inputType: 'text', regexPattern: '/(a+)+$/' }, 'aaaa!'], [{ inputType: 'number', regexPattern: '/^[1-9]/' }, '۰۷'],
    [{ inputType: 'text', regexPattern: '/^\\p{L}+$/u' }, 'ab'], [{ inputType: 'text', regexPattern: '/^\\w+@\\w+\\.com$/' }, 'a@b.com'], [{ inputType: 'text', regexPattern: '/a{2,3}b?/' }, 'caab'],
  ];
  const input = inputCases.map(([d, raw]) => ({ data: d, raw, result: J(validateInput({ data: { kind: 'input', ...d } }, raw)) }));

  const regexes = ['/^\\d+$/', '/(a+)+$/', '/(a|aa)*$/', '/(?:ab|cd){1,3}/', '/(?=x)/', '/(?<!x)y/', '/(a)\\1/', '/a{2}{3}/', '/[/', '/(/', '/a)/', '/x/g', '/x/iu', '/x/ii', 'plain', '/' + 'a'.repeat(201) + '/', '/(?<n>a)+/', '/(a+)?/', '/(a{1,2})*/', '/([a-z]+\\d)+/', '/[a-z]*+/', '/^(\\d{3}-?){2}$/', '/\\k<n>(?<n>a)/', '/a|b|c/', '/(a|b)/', '/(a|b)?/', '/(?:a|b)+/', '/\\(a+\\)+/', '/[(a+)]+/', ''];
  const regexVec = regexes.map(r => ({ input: r, analysis: J(analyzeRegex(r)), compiles: !!compileSafe(r) }));

  const baseCtx = { now: '2026-10-05T10:30:00Z', utcOffset: '+03:30', businessOpen: true, agentsOnline: false, locale: 'fa_IR', pageUrl: 'https://shop.test/Pricing/plans?x=1#h', device: 'desktop', returning: true };
  const toCtx = c => ({ ...c, now: new Date(c.now) });
  const rules = [
    { type: 'business_hours', operator: 'is', value: 'open' }, { type: 'business_hours', value: 'closed' }, { type: 'agents_online', value: 'no' }, { type: 'agents_online', value: 'yes' },
    { type: 'locale', operator: 'is_one_of', value: ['fa'] }, { type: 'locale', operator: 'is_one_of', value: ['en'] }, { type: 'locale', operator: 'is_not_one_of', value: ['en'] }, { type: 'locale', operator: 'is_one_of', value: ['f'] }, { type: 'locale', operator: 'equals', value: ['fa'] },
    { type: 'page_url', operator: 'contains', value: 'PRICING' }, { type: 'page_url', operator: 'contains', value: '/pricing' }, { type: 'page_url', operator: 'equals', value: '/pricing/plans' }, { type: 'page_url', operator: 'starts_with', value: 'https://shop' },
    { type: 'page_url', operator: 'ends_with', value: '#h' }, { type: 'page_url', operator: 'not_contains', value: 'nope' }, { type: 'page_url', operator: 'matches', value: '/plans\\?x=\\d/' }, { type: 'page_url', operator: 'matches', value: '/(a+)+$/' },
    { type: 'returning_visitor', value: 'true' }, { type: 'returning_visitor', value: true }, { type: 'returning_visitor', value: 'false' }, { type: 'device', value: ['desktop'] }, { type: 'device', value: ['mobile'] },
    { type: 'weekday', value: ['mon'] }, { type: 'weekday', value: ['sat', 'sun'] }, { type: 'time_of_day', operator: 'between', value: { from: '13:00', to: '14:30' } }, { type: 'time_of_day', operator: 'between', value: { from: '14:00', to: '14:01' } },
    { type: 'time_of_day', operator: 'not_between', value: { from: '14:00', to: '15:00' } }, { type: 'time_of_day', operator: 'between', value: { from: '22:00', to: '06:00' } }, { type: 'time_of_day', operator: 'between', value: { from: 'bad', to: '06:00' } },
    { type: 'variable', variable: 'a', operator: 'gt', value: '5' }, { type: 'variable', variable: 'a', operator: 'gte', value: '۷' }, { type: 'variable', variable: 'a', operator: 'lt', value: '7' }, { type: 'variable', variable: 'a', operator: 'lte', value: '{{b}}' },
    { type: 'variable', variable: 'a', operator: 'equals', value: '۷' }, { type: 'variable', variable: 's', operator: 'equals', value: 'علی' }, { type: 'variable', variable: 's', operator: 'not_equals', value: 'x' }, { type: 'variable', variable: 's', operator: 'contains', value: 'ل' },
    { type: 'variable', variable: 's', operator: 'not_contains', value: 'ل' }, { type: 'variable', variable: 's', operator: 'starts_with', value: 'ع' }, { type: 'variable', variable: 's', operator: 'ends_with', value: 'ي' }, { type: 'variable', variable: 'z', operator: 'is_set' },
    { type: 'variable', variable: 'e', operator: 'is_set' }, { type: 'variable', variable: 'e', operator: 'is_not_set' }, { type: 'variable', variable: 'arr', operator: 'contains', value: 'b' }, { type: 'variable', variable: 'arr', operator: 'is_set' },
    { type: 'variable', variable: 'a', operator: 'matches', value: '/^\\d$/' }, { type: 'variable', variable: 'p', operator: 'matches', value: '/^09\\d+$/' }, { type: 'variable', variable: 'a', operator: 'gt', value: '' }, { type: 'variable', variable: 'q', operator: 'gt', value: '1' },
    { type: 'variable', variable: 'hex', operator: 'gt', value: '10' }, { type: 'variable', variable: 'a', operator: 'unknown_op', value: '1' }, { type: 'nonsense', value: 1 },
  ];
  const ruleVars = { a: '۷', b: '7', s: 'علي', z: 'v', e: '', arr: ['a', 'b'], p: '۰۹۱۲۳', hex: '0x20', q: 'abc' };
  const ruleVec = [];
  [baseCtx, { ...baseCtx, locale: 'en', now: '2026-10-03T22:30:00Z', utcOffset: 'Asia/Tehran', returning: false, device: 'mobile', businessOpen: false, agentsOnline: true }].forEach(c => {
    rules.forEach(r => ruleVec.push({ rule: r, ctx: c, vars: ruleVars, result: evaluateRule(r, toCtx(c), ruleVars) }));
  });

  const trig = [
    {}, { visitor: 'new' }, { visitor: 'returning' }, { hours: 'open' }, { hours: 'closed' }, { locales: ['fa'] }, { locales: ['en', 'de'] }, { locales: ['fa_ir'] },
    { urlRules: [{ id: 'u1', match: 'contains', value: '/pricing', mode: 'show', startTopicId: 'tp' }] }, { urlRules: [{ id: 'u1', match: 'contains', value: '/nope', mode: 'show' }] },
    { urlRules: [{ id: 'u1', match: 'starts_with', value: 'https://shop', mode: 'hide' }] }, { urlRules: [{ id: 'u1', match: 'regex', value: '/plans\\?x=1$/', mode: 'show', startTopicId: 'rx' }, { id: 'u2', match: 'ends_with', value: '#h', mode: 'show', startTopicId: 'z' }] },
    { urlRules: [{ id: 'u1', match: 'equals', value: 'https://SHOP.test/pricing/plans?x=1#h', mode: 'show' }] }, { urlRules: [{ id: 'u1', match: 'contains', value: '', mode: 'show' }] },
    { visitor: 'returning', hours: 'open', locales: ['fa'], urlRules: [{ id: 'u1', match: 'contains', value: 'plans', mode: 'show', startTopicId: 'go' }] },
  ];
  const triggerVec = [];
  [baseCtx, { ...baseCtx, returning: false, businessOpen: false, locale: 'en', pageUrl: 'https://other.test/' }].forEach(c => trig.forEach(t => {
    const settings = normalizeSettings({ triggers: t });
    const r = evaluateTriggers(settings.triggers, toCtx(c));
    triggerVec.push({ triggers: t, ctx: c, show: r.show, startTopicId: r.startTopicId, via: r.via, ruleId: r.ruleId });
  }));

  const splitVec = [];
  ['s-1', 'session-alpha', '00000000-0000-4000-8000-000000000001', 'جلسه', ''].forEach(sid => ['n1', 'split_2', 'گره'].forEach(nid => {
    const b = bucketOf(sid, nid);
    splitVec.push({ session: sid, node: nid, fnv: fnv1a32(`${sid}:${nid}`), bucket: b, variant: pickVariant([{ id: 'a', weight: 30 }, { id: 'b', weight: 70 }], b), variant3: pickVariant([{ id: 'a', weight: 0 }, { id: 'b', weight: 0 }, { id: 'c' }], b) });
  }));
  [[], null, [{ id: 'only', weight: 5 }]].forEach(v => [0, 4999, 9999].forEach(b => splitVec.push({ session: '', node: '', fnv: 0, bucket: b, variants: v, variant: pickVariant(v, b), variant3: null })));

  const urls = ['https://a.test/x?y#z', 'http://A.Test:8080/Path/x%20y', 'https://user:pw@host.test/', 'mailto:a@b.co', 'tel:+98911', 'javascript:alert(1)', 'ftp://x.test/f', 'https://', 'not a url', '//x.test/y', '/relative/path', 'https://exa mple.com/', 'HTTPS://UPPER.COM', 'https://a.test', 'https:\\\\a.test\\x', 'data:text/plain,hi', ' https://a.test/ ', 'https://a.test/a b', 'https://[::1]/', 'x:y', ''];
  const urlVec = urls.map(u => {
    let parts = null;
    try { const p = new URL(u); parts = { protocol: p.protocol, hostname: p.hostname, pathname: p.pathname }; } catch (e) { parts = null; }
    return { url: u, parts, allowedHttps: isAllowedUrl(u, ['https:']), allowedAll: isAllowedUrl(u, ['https:', 'http:', 'mailto:', 'tel:']) };
  });

  const nums = ['', ' ', '0', '12', ' 12 ', '1e3', '-1.5', '.5', '5.', '0x1f', '0b11', '0o17', 'Infinity', '-Infinity', 'abc', '1 2', '+3', '--1', '1_000', '\u00a07\u00a0', '١٢٣'];
  const misc = {
    numbers: nums.map(s => ({ input: s, number: Number.isFinite(Number(s)) ? Number(s) : String(Number(s)) })),
    normalize: ['  ABC ', 'علي', 'كتاب ۱۲۳', '١٢٣abc', 'Ünï', ''].map(s => ({ input: s, normalize: normalizeText(s), ascii: toAscii(s) })),
  };
  return {
    template: { vars: VARS, cases: tpl }, markdown: { vars: VARS, cases: md }, input: { cases: input }, regex: { cases: regexVec }, rules: { cases: ruleVec },
    triggers: { cases: triggerVec }, split: { cases: splitVec }, url: { cases: urlVec }, misc: { cases: [misc] },
  };
};
