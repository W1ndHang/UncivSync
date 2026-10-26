// FsAdminPage.java - 管理页 HTML（/admin）
//
// 架构（2026-10-03 重写）：SPA 路由 + 脏检查数据层 + DOM 引用缓存。
//   - 路由：#/overview / #/room/<id> / #/settings，hashchange 驱动，切换零延迟
//   - 数据层：Store 统一管理，轮询拉数据后只更新 Store；视图订阅变化
//   - 渲染：每个视图 mount() 时用 createElement 构建完整 DOM 骨架（一次性），
//     update() 只对缓存的 DOM 引用做 textContent 赋值；**绝不 innerHTML 重写**
//   - 脏检查：数据 JSON.stringify 对比，相同则跳过 update → 零 DOM 操作 → 零卡顿
//   - 交互：事件委托（不写 inline onclick），chip/select 的状态由路由参数管理
package unciv.fs.local;

public final class FsAdminPage {
    private FsAdminPage() {}

    public static final String HTML = """
<!DOCTYPE html>
<html lang="zh">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>UncivSync Admin</title>
<style>
  /* ═══ 主题变量 ═══ */
  :root {
    --bg: #0f1419; --panel: #161d24; --input: #0b0f13;
    --border: #232d37; --border-hover: #3d5a78;
    --text: #d8dee6; --text-dim: #8b98a5; --text-muted: #6b7885;
    --accent: #7ec8a9; --accent-bg: #1d3a2a; --accent-border: #2d5a3a;
    --warn: #e0c97e; --warn-bg: #3a331d;
    --warn2: #e0b47e; --warn2-bg: #3a2d1d;
    --frozen-bg: #262b31; --frozen-text: #8b98a5;
    --link: #9fc3e8; --link-bg: #1d2c3a;
    --btn: #24405a; --btn-hover: #2d5175; --btn-text: #fff;
    --btn-alt-bg: #1d2731;
    --nav-hover: #1d2731;
    --purple: #a9a2e0; --purple-bg: #2a2a4a;
    --blue: #8fb3d0; --blue-bg: #26313a;
    --range-accent: #3d6a9a;
    --danger: #e8a2a2; --danger-border: #8a3b3b; --danger-bg: #3a1d1d;
    --shadow: rgba(0,0,0,0.3);
  }
  :root[data-theme="light"] {
    --bg: #f0f2f5; --panel: #ffffff; --input: #f5f6f8;
    --border: #d8dde3; --border-hover: #a0b4c8;
    --text: #1a2332; --text-dim: #5a6a7a; --text-muted: #8a95a0;
    --accent: #1a8a5c; --accent-bg: #e0f5ec; --accent-border: #a0d8c0;
    --warn: #8a6a20; --warn-bg: #fdf3d8;
    --warn2: #a05a20; --warn2-bg: #fceee0;
    --frozen-bg: #e8eaed; --frozen-text: #6a7580;
    --link: #1565a0; --link-bg: #e0ecf5;
    --btn: #d0dce8; --btn-hover: #c0d0e0; --btn-text: #1a2332;
    --btn-alt-bg: #e4e8ec;
    --nav-hover: #e4e8ec;
    --purple: #6a5acd; --purple-bg: #edeafa;
    --blue: #3a7a9a; --blue-bg: #e0f0f5;
    --range-accent: #2563a8;
    --danger: #c04040; --danger-border: #e0a0a0; --danger-bg: #fdf0f0;
    --shadow: rgba(0,0,0,0.08);
  }

  * { box-sizing: border-box; margin: 0; padding: 0; }
  body { font-family: "Segoe UI", "Microsoft YaHei", sans-serif; background: var(--bg); color: var(--text); height: 100vh; display: flex; transition: background .2s, color .2s; }
  .mono { font-family: Consolas, "Courier New", monospace; }

  #nav { width: 232px; min-width: 232px; background: var(--panel); border-right: 1px solid var(--border);
         display: flex; flex-direction: column; padding: 14px 10px; }
  #nav h1 { font-size: 16px; color: var(--accent); margin-bottom: 12px; padding-left: 6px; display: flex; align-items: center; gap: 6px; }
  #nav h1 .spacer { flex: 1; }
  .navBtn { background: var(--btn); color: var(--btn-text); border: none; border-radius: 5px; padding: 3px 8px; cursor: pointer; font-size: 12px; }
  .navBtn:hover { background: var(--btn-hover); }
  .navInfo { font-size: 12px; color: var(--text-dim); line-height: 1.9; padding: 0 6px 12px; border-bottom: 1px solid var(--border); }
  .navInfo b { color: var(--text); font-weight: 600; }
  .navItem { padding: 7px 10px; border-radius: 6px; cursor: pointer; font-size: 13px;
             margin-top: 4px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .navItem:hover { background: var(--nav-hover); }
  .navItem.active { background: var(--btn); color: var(--btn-text); }
  .navItem .dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%; margin-right: 6px; }
  #navRooms { flex: 1; overflow-y: auto; margin-top: 8px; }

  #main { flex: 1; overflow-y: auto; padding: 18px 22px; }
  h2 { font-size: 15px; color: var(--text-dim); margin-bottom: 12px; }
  .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 12px; }
  .card { background: var(--panel); border: 1px solid var(--border); border-radius: 8px; padding: 12px 14px;
          cursor: pointer; transition: border-color .12s, background .2s; }
  .card:hover { border-color: var(--border-hover); }
  .card .title { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
  .card .idx { background: var(--btn); color: var(--btn-text); border-radius: 4px; font-size: 12px; padding: 1px 7px; }
  .card .name { font-weight: 600; font-size: 14px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .card .row { display: flex; justify-content: space-between; font-size: 12px; color: var(--text-dim); line-height: 1.85; }
  .card .row b { color: var(--text); font-weight: 500; }

  .state { font-size: 12px; padding: 1px 8px; border-radius: 10px; white-space: nowrap; }
  .st-run { background: var(--accent-bg); color: var(--accent); }
  .st-settle { background: var(--warn-bg); color: var(--warn); }
  .st-paused { background: var(--warn2-bg); color: var(--warn2); }
  .st-frozen { background: var(--frozen-bg); color: var(--frozen-text); }

  .backBtn { background: none; border: 1px solid var(--border-hover); color: var(--link); border-radius: 6px;
             padding: 5px 14px; cursor: pointer; font-size: 13px; margin-bottom: 14px; }
  .backBtn:hover { background: var(--link-bg); }
  .detailGrid { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 10px; margin-bottom: 16px; }
  .kv { background: var(--panel); border: 1px solid var(--border); border-radius: 8px; padding: 10px 12px; }
  .kv .k { font-size: 12px; color: var(--text-dim); margin-bottom: 4px; }
  .kv .v { font-size: 14px; color: var(--text); }
  table { width: 100%; border-collapse: collapse; font-size: 13px; margin-bottom: 16px; }
  th, td { text-align: left; padding: 7px 10px; border-bottom: 1px solid var(--border); }
  th { color: var(--text-dim); font-weight: 500; font-size: 12px; }
  .yes { color: var(--accent); } .no { color: var(--text-muted); }

  .logBar { display: flex; align-items: center; gap: 8px; margin: 10px 0 8px; flex-wrap: wrap; }
  .chip { padding: 3px 12px; border-radius: 12px; border: 1px solid var(--border); cursor: pointer; font-size: 12px; color: var(--text-dim); }
  .chip.on { background: var(--btn); color: var(--btn-text); border-color: var(--border-hover); }
  select { background: var(--panel); color: var(--text); border: 1px solid var(--border); border-radius: 5px; padding: 3px 6px; font-size: 12px; }
  #logBox { background: var(--input); border: 1px solid var(--border); border-radius: 8px; padding: 10px 12px;
            font-family: Consolas, monospace; font-size: 12.5px; line-height: 1.7; min-height: 200px; max-height: 45vh; overflow-y: auto; }
  .ev-ts { color: var(--text-muted); } .ev-cat { border-radius: 3px; padding: 0 5px; margin-right: 6px; }
  .c-op { background: var(--accent-bg); color: var(--accent); }
  .c-turn { background: var(--purple-bg); color: var(--purple); }
  .c-player { background: var(--warn2-bg); color: var(--warn2); }
  .c-system { background: var(--blue-bg); color: var(--blue); }
  .empty { color: var(--text-muted); padding: 30px; text-align: center; }
  .idWrap { cursor: copy; }
  .idWrap:hover { color: var(--accent); }
  .copyHint { display: none; font-size: 11px; color: var(--accent); margin-left: 8px; white-space: nowrap; }
  .idWrap:hover .copyHint { display: inline; }

  .switch { width: 46px; height: 24px; border-radius: 12px; border: 1px solid var(--border-hover);
            background: var(--frozen-bg); position: relative; cursor: pointer; padding: 0; }
  .switch .knob { position: absolute; top: 2px; left: 2px; width: 18px; height: 18px;
                  border-radius: 50%; background: var(--text-dim); transition: left .15s, background .15s; }
  .switch.on { background: var(--accent-bg); border-color: var(--accent-border); }
  .switch.on .knob { left: 24px; background: var(--accent); }
  input[type=range] { accent-color: var(--range-accent); }
  input[type=range]:disabled { opacity: 0.3; cursor: not-allowed; }
  .setRow { display: flex; align-items: center; gap: 14px; margin-bottom: 14px; }
  .setLbl { font-size: 14px; }
  #saveHint { font-size: 13px; color: var(--accent); }
  #toast { position: fixed; top: 14px; right: 14px; background: var(--btn); color: var(--btn-text); padding: 8px 16px;
           border-radius: 6px; font-size: 13px; display: none; pointer-events: none; z-index: 999; box-shadow: 0 2px 8px var(--shadow); }
  .loading { color: var(--text-muted); font-style: italic; }
</style>
</head>
<body>
  <div id="nav">
    <h1>UncivSync <span class="spacer"></span><button id="themeBtn" class="navBtn" title="Theme">🌙</button><button id="langBtn" class="navBtn">EN/中文</button></h1>
    <div class="navInfo" id="navInfo"></div>
    <div style="font-size:12px;color:#8b98a5;padding:10px 6px 0;" id="navRoomsLabel"></div>
    <div id="navRooms"></div>
  </div>
  <div id="main"></div>
  <div id="toast"></div>

<script>
'use strict';

/* ═══ Data Store ═══ */
const Store = {
  overview: null, _ovJson: '',
  rooms: [],     _roomsJson: '',
  detail: null,  _detailJson: '',
  logs: null,    _logsJson: '',
  settings: -1,
  serverStartMs: 0,
};

/* ═══ i18n ═══ */
let lang = localStorage.getItem('adminLang') || 'zh';
let pw = localStorage.getItem('adminpw') || '';

const I18N = {
  zh: {
    navRooms:'房间', uptime:'运行时长', version:'版本', rooms:'房间',
    overview:'房间总览', noRooms:'当前没有房间', turn:'回合',
    turnN:n=>'第 '+n+' 回合', playersOnline:'玩家（在线/总数）',
    saveName:'存档名称', gameId:'存档标识 (gameId)', saveSize:'存档大小',
    lastPersist:'最后落盘', revision:'状态版本', pausedBy:'暂停者',
    frozenFor:'空房冻结时长', noSave:'无存档', ago:ms=>fmtDur(ms)+'前',
    back:'← 返回房间总览', players:'玩家',
    thName:'昵称', thCiv:'文明', thReady:'就绪', thStatus:'状态', thSeen:'最近活动',
    noOnline:'无在线玩家', roomLog:'房间日志', all:'全部',
    catOp:'op 操作', catTurn:'回合', catPlayer:'玩家', catSystem:'系统',
    count:'条数', noLogs:'暂无日志', roomClosed:'房间已关闭',
    pwdPrompt:'请输入管理口令（--admin-pass）:',
    copyHint:'📋 点击复制', copied:'已复制房间ID：', copyFail:'复制失败，请手动选择文本复制',
    netLost:'与服务端连接中断，自动重连中…',
    pauseRoom:'暂停回合', resumeRoom:'恢复回合',
    settings:'设置', autoClean:'闲置存档自动清理', roomUptime:'房间运行', mode:'模式',
    countdown:'倒计时',
    autoCleanDesc:'上一次回合推进距现在超过设定时长的房间，其存档将被自动删除（含玩家归属记录）。连接和操作都不计入，只看回合。',
    autoCleanOff:'已关闭', autoCleanRange:'12 ～ 168 小时（12 小时 ～ 一周）',
    autoCleanSwitch:'自动清理', autoCleanOnNote:'已开启',
    hours:'小时', day:'天', saved:'已保存',
    state:{run:'进行中',settle:'结算中',paused:'已暂停',frozen:'空房冻结'},
    modeT:{simultaneous:'同步',async:'异步'},
    host:'房主', loading:'加载中…',
    searchRoom:'🔍 查找房间（输入存档名）', roomNotFound:'未找到该房间',
    deleteRoom:'删除房间', downloadSave:'下载存档', confirmDelete:'确定要删除此房间及其存档吗？此操作不可撤销。',
    thAvgTurn:'平均时长', errorLog:'错误日志', noErrors:'暂无错误记录', thTime:'时间', thSource:'来源', thType:'类型', thMsg:'消息',
  },
  en: {
    navRooms:'Rooms', uptime:'Uptime', version:'Version', rooms:'Rooms',
    overview:'Room Overview', noRooms:'No rooms', turn:'Turn',
    turnN:n=>'Turn '+n, playersOnline:'Players (online/total)',
    saveName:'Save file', gameId:'Game ID', saveSize:'Save size',
    lastPersist:'Last persist', revision:'Revision', pausedBy:'Paused by',
    frozenFor:'Frozen for', noSave:'no save', ago:ms=>fmtDur(ms)+' ago',
    back:'← Back to overview', players:'Players',
    thName:'Nickname', thCiv:'Civ', thReady:'Ready', thStatus:'Status', thSeen:'Last seen',
    noOnline:'No players online', roomLog:'Room Log', all:'All',
    catOp:'ops', catTurn:'turn', catPlayer:'player', catSystem:'system',
    count:'Count', noLogs:'No events yet', roomClosed:'Room closed',
    pwdPrompt:'Enter admin password (--admin-pass):',
    copyHint:'📋 Click to copy', copied:'Room ID copied: ', copyFail:'Copy failed, please select the text manually',
    netLost:'Connection to server lost, reconnecting…',
    pauseRoom:'Pause turn', resumeRoom:'Resume turn',
    settings:'Settings', autoClean:'Auto-delete idle saves', roomUptime:'Room uptime', mode:'Mode',
    countdown:'Countdown',
    autoCleanDesc:'Rooms whose last turn advance is older than the threshold are deleted automatically. Connections and ops do not count - only turn advances.',
    autoCleanOff:'Disabled', autoCleanRange:'12 – 168 hours (12h – 1 week)',
    autoCleanSwitch:'Auto-clean', autoCleanOnNote:'Enabled',
    hours:'hours', day:'d', saved:'Saved',
    state:{run:'Running',settle:'Settling',paused:'Paused',frozen:'Frozen'},
    modeT:{simultaneous:'Simultaneous',async:'Async'},
    host:'Host', loading:'Loading…',
    searchRoom:'🔍 Find room (exact save name)', roomNotFound:'Room not found',
    deleteRoom:'Delete Room', downloadSave:'Download Save', confirmDelete:'Delete this room and its save? This cannot be undone.',
    thAvgTurn:'Avg Turn', errorLog:'Error Log', noErrors:'No errors recorded', thTime:'Time', thSource:'Source', thType:'Type', thMsg:'Message',
  }
};

const LOG_T = {
  zh: {
    room_create:a=>'房间创建（gameId='+a[0]+'）',
    join:a=>a[0]+' 加入'+(a[2]?'（观战）':''),
    reconnect:a=>a[0]+' 重连（顶掉旧连接）',
    leave:a=>a[0]+' 离开（掉线宽限计时开始）',
    pause:a=>a[0]+' 暂停了对局', resume:a=>a[0]+' 恢复了对局',
    deadline_armed:a=>'动态倒计时起表：第 '+a[0]+' 回合 '+a[1]+' 秒',
    deadline_forced:a=>'⏰ 倒计时到期 → 强制全员就绪（第 '+a[0]+' 回合）',
    turn_advance:a=>'回合 '+a[0]+' → '+a[1]+'（已落盘，rev='+a[2]+'）',
    turn_skip_dup:a=>'跳过重复推进（调度时第 '+a[0]+' 回合，当前已是第 '+a[1]+'）',
    empty_freeze:()=>'最后一个真人离开 → 空房冻结',
    op_ok:a=>a[0]+' '+a[1]+' ✓（rev='+a[2]+'）',
    op_deferred:a=>a[0]+' '+a[1]+' ✓（延后到下回合）',
    op_reject:a=>a[0]+' '+a[1]+' ✗ 拒绝：'+a[2],
    op_fail:a=>a[0]+' '+a[1]+' ✗ '+a[2],
  },
  en: {
    room_create:a=>'Room created (gameId='+a[0]+')',
    join:a=>a[0]+' joined'+(a[2]?' (spectator)':''),
    reconnect:a=>a[0]+' reconnected',
    leave:a=>a[0]+' left (offline grace timer started)',
    pause:a=>a[0]+' paused the game', resume:a=>a[0]+' resumed the game',
    deadline_armed:a=>'Countdown armed: turn '+a[0]+', '+a[1]+'s',
    deadline_forced:a=>'⏰ Countdown expired → force-ready all (turn '+a[0]+')',
    turn_advance:a=>'Turn '+a[0]+' → '+a[1]+' (persisted, rev='+a[2]+')',
    turn_skip_dup:a=>'Skipped duplicate advance (turn '+a[0]+' → '+a[1]+')',
    empty_freeze:()=>'Last human left → room frozen',
    op_ok:a=>a[0]+' '+a[1]+' ✓ (rev='+a[2]+')',
    op_deferred:a=>a[0]+' '+a[1]+' ✓ (deferred)',
    op_reject:a=>a[0]+' '+a[1]+' ✗ rejected: '+a[2],
    op_fail:a=>a[0]+' '+a[1]+' ✗ '+a[2],
  }
};
function t(){return I18N[lang];}
function renderEvent(e){
  const tpl=(LOG_T[lang]||LOG_T.zh)[e.code];
  if(tpl){try{return tpl(e.args||[]);}catch(_){}}
  return e.code+' '+(e.args||[]).join(' ');
}

/* ═══ Utilities ═══ */
function api(p){return fetch(p+(p.includes('?')?'&':'?')+'pw='+encodeURIComponent(pw));}
function toast(msg){const x=$('toast');x.textContent=msg;x.style.display='block';setTimeout(()=>x.style.display='none',2500);}
function fmtDur(ms){
  if(ms<0)return'—';const zh=lang==='zh',U=zh?['秒','分','时','天']:['s','m','h','d'];
  const s=Math.floor(ms/1000);if(s<60)return s+U[0];
  const m=Math.floor(s/60);if(m<60)return m+U[1]+(s%60)+U[0];
  const h=Math.floor(m/60);if(h<24)return h+U[2]+(m%60)+U[1];
  return Math.floor(h/24)+U[3]+(h%24)+U[2];
}
function fmtUptime(ms){
  if(ms<0)return'—';const s=Math.floor(ms/1000);
  const h=Math.floor(s/3600),m=Math.floor((s%3600)/60),sec=s%60;
  const p=n=>String(n).padStart(2,'0');return h+':'+p(m)+':'+p(sec);
}
function fmtBytes(b){
  if(b<=0)return'—';if(b<1024)return b+' B';if(b<1048576)return(b/1024).toFixed(1)+' KB';
  return(b/1048576).toFixed(1)+' MB';
}
function fmtClock(ms){
  const d=new Date(ms),p=n=>String(n).padStart(2,'0');
  return p(d.getHours())+':'+p(d.getMinutes())+':'+p(d.getSeconds());
}
function copyText(txt){
  // 先同步 execCommand：在点击手势内执行，嵌入式浏览器/非安全上下文都可用；失败再走异步 Clipboard API
  if(fbCopy(txt))return Promise.resolve(true);
  if(navigator.clipboard&&window.isSecureContext)return navigator.clipboard.writeText(txt).then(()=>true).catch(()=>false);
  return Promise.resolve(false);
}
function fbCopy(txt){
  try{const ta=document.createElement('textarea');ta.value=txt;ta.style.cssText='position:fixed;opacity:0';
  document.body.appendChild(ta);ta.select();const ok=document.execCommand('copy');document.body.removeChild(ta);return ok;}
  catch(e){return false;}
}
function copyId(id){copyText(id).then(ok=>toast(ok?t().copied+id:t().copyFail));}
async function handle401(r){
  if(r.status===401){pw=prompt(t().pwdPrompt,pw)||'';localStorage.setItem('adminpw',pw);return true;}
  return false;
}
function $(id){return document.getElementById(id);}
function el(tag,cls,text){const e=document.createElement(tag);if(cls)e.className=cls;if(text!=null)e.textContent=text;return e;}

/* ═══ Router ═══ */
let currentView=null;
let currentGameId=null;

function parseRoute(){
  const h=location.hash||'#/overview';
  const m=h.match(/^#\\/room\\/(.+)$/);
  if(m)return{view:'room',gameId:decodeURIComponent(m[1])};
  if(h==='#/settings')return{view:'settings'};
  if(h==='#/errors')return{view:'errors'};
  return{view:'overview'};
}
function navigate(){
  const route=parseRoute();
  const main=$('main');
  if(currentView&&currentView.destroy)currentView.destroy();
  main.innerHTML='';
  currentGameId=route.gameId||null;
  try{
    if(route.view==='room')currentView=RoomDetailView(main,route.gameId);
    else if(route.view==='settings')currentView=SettingsView(main);
    else if(route.view==='errors')currentView=ErrorsView(main);
    else currentView=OverviewView(main);
  }catch(e){
    main.appendChild(el('div','empty','Error: '+e.message));
    console.error(e);
  }
  renderNav();
  // 立即拉数据（不等下一个轮询周期）
  if(route.view==='room')pollDetail();
}
window.addEventListener('hashchange',navigate);

/* ═══ Nav ═══ */
// 搜索框只创建一次（不随轮询重建，否则丢失焦点/输入）
let _searchBoxInit = false;
function initSearchBox() {
  if (_searchBoxInit) return;
  _searchBoxInit = true;
  const nav = $('navRooms');
  const box = el('div');
  box.id = 'searchBox';
  box.style.cssText = 'padding:6px 4px 8px;border-bottom:1px solid var(--border);margin-bottom:6px';
  const input = el('input');
  input.type = 'text';
  input.id = 'roomSearch';
  input.style.cssText = 'width:100%;background:var(--input);color:var(--text);border:1px solid var(--border);border-radius:6px;padding:6px 10px;font-size:13px;outline:none';
  input.addEventListener('keydown', e => {
    if (e.key !== 'Enter') return;
    const q = input.value.trim();
    if (!q) return;
    const hit = Store.rooms.find(r => r.gameId === q);
    if (hit) location.hash = '#/room/' + encodeURIComponent(hit.gameId);
    else { toast(t().roomNotFound); input.select(); }
  });
  box.appendChild(input);
  nav.appendChild(box);
}

function updateSearchPlaceholder() {
  const input = $('roomSearch');
  if (input) input.placeholder = t().searchRoom;
}

let _navRoomsJson=null;   // null = 从未渲染过（不能用 '' —— 初始 location.hash 也是 ''，会导致首次加载跳过渲染）
function renderNav(){
  initSearchBox();
  updateSearchPlaceholder();
  // 只重建导航入口（不动搜索框）；只有 hash 变了才重绘
  const json = location.hash;
  if (json === _navRoomsJson) return;
  _navRoomsJson = json;
  // 删除搜索框后面的所有旧导航项
  const nav = $('navRooms');
  while (nav.children.length > 1) nav.removeChild(nav.lastChild);
  const L = t();
  const si = el('div','navItem'+(parseRoute().view==='settings'?' active':''),'⚙ '+L.settings);
  si.onclick = () => location.hash = '#/settings';
  nav.appendChild(si);
  const ei = el('div','navItem'+(parseRoute().view==='errors'?' active':''),'⚠ '+L.errorLog);
  ei.onclick = () => location.hash = '#/errors';
  nav.appendChild(ei);
  const oi = el('div','navItem'+(parseRoute().view==='overview'?' active':''),'▸ '+L.overview);
  oi.onclick = () => location.hash = '#/overview';
  nav.appendChild(oi);
}

/* ═══ Nav Info（运行时长/版本/房间数）═══ */
let _navInfoEls=null;
function renderNavInfo(){
  const L=t();const info=$('navInfo');
  if(!_navInfoEls){
    info.innerHTML='';
    const mk=lbl=>{const s=el('span',null,lbl+' ');const b=el('b','mono');info.append(s,b,el('br'));return{s,b};};
    const a=mk(L.uptime),b=mk(L.version),c=mk(L.rooms);
    _navInfoEls={u:a,v:b,r:c};
    _navInfoEls.u.b.id='uptimeVal';
  }
  const o=Store.overview;
  _navInfoEls.u.s.textContent=L.uptime+' ';
  _navInfoEls.v.s.textContent=L.version+' ';
  _navInfoEls.r.s.textContent=L.rooms+' ';
  _navInfoEls.u.b.textContent=o?fmtUptime(o.uptimeMs):'—';
  _navInfoEls.v.b.textContent=o?o.version:'—';
  _navInfoEls.r.b.textContent=o?o.rooms:'—';
  if(o)Store.serverStartMs=Date.now()-o.uptimeMs;
}
setInterval(()=>{
  const u=$('uptimeVal');
  if(u&&Store.serverStartMs>0)u.textContent=fmtUptime(Date.now()-Store.serverStartMs);
},1000);

/* ═══ View: Overview ═══ */
function OverviewView(container){
  const h2=el('h2');
  const grid=el('div','grid');
  const empty=el('div','empty');
  container.append(h2,grid,empty);
  let _json='';
  function update(){
    const json=JSON.stringify(Store.rooms);
    if(json===_json)return;
    _json=json;
    const L=t();
    h2.textContent=L.overview+(lang==='zh'?'（':'(')+Store.rooms.length+(lang==='zh'?'）':')');
    empty.textContent=L.noRooms;
    empty.style.display=Store.rooms.length?'none':'block';
    grid.style.display=Store.rooms.length?'grid':'none';
    grid.innerHTML='';
    for(const r of Store.rooms){
      const card=el('div','card');
      const title=el('div','title');
      const idx=el('span','idx','#'+r.idx);
      const name=el('span','name mono idWrap',r.gameId);
      name.title=r.gameId;
      const hint=el('span','copyHint',t().copyHint);
      name.appendChild(hint);
      name.onclick=e=>{e.stopPropagation();copyId(r.gameId);};
      title.append(idx,name);
      const row=el('div','row');
      const lbl=el('span',null,t().turn);
      const val=el('b',null,t().turnN(r.turn));
      row.append(lbl,val);
      card.append(title,row);
      card.onclick=()=>location.hash='#/room/'+encodeURIComponent(r.gameId);
      // 悬停即预取详情：点击进入时数据已在缓存，零等待渲染
      card.addEventListener('pointerenter',()=>prefetchRoom(r.gameId));
      grid.appendChild(card);
    }
  }
  update();
  return{update,destroy(){}};
}

/* ═══ View: RoomDetail ═══ */
function RoomDetailView(container,gameId){
  const L=t();
  const back=el('button','backBtn',L.back);
  back.onclick=()=>location.hash='#/overview';
  const h2=el('h2');
  const badge=el('span','state');
  const grid=el('div','detailGrid');
  const keys=['gameId','roomUptime','turn','mode','playersOnline','countdown','saveSize','lastPersist','revision'];
  const kvs={};
  for(const k of keys){
    const box=el('div','kv'),kk=el('div','k',L[k]||k),vv=el('div','v','…');
    box.append(kk,vv);grid.appendChild(box);kvs[k]={k:kk,v:vv};
  }
  // 存档ID 格子：包一层可点击复制的 span（update 里的 set 只改内部文本，不覆盖点击事件）
  {
    const gidWrap=el('span','mono idWrap');
    gidWrap.style.wordBreak='break-all';
    const gidTxt=el('span');
    gidWrap.append(gidTxt,el('span','copyHint',L.copyHint));
    gidWrap.onclick=e=>{e.stopPropagation();copyId(gameId);};
    kvs.gameId.v.textContent='';
    kvs.gameId.v.appendChild(gidWrap);
    kvs.gameId.txt=gidTxt;
  }
  const ph2=el('h2',null,L.players);
  const table=el('table');
  const thead=el('thead'),trh=el('tr');
  for(const d of ['Player ID','thCiv','thReady','thAvgTurn','thStatus','thSeen']){
    trh.appendChild(el('th',null,d.startsWith('th')?L[d]:d));
  }
  thead.appendChild(trh);
  const tbody=el('tbody');
  table.append(thead,tbody);
  const lh2=el('h2',null,L.roomLog);
  const logBar=el('div','logBar');
  const chips=[];
  let logCat='',logCount=200;
  for(const[val,label]of[['',L.all],['op',L.catOp],['turn',L.catTurn],['player',L.catPlayer],['system',L.catSystem]]){
    const chip=el('span','chip'+(logCat===val?' on':''),label);
    chip.onclick=()=>{logCat=val;chips.forEach(c=>c.classList.remove('on'));chip.classList.add('on');fetchLogs();};
    chips.push(chip);logBar.appendChild(chip);
  }
  const spacer=el('span');spacer.style.flex='1';
  const countLbl=el('span',null,L.count);countLbl.style.cssText='font-size:12px;color:#8b98a5';
  const sel=el('select');
  for(const n of[50,100,200,500]){const o=el('option',null,String(n));o.value=String(n);if(n===200)o.selected=true;sel.appendChild(o);}
  sel.onchange=()=>{logCount=+sel.value;fetchLogs();};
  logBar.append(spacer,countLbl,sel);
  const logBox=el('div');logBox.id='logBox';
  logBox.appendChild(el('div','loading',L.loading));
  // 操作按钮行
  const actionBar=el('div');actionBar.style.cssText='display:flex;gap:10px;margin-bottom:14px';
  const dlBtn=el('button','backBtn','⬇ '+L.downloadSave);
  dlBtn.style.color='#9fc3e8';
  dlBtn.onclick=()=>{window.open('/admin/api/room/download?gameId='+encodeURIComponent(gameId)+'&pw='+encodeURIComponent(pw),'_blank');};
  // 暂停/恢复回合：与游戏内玩家的暂停走同一套服务端逻辑（倒计时冻结/续走）
  const pauseBtn=el('button','backBtn');
  pauseBtn.style.color='#e0c97e';
  pauseBtn.onclick=async()=>{
    pauseBtn.disabled=true;
    try{
      const act=(Store.detail&&Store.detail.state==='paused')?'resume':'pause';
      const r=await fetch('/admin/api/room/'+act+'?gameId='+encodeURIComponent(gameId)+'&pw='+encodeURIComponent(pw),{method:'POST'});
      if(r.ok){pollDetail();fetchLogs();}
    }catch(e){console.error('pause/resume:',e);}
    pauseBtn.disabled=false;
  };
  const delBtn=el('button','backBtn','🗑 '+L.deleteRoom);
  delBtn.style.cssText='color:#e8a2a2;border-color:#8a3b3b';
  delBtn.onclick=async()=>{
    if(!confirm(t().confirmDelete))return;
    try{
      const r=await fetch('/admin/api/room/delete?gameId='+encodeURIComponent(gameId)+'&pw='+encodeURIComponent(pw),{method:'POST'});
      if(r.ok){toast(t().deleteRoom+' ✓');location.hash='#/overview';}
    }catch(e){}
  };
  actionBar.append(dlBtn,pauseBtn,delBtn);
  container.append(back,actionBar,h2,badge,grid,ph2,table,lh2,logBar,logBox);
  let _dj='',_lj='',_pj='';
  let lastD=null,skewMs=0,fetchedAt=0;const seenRefs=[];

  function update(){
    try{
    const d=Store.detail;
    if(!d||d.gameId!==gameId)return;
    // 服务端时钟偏移 + 抓取时刻：供本地 ticker 每秒推算，不依赖下次轮询
    if(d.serverNowMs)skewMs=d.serverNowMs-Date.now();
    fetchedAt=Date.now();lastD=d;
    const json=JSON.stringify(d);
    if(json!==_dj){
      _dj=json;
      h2.textContent='#'+(Store.rooms.find(x=>x.gameId===d.gameId)?.idx||'?')+' ';
      badge.className='state st-'+d.state;
      badge.textContent=t().state[d.state]||d.state;
      h2.appendChild(badge);
      // 暂停/恢复按钮随状态切换文案
      pauseBtn.textContent=d.state==='paused'?('▶ '+t().resumeRoom):('⏸ '+t().pauseRoom);
      const set=(k,val)=>{kvs[k].k.textContent=t()[k]||k;(kvs[k].txt||kvs[k].v).textContent=val;};
      set('gameId',d.gameId);
      set('roomUptime',fmtUptime(d.uptimeMs));set('turn',t().turnN(d.turn));
      set('mode',t().modeT[d.mode]||d.mode);
      const pl=d.players||[];
      set('playersOnline',pl.filter(p=>p.online).length+' / '+pl.length);
      set('countdown',d.deadlineRemainMs<0?'—':fmtDur(d.deadlineRemainMs));
      set('saveSize',d.saveExists?fmtBytes(d.saveBytes):t().noSave);
      set('lastPersist',d.lastPersistAgoMs<0?'—':t().ago(d.lastPersistAgoMs));
      set('revision',String(d.revision));
      const pj=JSON.stringify(pl);
      if(pj!==_pj){
        _pj=pj;
        seenRefs.length=0;
        tbody.innerHTML='';
        if(!pl.length){const tr=el('tr'),td=el('td','empty',t().noOnline);td.colSpan=6;tr.appendChild(td);tbody.appendChild(tr);}
        else for(const p of pl){
          const tr=el('tr');
          const pidTd=el('td');
          if(p.playerId){
            const pe=el('span','mono idWrap',p.playerId.substring(0,8)+'…');
            pe.appendChild(el('span','copyHint',t().copyHint));
            pe.onclick=()=>copyId(p.playerId);
            pidTd.appendChild(pe);
            if(p.host){const tag=el('span',null,' ('+t().host+')');tag.style.cssText='color:#e0c97e;font-size:11px';pidTd.appendChild(tag);}
          }else pidTd.textContent='—';
          const civTd=el('td');
          if(p.civ){
            const ce=el('span','idWrap',p.civ);
            ce.appendChild(el('span','copyHint',t().copyHint));
            ce.onclick=()=>copyId(p.civ);
            civTd.appendChild(ce);
          }else civTd.textContent='—';
          const rTd=el('td',p.ready?'yes':'no',p.ready?'✓':'—');
          const avgTd=el('td',null,p.avgTurnMs>=0?fmtDur(p.avgTurnMs):'—');
          const sTd=el('td');
          sTd.appendChild(el('span',p.online?'yes':'no',p.online?'●':'○'));
          const seenTd=el('td',null,p.lastSeenAgoMs<0?'—':t().ago(p.lastSeenAgoMs));
          seenRefs.push({td:seenTd,abs:(typeof p.lastSeenAbsMs==='number')?p.lastSeenAbsMs:-1});
          tr.append(pidTd,civTd,rTd,avgTd,sTd,seenTd);
          tbody.appendChild(tr);
        }
      }
    }
    const lg=Store.logs;
    if(lg&&lg.gameId===gameId){
      const ljson=JSON.stringify(lg.events);
      if(ljson!==_lj){
        _lj=ljson;
        logBox.innerHTML='';
        if(!lg.events.length)logBox.appendChild(el('div','empty',t().noLogs));
        else for(const e of lg.events){
          const div=el('div');
          div.append(el('span','ev-ts',fmtClock(e.ts)),el('span','ev-cat c-'+e.cat,e.cat),document.createTextNode(renderEvent(e)));
          logBox.appendChild(div);
        }
      }
    }
  }catch(e){
    console.error('RoomDetailView.update:',e);
    const errEl=el('div','empty','⚠ '+e.message);
    errEl.style.color='#e0b47e';
    container.appendChild(errEl);
  }
  }
  function fetchLogs(){
    api('/admin/api/logs?gameId='+encodeURIComponent(gameId)+'&count='+logCount+'&cat='+logCat)
      .then(r=>r.json()).then(lg=>{Store.logs=lg;update();}).catch(()=>{});
  }
  fetchLogs();
  // 日志自动刷新（此前只在进入/切换筛选时拉一次）
  const logTimer=setInterval(fetchLogs,5000);
  // 本地每秒推算易变字段（倒计时/房间时长/落盘距今/玩家最近活跃）：
  // 数值由绝对时间戳 + 时钟偏移算出，页面被后台节流(轮询停摆)时照样走秒
  const tickTimer=setInterval(()=>{
    if(!lastD)return;
    const now=Date.now(),serverNow=now+skewMs;
    const setv=(k,v)=>{const kv=kvs[k];if(kv)(kv.txt||kv.v).textContent=v;};
    if(lastD.deadlineAtMs>0)setv('countdown',fmtDur(Math.max(0,lastD.deadlineAtMs-serverNow)));
    else setv('countdown','—');
    setv('roomUptime',fmtUptime(lastD.uptimeMs+(now-fetchedAt)));
    setv('lastPersist',lastD.lastPersistAbsMs>0?t().ago(serverNow-lastD.lastPersistAbsMs):'—');
    for(const s of seenRefs)if(s.td.isConnected)s.td.textContent=s.abs>0?t().ago(serverNow-s.abs):'—';
  },1000);
  // 构造即渲染：如果缓存里已有本房间数据（悬停预取/刚退出重进），直接显示，不等首次轮询
  update();
  return{update,fetchLogs,destroy(){clearInterval(logTimer);clearInterval(tickTimer);}};
}

/* ═══ View: Settings ═══ */
function SettingsView(container){
  const L=t();
  const h2=el('h2',null,'⚙ '+L.settings+' — '+L.autoClean);
  const desc=el('div');desc.style.cssText='font-size:13px;color:#8b98a5;max-width:720px;line-height:1.8;margin-bottom:18px';
  desc.textContent=L.autoCleanDesc;
  const panel=el('div');panel.style.cssText='background:#161d24;border:1px solid #232d37;border-radius:8px;padding:18px 20px;max-width:720px';
  const row=el('div','setRow');
  const lbl=el('span','setLbl',L.autoCleanSwitch);
  const sw=el('button','switch');const knob=el('span','knob');sw.appendChild(knob);
  const badge=el('span','state');
  const saveHint=el('span');saveHint.id='saveHint';
  row.append(lbl,sw,badge,saveHint);
  const slider=el('input');slider.type='range';slider.min='12';slider.max='168';slider.step='1';
  const rangeLbl=el('div');rangeLbl.style.cssText='display:flex;justify-content:space-between;font-size:12px;color:#6b78a5;margin-top:4px';
  rangeLbl.append(el('span',null,'12 '+L.hours),el('span',null,L.autoCleanRange),el('span',null,'168 '+L.hours));
  panel.append(row,slider,rangeLbl);
  container.append(h2,desc,panel);
  let saveTimer=null;
  function paint(){
    const on=Store.settings>0,hours=on?Store.settings:24;
    sw.className='switch'+(on?' on':'');
    slider.value=hours;slider.disabled=!on;
    badge.className='state '+(on?'st-run':'st-frozen');
    badge.textContent=on?t().autoCleanOnNote+'：'+hours+' '+t().hours+'（'+(hours/24).toFixed(1)+' '+t().day+'）':t().autoCleanOff;
  }
  async function save(hours){
    try{
      const r=await fetch('/admin/api/settings?hours='+hours+'&pw='+encodeURIComponent(pw),{method:'POST'});
      if(r.status===401){await handle401(r);return;}
      if(!r.ok)return;
      Store.settings=hours;paint();
      saveHint.textContent='✓ '+t().saved;
      clearTimeout(saveTimer);saveTimer=setTimeout(()=>saveHint.textContent='',2000);
    }catch(e){}
  }
  sw.onclick=()=>save(Store.settings>0?0:Math.max(12,+slider.value||24));
  slider.oninput=()=>{const v=+slider.value;badge.textContent=t().autoCleanOnNote+'：'+v+' '+t().hours+'（'+(v/24).toFixed(1)+' '+t().day+'）';};
  slider.onchange=()=>save(+slider.value);
  paint();
  return{update:paint,destroy(){clearTimeout(saveTimer);}};
}

/* ═══ View: ErrorLog ═══ */
function ErrorsView(container){
  const L=t();
  const h2=el('h2',null,'⚠ '+L.errorLog);
  const table=el('table');
  const thead=el('thead'),trh=el('tr');
  for(const d of ['thTime','thSource','thType','thMsg'])trh.appendChild(el('th',null,L[d]));
  thead.appendChild(trh);
  const tbody=el('tbody');
  table.append(thead,tbody);
  const empty=el('div','empty',L.noErrors);
  container.append(h2,table,empty);
  let _json='';
  function update(){
    // 从 /admin/api/errors 拉数据
    api('/admin/api/errors?count=100').then(r=>r.json()).then(data=>{
      const json=JSON.stringify(data.errors);
      if(json===_json)return;
      _json=json;
      const errs=data.errors||[];
      empty.style.display=errs.length?'none':'block';
      table.style.display=errs.length?'table':'none';
      tbody.innerHTML='';
      for(const e of errs){
        const tr=el('tr');
        tr.appendChild(el('td','mono',fmtClock(e.ts)));
        tr.appendChild(el('td',null,e.source||'—'));
        tr.appendChild(el('td',null,e.type||'—'));
        tr.appendChild(el('td',null,e.msg||'—'));
        tbody.appendChild(tr);
      }
    }).catch(()=>{});
  }
  update();
  const timer=setInterval(update,3000);
  return{update,destroy(){clearInterval(timer);}};
}

/* ═══ Theme ═══ */
let themeMode = localStorage.getItem('adminTheme') || 'auto';   // dark / light / auto
const THEME_ICONS = { dark: '🌙', light: '☀️', auto: '🖥️' };
const THEME_LABELS = {
  zh: { dark: '夜间', light: '日间', auto: '跟随系统' },
  en: { dark: 'Dark', light: 'Light', auto: 'Auto' },
};

function applyTheme() {
  const effective = themeMode === 'auto'
    ? (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light')
    : themeMode;
  document.documentElement.setAttribute('data-theme', effective);
  $('themeBtn').textContent = THEME_ICONS[themeMode];
  $('themeBtn').title = (THEME_LABELS[lang] || THEME_LABELS.zh)[themeMode];
}

function cycleTheme() {
  themeMode = themeMode === 'dark' ? 'light' : themeMode === 'light' ? 'auto' : 'dark';
  localStorage.setItem('adminTheme', themeMode);
  applyTheme();
}

$('themeBtn').onclick = cycleTheme;

// auto 模式下监听系统主题变化，实时切换
window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
  if (themeMode === 'auto') applyTheme();
});

/* ═══ Language ═══ */
function applyLang(){
  $('langBtn').textContent='EN/中文';
  applyTheme();
  document.title='UncivSync '+(lang==='zh'?'管理台':'Admin');
  document.documentElement.lang=lang;
  $('navRoomsLabel').textContent=t().navRooms;
  _navInfoEls=null;
  renderNavInfo();
  _navRoomsJson=null;
  renderNav();
}
$('langBtn').onclick=()=>{
  lang=lang==='zh'?'en':'zh';
  localStorage.setItem('adminLang',lang);
  applyLang();
  navigate();
};

/* ═══ Poller ═══ */
// 网络状态：断连时显示横幅并加快重试；恢复后自动清除。服务端重启期间页面不再"假死"。
let netOk=true;
function setNet(ok){
  if(ok===netOk)return;
  netOk=ok;
  let b=$('netBanner');
  if(!ok){
    if(!b){b=el('div');b.id='netBanner';
      b.style.cssText='position:fixed;top:0;left:0;right:0;background:#8a5a2b;color:#ffe;z-index:1000;text-align:center;padding:6px;font-size:13px';
      document.body.appendChild(b);}
    b.textContent='⚠ '+t().netLost;
  }else if(b)b.remove();
}
async function pollOverview(){
  try{
    const r=await api('/admin/api/overview');
    if(await handle401(r))return;
    Store.overview=await r.json();
    renderNavInfo();
    setNet(true);
  }catch(e){setNet(false);}
}
async function pollRooms(){
  try{
    const r=await api('/admin/api/rooms');
    if(r.status===401)return;
    Store.rooms=await r.json();
    renderNav();
    if(currentView&&currentView.update)currentView.update();
    setNet(true);
  }catch(e){setNet(false);}
}
async function pollDetail(){
  if(!currentGameId)return;
  try{
    const r=await api('/admin/api/room?gameId='+encodeURIComponent(currentGameId));
    if(r.status===401)return;
    if(r.status===404){toast(t().roomClosed);location.hash='#/overview';return;}
    Store.detail=await r.json();
    if(currentView&&currentView.update)currentView.update();
    setNet(true);
  }catch(e){
    console.error('pollDetail:',e);
    setNet(false);
  }
}

/* ═══ Prefetch：悬停即预取详情，点进去零等待 ═══ */
const _prefetched={};
function prefetchRoom(gameId){
  if(gameId===currentGameId)return;
  const now=Date.now();
  if(_prefetched[gameId]&&now-_prefetched[gameId]<10000)return;
  _prefetched[gameId]=now;
  api('/admin/api/room?gameId='+encodeURIComponent(gameId))
    .then(r=>r.ok?r.json():null)
    .then(d=>{if(d){Store.detail=d;if(currentView&&currentView.update)currentView.update();}})
    .catch(()=>{_prefetched[gameId]=0;});
}

/* ═══ Boot ═══ */
applyTheme();
applyLang();
navigate();
pollOverview();
pollRooms();
setInterval(pollOverview,5000);
setInterval(pollRooms,3000);
setInterval(pollDetail,1500);
// 页面重新可见/网络恢复：立即拉一次数据，不等下一个(可能被节流的)轮询周期
document.addEventListener('visibilitychange',()=>{if(!document.hidden){pollOverview();pollRooms();pollDetail();}});
window.addEventListener('online',()=>{pollOverview();pollRooms();pollDetail();});
// 任何点击/聚焦也立即触发一次真实刷新：后台节流期间点回来的第一下就能拿到新数据
let _lastAct=0;
function actPoll(){
  const n=Date.now();
  if(n-_lastAct<1000)return;
  _lastAct=n;
  pollOverview();pollRooms();pollDetail();
}
window.addEventListener('focus',actPoll);
document.addEventListener('pointerdown',actPoll,true);
</script>
</body>
</html>
""";
}
