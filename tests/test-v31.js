// v3.1 桩测：与 Windows v1.3 同步修复订阅多节点导入
// 1) 完整身份去重键：同入口不同 uuid 全进、完全相同才去重、dupSkipped/skipReasons 返回；
// 2) 三类格式全量解析：链接清单 24 条同入口 vless、JSON 20 条同入口、Clash 21 条（含首行非 name）；
// 3) 坏行跳过与诊断：parseSubscriptionDetailed 返回 diag{format,rawLines,ok,skipped,firstSkip}，
//    Base64 去空白后解码、vmess 缺服务器判无效；
// 4) 卡片显示总数 sub.total、诊断行只在解析明显偏少（0 个或 < 一半）时出现；
// 5) 重复 URL 拒绝：addSubscription 与粘贴导入多 URL 均跳过已存在 URL；
// 6) 版本 v3.1。种子一律用假域名 *.synthetic.invalid 与假 UUID，绝不用真实订阅链接。
const fs = require('fs');
const vm = require('vm');
const path = require('path');
const BASE = '/home/hatch/workspace/nextin-android';
const html = fs.readFileSync(path.join(BASE, 'app/assets/index.html'), 'utf8');
const src = html.match(/<script>([\s\S]*)<\/script>/)[1];

function makeEl() {
  var cls = new Set();
  return { value: '', textContent: '', innerHTML: '', checked: false, style: {},
    classList: { add(c){ cls.add(c); }, remove(c){ cls.delete(c); },
      toggle(c, f){ if (f === undefined) { cls.has(c) ? cls.delete(c) : cls.add(c); } else { f ? cls.add(c) : cls.delete(c); } },
      contains(c){ return cls.has(c); } },
    querySelector(){ return null; }, addEventListener(){}, focus(){}, setSelectionRange(){}, };
}
const els = {};
const documentStub = {
  body: makeEl(),
  getElementById(id){ if(!els[id]) els[id]=makeEl(); return els[id]; },
  querySelectorAll(sel){ if (String(sel).indexOf('nav button') >= 0) return [makeEl(), makeEl(), makeEl()]; return []; },
  querySelector(){ return null; },
  createElement(){ return makeEl(); },
};
const store = {};
let fetchText = '';
const bridge = {
  fetchSubscription(){ return JSON.stringify({ok:true, text: fetchText, userinfo:''}); },
  apiGet(){ return JSON.stringify({ok:false, body:''}); },
  apiPatch(){ return JSON.stringify({ok:true}); },
  apiPut(){ return JSON.stringify({ok:true}); },
  tcpPing(){ return JSON.stringify({ok:true, ms: 12}); },
  getInstalledApps(){ return JSON.stringify({ok:true, apps:[]}); },
  saveAppRules(){ return '{"ok":true}'; },
  saveSettings(){ return '{"ok":true}'; },
  getCoreLog(){ return JSON.stringify({ok:true, log:''}); },
  coreStatus(){ return JSON.stringify({ok:true, running:false, apiReady:false, uptimeSec:0}); },
  getInfo(){ return JSON.stringify({ok:true, model:'Mi 6', sdk:28, 'sing-box':'1.14.4'}); },
  getAbi(){ return JSON.stringify({ok:true, abi:'arm64-v8a'}); },
  validateConfig(){ return JSON.stringify({ok:true}); },
  startCore(){ return JSON.stringify({ok:true, ready:true}); },
  stopCore(){ return '{"ok":true}'; },
  connectVpn(){ return '{"ok":true}'; },
  trafficOnce(){ return JSON.stringify({ok:false}); },
};
const sandbox = {
  console, setTimeout, clearTimeout, setInterval, clearInterval,
  window: {}, document: documentStub,
  localStorage: { getItem:k=> (k in store? store[k]:null), setItem:(k,v)=>{store[k]=v;}, removeItem:k=>{delete store[k];} },
  navigator: {}, URL, JSON, Math, Date, encodeURIComponent, decodeURIComponent, atob, btoa,
  AndroidBridge: bridge, confirm: () => true,
};
sandbox.window = sandbox;
vm.createContext(sandbox);
vm.runInContext(src, sandbox, { filename: 'index.html.js' });
function ev(code){ return vm.runInContext(code, sandbox); }
let pass = 0, fail = 0;
function check(name, cond, extra) {
  if (cond) { pass++; console.log('PASS', name); }
  else { fail++; console.log('FAIL', name, extra === undefined ? '' : JSON.stringify(extra)); }
}

// ---- 合成种子：24 条同入口 vless（Windows 复现案例 A 对照）----
function uuid(i){ return '11111111-1111-4111-8111-' + String(100000000000 + i); }
function vlessLine(i){
  return 'vless://' + uuid(i) + '@entry.synthetic.invalid:443?encryption=none&security=tls&sni=entry.synthetic.invalid&type=tcp#%E8%8A%82%E7%82%B9' + String(i).padStart(2, '0');
}
const LINES24 = Array.from({length: 24}, (_, k) => vlessLine(k + 1)).join('\n');
const JSON20 = JSON.stringify(Array.from({length: 20}, (_, k) => ({
  name: 'json-' + (k + 1), type: 'vless', server: 'entry.synthetic.invalid', port: 443,
  uuid: uuid(100 + k + 1), tls: true, servername: 'entry.synthetic.invalid', network: 'tcp',
})));
// Clash 21 条：首行字段轮换 type / server / uuid / name，另含 1 条 flow 式单行（计入 21 条内）
let clashParts = ['proxies:'];
for (let k = 1; k <= 20; k++) {
  if (k % 4 === 1) clashParts.push('  - type: vless', '    name: clash-' + k, '    server: entry.synthetic.invalid', '    port: 443', '    uuid: ' + uuid(200 + k), '    tls: true', '    servername: entry.synthetic.invalid');
  else if (k % 4 === 2) clashParts.push('  - server: entry.synthetic.invalid', '    name: clash-' + k, '    type: vless', '    port: 443', '    uuid: ' + uuid(200 + k), '    tls: true');
  else if (k % 4 === 3) clashParts.push('  - uuid: ' + uuid(200 + k), '    name: clash-' + k, '    type: vless', '    server: entry.synthetic.invalid', '    port: 443', '    tls: true');
  else clashParts.push('  - name: clash-' + k, '    type: vless', '    server: entry.synthetic.invalid', '    port: 443', '    uuid: ' + uuid(200 + k), '    tls: true');
}
clashParts.push('  - {name: clash-21, type: vless, server: entry.synthetic.invalid, port: 443, uuid: ' + uuid(221) + ', tls: true}');
const CLASH21 = clashParts.join('\n');

// ---- 0. 版本 ----
check('about 显示 v3.1 + 版本记录', ev('aboutText()').indexOf('v3.1') >= 0 && ev('aboutText()').indexOf('与 Windows v1.3 同步修复订阅多节点导入') >= 0, ev('aboutText()'));
check('源码无 v3.0 残留', !html.includes('v3.0'));
check('nodeKey 函数存在且用于去重', src.includes('function nodeKey(n)') && src.includes('var key=nodeKey(n);') && src.includes('nodeKey(x)'));
check('sanitizeNodes 返回 dupSkipped 与 skipReasons', src.includes('dupSkipped') && src.includes('skipReasons'));

// ---- 1. 完整身份去重键 ----
const dupRes = ev(`sanitizeNodes([
  {name:"A", type:"vless", server:"entry.synthetic.invalid", port:443, uuid:"${uuid(1)}"},
  {name:"B", type:"vless", server:"entry.synthetic.invalid", port:443, uuid:"${uuid(2)}"},
  {name:"A-copy", type:"vless", server:"entry.synthetic.invalid", port:443, uuid:"${uuid(1)}"}
],[],[])`);
check('同入口不同 uuid 不去重、完全相同才去重', dupRes.nodes.length === 2 && dupRes.dupSkipped === 1, dupRes);
check('dupSkipped 计数且 skipReasons 有去重原因', dupRes.dupSkipped === 1 && dupRes.skipReasons.length >= 1 && dupRes.skipReasons[0].indexOf('重复') >= 0, dupRes.skipReasons);
const keyDiff = ev(`nodeKey({type:"vless",server:"entry.synthetic.invalid",port:443,uuid:"${uuid(1)}"}) !== nodeKey({type:"vless",server:"entry.synthetic.invalid",port:443,uuid:"${uuid(2)}"})`);
check('nodeKey 区分不同 uuid', keyDiff === true);
const keySame = ev(`nodeKey({type:"ss",server:"ss.synthetic.invalid",port:8388,cipher:"aes-256-gcm",password:"pass"}) === nodeKey({type:"ss",server:"ss.synthetic.invalid",port:8388,cipher:"aes-256-gcm",password:"pass"})`);
check('nodeKey 完全相同节点键相同', keySame === true);

// ---- 2. 三类格式全量解析（复现：案例 A / JSON / Clash）----
const d24 = ev('parseSubscriptionDetailed(' + JSON.stringify(LINES24) + ')');
check('案例 A：24 条同入口 vless 首次解析 24 条', d24.nodes.length === 24 && d24.diag.ok === 24 && d24.diag.rawLines === 24 && d24.diag.skipped === 0, d24.diag);
check('案例 A 诊断格式为链接清单', d24.diag.format === '链接清单', d24.diag);
ev('state.nodes=[]; state.subs=[];');
const subA = ev('(function(){ var s={}; var added=importNodes(parseSubscriptionText(' + JSON.stringify(LINES24) + '), s); return {added:added, nodes:state.nodes.length, count:s.count, total:s.total}; })()');
check('案例 A：24 条首次导入全数入库', subA.added === 24 && subA.nodes === 24 && subA.count === 24 && subA.total === 24, subA);
const subA2 = ev('(function(){ var s={}; var added=importNodes(parseSubscriptionText(' + JSON.stringify(LINES24) + '), s); return {added:added, nodes:state.nodes.length, count:s.count, total:s.total, dup:s.dupSkipped}; })()');
check('案例 A：再加一次 0 条新入库、总数仍 24、dupSkipped 24', subA2.added === 0 && subA2.nodes === 24 && subA2.count === 0 && subA2.total === 24 && subA2.dup === 24, subA2);
const dJson = ev('parseSubscriptionDetailed(' + JSON.stringify(JSON20) + ')');
check('JSON 20 条同入口全解析', dJson.nodes.length === 20 && dJson.diag.format === 'JSON' && dJson.diag.rawLines === 20 && dJson.diag.ok === 20, dJson.diag);
const dClash = ev('parseSubscriptionDetailed(' + JSON.stringify(CLASH21) + ')');
check('Clash 21 条解析 21 条', dClash.nodes.length === 21 && dClash.diag.format === 'Clash YAML' && dClash.diag.rawLines === 21 && dClash.diag.ok === 21, dClash.diag);
check('Clash 首行非 name 条目未丢（type/server/uuid 首行均在）',
  dClash.nodes.some(n => n.name === 'clash-1') && dClash.nodes.some(n => n.name === 'clash-2') && dClash.nodes.some(n => n.name === 'clash-3') && dClash.nodes.some(n => n.name === 'clash-21'), dClash.nodes.map(n => n.name));

// ---- 3. 坏行跳过与诊断 ----
const badText = vlessLine(1) + '\n' + 'this-is-not-a-link' + '\n' + 'ssr://abc123' + '\n' + vlessLine(2) + '\n' + 'vless://' + uuid(3) + '@:443#bad';
const dBad = ev('parseSubscriptionDetailed(' + JSON.stringify(badText) + ')');
check('坏行只跳过并计数：5 行成功 2 跳过 3', dBad.nodes.length === 2 && dBad.diag.rawLines === 5 && dBad.diag.ok === 2 && dBad.diag.skipped === 3, dBad.diag);
check('诊断首个跳过原因只有摘要、不含行内容', typeof dBad.diag.firstSkip === 'string' && dBad.diag.firstSkip.length > 0 && dBad.diag.firstSkip.indexOf('this-is-not-a-link') < 0 && dBad.diag.firstSkip.indexOf('ssr://abc123') < 0, dBad.diag.firstSkip);
const vmessNoAdd = ev(`parseLink('vmess://' + b64e(JSON.stringify({ps:"no-server", port:"443", id:"${uuid(9)}"})))`);
check('vmess 缺服务器判无效行', vmessNoAdd === null);
const b64text = ev('parseSubscriptionDetailed(b64e(' + JSON.stringify(LINES24.split('\n').slice(0, 3).join('\n')) + '))');
check('Base64 订阅解析（去空白后解码）', b64text.nodes.length === 3, b64text.diag);
const b64ws = ev(`parseSubscriptionDetailed(b64e(${JSON.stringify(LINES24.split('\n').slice(0, 2).join('\n'))}).replace(/(.{20})/g, "$1\\n  "))`);
check('Base64 带换行空白仍解析', b64ws.nodes.length === 2, b64ws.diag);
const dEmpty = ev(`parseSubscriptionDetailed('')`);
check('空内容诊断', dEmpty.nodes.length === 0 && dEmpty.diag.format === '空内容', dEmpty.diag);
const dHtml = ev(`parseSubscriptionDetailed('<html><body>login</body></html>')`);
check('HTML 响应诊断为网页', dHtml.nodes.length === 0 && dHtml.diag.format === '网页（HTML）', dHtml.diag);

// ---- 4. 卡片显示总数 + 诊断行 ----
ev(`state.subs=[{name:"我的订阅", url:"https://subs.synthetic.invalid/abc", userinfo:"", updated:"2026/11/12", count:1, total:24, added:1, parseDiag:{format:"链接清单", rawLines:24, ok:24, skipped:0, firstSkip:""}},
  {name:"坏订阅", url:"https://subs.synthetic.invalid/bad", userinfo:"", updated:"2026/11/12", count:0, total:0, added:0, parseDiag:{format:"链接清单", rawLines:5, ok:0, skipped:5, firstSkip:"不是节点链接"}}]; renderSubs();`);
const subHtml = els.subList.innerHTML;
check('卡片显示总数 24 而非新入库数 1', subHtml.indexOf('>24 个节点<') >= 0 && subHtml.indexOf('>1 个节点<') < 0, subHtml.slice(0, 600));
check('0 节点卡出现红色诊断行', subHtml.indexOf('解析诊断：格式 链接清单 · 原始 5 行/条 · 成功 0 个 · 跳过 5 个') >= 0 && subHtml.indexOf('color:#B3261E') >= 0, subHtml.slice(0, 900));
check('诊断行只含原因摘要、不含订阅 URL', ev(`subDiagHtml({url:"https://subs.synthetic.invalid/bad?token=SECRET", parseDiag:{format:"链接清单", rawLines:5, ok:0, skipped:5, firstSkip:"不是节点链接"}})`).indexOf('subs.synthetic.invalid') < 0);
check('正常卡（24/24）不显示诊断行', (subHtml.match(/解析诊断/g) || []).length === 1, subHtml);
check('subDiagHtml 正常订阅返回空', ev(`subDiagHtml({parseDiag:{format:"链接清单", rawLines:24, ok:24, skipped:0, firstSkip:""}})`) === '');
check('subDiagHtml 偏少（2/5）返回诊断行', ev(`subDiagHtml({parseDiag:{format:"链接清单", rawLines:5, ok:2, skipped:3, firstSkip:"不是节点链接"}})`).indexOf('解析诊断') >= 0);

// ---- 5. 重复 URL 拒绝 ----
ev('state.nodes=[]; state.subs=[];');
fetchText = LINES24;
ev(`$("subUrl").value="https://subs.synthetic.invalid/dup"; $("subName").value="测试订阅"; $("subSite").value=""; addSubscription();`);
check('首次添加订阅成功', ev('state.subs.length') === 1 && ev('state.nodes.length') === 24, {subs: ev('state.subs.length'), nodes: ev('state.nodes.length')});
check('首次添加卡片 total=24', ev('state.subs[0].total') === 24 && ev('state.subs[0].count') === 24, ev('state.subs[0]'));
ev(`addSubscription();`);
check('同一 URL 再添加被拒绝、不重复建卡', ev('state.subs.length') === 1, ev('state.subs.length'));
check('拒绝时提示“该订阅已添加”', els.appToast.textContent === '该订阅已添加', els.appToast.textContent);
// 粘贴导入多 URL：一条已存在、一条新的
ev(`$("importText").value="https://subs.synthetic.invalid/dup\\nhttps://subs.synthetic.invalid/new-one"; doImport();`);
check('粘贴导入跳过重复 URL、只加新订阅', ev('state.subs.length') === 2, ev('state.subs.map(function(s){return s.url;})'));
check('粘贴导入新订阅 total=24', ev('state.subs[1].total') === 24, ev('state.subs[1]'));

// ---- 6. updateSub 同步 total/parseDiag ----
fetchText = LINES24.split('\n').slice(0, 5).join('\n');
const upd = ev('updateSub(0)');
check('updateSub 返回新导入数（已存在 24 条同入口，只剩 0 条新）', upd === 0, upd);
check('updateSub 后 total 同步为 5', ev('state.subs[0].total') === 5 && ev('state.subs[0].parseDiag') && ev('state.subs[0].parseDiag.ok') === 5, ev('state.subs[0].parseDiag'));

console.log(`\nTOTAL pass=${pass} fail=${fail}`);
process.exit(fail ? 1 : 0);
