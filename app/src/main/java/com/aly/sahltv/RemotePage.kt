package com.aly.sahltv

/** The phone remote web page (served by RemoteServer). Plain HTML/JS, no outside files. */
object RemotePage {
    val HTML = """<!DOCTYPE html>
<html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
<meta name="theme-color" content="#0E1A24">
<title>Sahl TV Remote</title>
<style>
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
body{margin:0;background:#0E1A24;color:#EEF3F1;font-family:system-ui,-apple-system,"Segoe UI",Roboto,"Noto Sans Arabic",sans-serif}
.wrap{max-width:520px;margin:0 auto;padding:14px 14px 40px}
h1{font-size:19px;margin:6px 2px 12px;display:flex;justify-content:space-between;align-items:center}
h1 small{font-size:12px;color:#8FA39C;font-weight:500}
.card{background:#162630;border-radius:16px;padding:12px;margin-bottom:12px}
.card h2{font-size:12px;letter-spacing:.6px;text-transform:uppercase;color:#8FA39C;margin:0 0 10px}
.now{font-size:14px;margin-bottom:10px;min-height:18px;color:#cfe}
.row{display:flex;gap:8px}
button{flex:1;border:0;border-radius:12px;padding:14px 6px;font-size:18px;background:#22343C;color:#EEF3F1;font-family:inherit}
button:active{background:#3CC88F;color:#0E1A24}
button.p{background:#3CC88F;color:#0E1A24;font-weight:700}
input,textarea{width:100%;border:0;border-radius:12px;padding:12px;font-size:17px;background:#0E1A24;color:#EEF3F1;font-family:inherit}
textarea{min-height:64px;resize:vertical}
.apps{display:grid;grid-template-columns:repeat(2,1fr);gap:8px}
.apps button{font-size:14px;padding:12px 6px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.msg{font-size:13px;color:#F0B27A;margin-top:8px;min-height:16px}
.hide{display:none}
</style></head>
<body><div class="wrap">
<h1><span id="title">📺 Sahl TV</span><small id="status"></small></h1>

<div id="pairCard" class="card hide">
  <h2 id="pairH">Pair with your TV</h2>
  <p id="pairP" style="font-size:14px;color:#cfe;margin:0 0 10px">Enter the 6-digit code shown on the TV (☰ Launcher → Phone remote).</p>
  <input id="code" inputmode="numeric" maxlength="6" placeholder="123456">
  <div class="row" style="margin-top:10px"><button class="p" id="pairBtn">Pair</button></div>
  <div class="msg" id="pairMsg"></div>
</div>

<div id="main" class="hide">
  <div class="card">
    <h2 id="mediaH">Playing</h2>
    <div class="now" id="now">—</div>
    <div class="row">
      <button data-m="prev">⏮</button><button data-m="toggle" class="p">⏯</button><button data-m="next">⏭</button>
    </div>
    <div class="row" style="margin-top:8px">
      <button data-v="down">🔉</button><button data-v="mute">🔇</button><button data-v="up">🔊</button><button id="home">🏠</button>
    </div>
  </div>

  <div class="card">
    <h2 id="typeH">Type on the TV</h2>
    <textarea id="text" placeholder="Search, login…"></textarea>
    <div class="row" style="margin-top:8px">
      <button class="p" id="send">Send</button><button id="enter">⏎</button><button id="del">⌫</button><button id="done">✓</button>
    </div>
    <div class="msg" id="typeMsg"></div>
  </div>

  <div class="card">
    <h2 id="appsH">Apps</h2>
    <div class="apps" id="apps"></div>
  </div>
</div>
</div>
<script>
var token = localStorage.getItem('sahlToken') || '';
var ar = false;
var L = {
  en: {pairH:'Pair with your TV', pairP:'Enter the 6-digit code shown on the TV (☰ Launcher → Phone remote).', pairBtn:'Pair',
       wrong:'Wrong code — check the TV', mediaH:'Playing', nothing:'Nothing playing', typeH:'Type on the TV', send:'Send',
       ph:'Search, login…', appsH:'Apps', sent:'Sent ✓',
       kbNo:'Open a text box on the TV first. If nothing appears, finish the one-time keyboard setup (☰ Launcher → Phone remote).',
       off:'TV not reachable', on:'Connected'},
  ar: {pairH:'الاقتران بالتلفاز', pairP:'أدخل الرمز المكوّن من 6 أرقام الظاهر على التلفاز (☰ الواجهة ← الريموت من الهاتف).', pairBtn:'اقتران',
       wrong:'رمز خاطئ — تحقّق من التلفاز', mediaH:'يعمل الآن', nothing:'لا شيء قيد التشغيل', typeH:'الكتابة على التلفاز', send:'إرسال',
       ph:'بحث، تسجيل دخول…', appsH:'التطبيقات', sent:'تم الإرسال ✓',
       kbNo:'افتح خانة كتابة على التلفاز أولًا. إن لم يحدث شيء، أكمل إعداد لوحة المفاتيح لمرة واحدة (☰ الواجهة ← الريموت من الهاتف).',
       off:'التلفاز غير متاح', on:'متصل'}
};
function T(k){ return (ar ? L.ar : L.en)[k]; }
function el(id){ return document.getElementById(id); }
function api(path, body){
  return fetch(path, {method: body === undefined ? 'GET' : 'POST', headers: {'X-Token': token}, body: body === undefined ? undefined : body})
    .then(function(r){ if (r.status === 401) { showPair(); throw 'pair'; } return r.json(); });
}
function texts(){
  document.documentElement.dir = ar ? 'rtl' : 'ltr';
  ['pairH','pairP','mediaH','typeH','appsH'].forEach(function(k){ el(k).textContent = T(k); });
  el('pairBtn').textContent = T('pairBtn'); el('send').textContent = T('send'); el('text').placeholder = T('ph');
}
function showPair(){ el('main').classList.add('hide'); el('pairCard').classList.remove('hide'); }
function showMain(){ el('pairCard').classList.add('hide'); el('main').classList.remove('hide'); }
var appsKey = '';
function refresh(){
  api('/api/state').then(function(s){
    if (s.ar !== ar) { ar = s.ar; texts(); }
    showMain();
    el('status').textContent = T('on');
    el('now').textContent = s.now || T('nothing');
    var key = s.apps.map(function(a){ return a.p; }).join(',');
    if (key !== appsKey) {
      appsKey = key; var box = el('apps'); box.innerHTML = '';
      s.apps.forEach(function(a){
        var b = document.createElement('button'); b.textContent = a.l;
        b.onclick = function(){ api('/api/launch?pkg=' + encodeURIComponent(a.p), ''); };
        box.appendChild(b);
      });
    }
  }).catch(function(e){ if (e !== 'pair') el('status').textContent = T('off'); });
}
el('pairBtn').onclick = function(){
  fetch('/api/pair', {method:'POST', body: el('code').value.trim()}).then(function(r){ return r.json(); }).then(function(j){
    if (j.ok) { token = j.token; localStorage.setItem('sahlToken', token); refresh(); }
    else el('pairMsg').textContent = T('wrong');
  });
};
document.querySelectorAll('[data-m]').forEach(function(b){ b.onclick = function(){ api('/api/media?a=' + b.dataset.m, '').then(refresh); }; });
document.querySelectorAll('[data-v]').forEach(function(b){ b.onclick = function(){ api('/api/vol?d=' + b.dataset.v, ''); }; });
el('home').onclick = function(){ api('/api/home', ''); };
function typed(j){ el('typeMsg').textContent = j.ok ? T('sent') : T('kbNo'); setTimeout(function(){ el('typeMsg').textContent=''; }, 4000); }
el('send').onclick = function(){ var t = el('text').value; if (!t) return; api('/api/text', t).then(function(j){ if (j.ok) el('text').value=''; typed(j); }); };
el('enter').onclick = function(){ var t = el('text').value;
  var go = function(){ api('/api/key?k=enter', '').then(typed); };
  if (t) api('/api/text', t).then(function(j){ if (j.ok) { el('text').value=''; go(); } else typed(j); }); else go(); };
el('del').onclick = function(){ api('/api/key?k=del', '').then(typed); };
el('done').onclick = function(){ api('/api/kbdone', ''); el('typeMsg').textContent=''; };
texts();
if (!token) showPair(); else refresh();
setInterval(function(){ if (token) refresh(); }, 4000);
</script>
</body></html>"""
}
