// Admin console for the app update worker, served at GET /admin.
// The page is a self-contained SPA: token login, release management with
// browser-side (multipart) APK upload, and Analytics Engine dashboards.
// NOTE: the page script below must not contain backticks or "${" sequences,
// because the whole document lives inside this template literal.
const ADMIN_HTML = `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex">
<title>OpenOmniBot · 更新管理后台</title>
<style>
:root {
  --surface-1: #fcfcfb;
  --plane: #f9f9f7;
  --ink-1: #0b0b0b;
  --ink-2: #52514e;
  --ink-3: #898781;
  --grid: #e1e0d9;
  --axis: #c3c2b7;
  --border: rgba(11, 11, 11, 0.10);
  --series-1: #2a78d6;
  --series-2: #1baf7a;
  --accent: #2a78d6;
  --accent-ink: #ffffff;
  --accent-soft: rgba(42, 120, 214, 0.10);
  --good: #0ca30c;
  --critical: #d03b3b;
  --critical-soft: rgba(208, 59, 59, 0.08);
  --chip-beta-bg: rgba(237, 161, 0, 0.14);
  --chip-stable-bg: rgba(42, 120, 214, 0.12);
  --shadow: 0 1px 2px rgba(11, 11, 11, 0.04), 0 8px 24px rgba(11, 11, 11, 0.05);
}
@media (prefers-color-scheme: dark) {
  :root {
    --surface-1: #1a1a19;
    --plane: #0d0d0d;
    --ink-1: #ffffff;
    --ink-2: #c3c2b7;
    --ink-3: #898781;
    --grid: #2c2c2a;
    --axis: #383835;
    --border: rgba(255, 255, 255, 0.10);
    --series-1: #3987e5;
    --series-2: #199e70;
    --accent: #3987e5;
    --accent-ink: #ffffff;
    --accent-soft: rgba(57, 135, 229, 0.16);
    --good: #0ca30c;
    --critical: #d03b3b;
    --critical-soft: rgba(208, 59, 59, 0.16);
    --chip-beta-bg: rgba(201, 133, 0, 0.22);
    --chip-stable-bg: rgba(57, 135, 229, 0.20);
    --shadow: 0 1px 2px rgba(0, 0, 0, 0.4), 0 8px 24px rgba(0, 0, 0, 0.35);
  }
}
* { box-sizing: border-box; }
html, body { margin: 0; padding: 0; }
body {
  background: var(--plane);
  color: var(--ink-1);
  font: 14px/1.55 system-ui, -apple-system, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif;
  -webkit-font-smoothing: antialiased;
}
button { font: inherit; cursor: pointer; }
input, textarea, select { font: inherit; color: var(--ink-1); }
a { color: var(--accent); text-decoration: none; }
a:hover { text-decoration: underline; }

.hidden { display: none !important; }

/* ---------- Login ---------- */
.login-wrap {
  min-height: 100vh;
  display: flex; align-items: center; justify-content: center;
  padding: 24px;
}
.login-card {
  width: 380px; max-width: 100%;
  background: var(--surface-1);
  border: 1px solid var(--border);
  border-radius: 16px;
  box-shadow: var(--shadow);
  padding: 36px 32px 32px;
}
.brand { display: flex; align-items: center; gap: 10px; margin-bottom: 6px; }
.brand-dot {
  width: 12px; height: 12px; border-radius: 4px;
  background: linear-gradient(135deg, var(--series-1), var(--series-2));
  flex: none;
}
.brand-name { font-size: 17px; font-weight: 650; letter-spacing: 0.01em; }
.login-sub { color: var(--ink-3); font-size: 13px; margin: 0 0 24px; }
.field-label { display: block; font-size: 12px; color: var(--ink-2); margin: 14px 0 6px; }
.text-input {
  width: 100%;
  background: var(--plane);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 10px 12px;
  outline: none;
}
.text-input:focus { border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-soft); }
.login-error { color: var(--critical); font-size: 12.5px; min-height: 18px; margin: 8px 0 4px; }
.btn {
  border: 1px solid var(--border);
  background: var(--surface-1);
  color: var(--ink-1);
  border-radius: 10px;
  padding: 8px 14px;
  transition: filter .12s ease, background .12s ease;
}
.btn:hover { background: var(--accent-soft); }
.btn:disabled { opacity: .55; cursor: default; }
.btn-primary { background: var(--accent); border-color: var(--accent); color: var(--accent-ink); }
.btn-primary:hover { filter: brightness(1.08); background: var(--accent); }
.btn-danger { color: var(--critical); border-color: var(--critical); background: transparent; }
.btn-danger:hover { background: var(--critical-soft); }
.btn-block { width: 100%; padding: 10px 14px; margin-top: 10px; font-weight: 600; }
.btn-sm { padding: 4px 10px; border-radius: 8px; font-size: 12.5px; }

/* ---------- App shell ---------- */
.topbar {
  position: sticky; top: 0; z-index: 30;
  display: flex; align-items: center; gap: 24px;
  padding: 0 28px;
  height: 56px;
  background: var(--surface-1);
  border-bottom: 1px solid var(--border);
}
.topbar .brand { margin: 0; }
.nav { display: flex; gap: 4px; height: 100%; }
.nav button {
  border: none; background: none; color: var(--ink-2);
  padding: 0 14px; height: 100%;
  border-bottom: 2px solid transparent;
  font-weight: 550;
}
.nav button.active { color: var(--ink-1); border-bottom-color: var(--accent); }
.topbar-right { margin-left: auto; display: flex; align-items: center; gap: 12px; color: var(--ink-3); font-size: 12.5px; }
.container { max-width: 1120px; margin: 0 auto; padding: 24px 28px 64px; }

/* ---------- Filter row ---------- */
.filter-row { display: flex; align-items: center; gap: 12px; margin-bottom: 18px; flex-wrap: wrap; }
.segmented { display: inline-flex; background: var(--surface-1); border: 1px solid var(--border); border-radius: 10px; padding: 3px; }
.segmented button {
  border: none; background: none; color: var(--ink-2);
  padding: 5px 14px; border-radius: 7px; font-size: 13px;
}
.segmented button.active { background: var(--accent); color: var(--accent-ink); font-weight: 600; }
.filter-note { color: var(--ink-3); font-size: 12px; }

/* ---------- Cards & tiles ---------- */
.tile-row { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 14px; margin-bottom: 18px; }
.tile {
  background: var(--surface-1); border: 1px solid var(--border);
  border-radius: 14px; padding: 16px 18px 14px; box-shadow: var(--shadow);
}
.tile-label { font-size: 12.5px; color: var(--ink-2); }
.tile-value { font-size: 28px; font-weight: 650; margin-top: 2px; line-height: 1.2; }
.tile-sub { font-size: 12px; color: var(--ink-3); margin-top: 4px; }
.card {
  background: var(--surface-1); border: 1px solid var(--border);
  border-radius: 14px; box-shadow: var(--shadow);
  margin-bottom: 18px;
  overflow: hidden;
}
.card-head {
  display: flex; align-items: center; gap: 10px;
  padding: 14px 18px 0;
}
.card-title { font-size: 14.5px; font-weight: 650; }
.card-caption { font-size: 12px; color: var(--ink-3); }
.card-tools { margin-left: auto; display: flex; gap: 6px; }
.card-body { padding: 12px 18px 18px; position: relative; }
.card-body.loading { opacity: .5; pointer-events: none; }
.chart-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 18px; }
@media (max-width: 900px) { .chart-grid { grid-template-columns: 1fr; } }
.chart-grid .card { margin-bottom: 0; }
.empty-note { color: var(--ink-3); font-size: 13px; padding: 28px 0; text-align: center; }

/* ---------- Legend / tooltip ---------- */
.legend { display: flex; gap: 16px; padding: 8px 18px 0; font-size: 12.5px; color: var(--ink-2); }
.legend-key { display: inline-block; width: 14px; height: 0; border-top: 2.5px solid; border-radius: 2px; vertical-align: middle; margin-right: 6px; }
.viz-tooltip {
  position: absolute; z-index: 40; pointer-events: none;
  background: var(--surface-1); border: 1px solid var(--border);
  border-radius: 10px; box-shadow: var(--shadow);
  padding: 8px 12px; font-size: 12.5px; min-width: 132px;
  display: none;
}
.viz-tooltip .tt-title { color: var(--ink-3); font-size: 11.5px; margin-bottom: 4px; }
.viz-tooltip .tt-row { display: flex; align-items: center; gap: 8px; margin-top: 2px; }
.viz-tooltip .tt-key { width: 12px; height: 0; border-top: 2.5px solid; border-radius: 2px; flex: none; }
.viz-tooltip .tt-val { font-weight: 650; font-variant-numeric: tabular-nums; }
.viz-tooltip .tt-label { color: var(--ink-2); }

/* ---------- Bars ---------- */
.hbar-row { display: grid; grid-template-columns: 132px 1fr; align-items: center; gap: 10px; padding: 3px 0; }
.hbar-label { color: var(--ink-2); font-size: 12.5px; text-align: right; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.hbar-track { display: flex; align-items: center; gap: 8px; min-width: 0; }
.hbar-bar {
  height: 16px; border-radius: 0 4px 4px 0;
  background: var(--series-1);
  min-width: 2px;
  transition: filter .1s ease;
}
.hbar-row:hover .hbar-bar { filter: brightness(1.12); }
.hbar-value { font-size: 12px; color: var(--ink-1); font-variant-numeric: tabular-nums; flex: none; }

/* ---------- Data table ---------- */
.data-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.data-table th {
  text-align: left; color: var(--ink-3); font-weight: 550; font-size: 12px;
  padding: 6px 10px; border-bottom: 1px solid var(--grid);
}
.data-table td { padding: 6px 10px; border-bottom: 1px solid var(--grid); }
.data-table td.num, .data-table th.num { text-align: right; font-variant-numeric: tabular-nums; }
.data-table tr:last-child td { border-bottom: none; }

/* ---------- Releases ---------- */
.rel-toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 16px; }
.rel-row {
  display: grid;
  grid-template-columns: 200px 1fr 130px 170px auto;
  gap: 14px; align-items: center;
  padding: 14px 18px;
  border-bottom: 1px solid var(--grid);
  cursor: pointer;
}
.rel-row:hover { background: var(--accent-soft); }
.rel-row:last-child { border-bottom: none; }
.rel-tag { font-weight: 650; }
.rel-notes { color: var(--ink-3); font-size: 12.5px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rel-meta { color: var(--ink-2); font-size: 12.5px; }
.chip {
  display: inline-block; font-size: 11px; font-weight: 600;
  border-radius: 6px; padding: 1.5px 8px; margin-left: 8px;
  vertical-align: 2px; color: var(--ink-1);
}
.chip-stable { background: var(--chip-stable-bg); }
.chip-beta { background: var(--chip-beta-bg); }
.chip-draft { background: var(--grid); color: var(--ink-2); }
@media (max-width: 900px) { .rel-row { grid-template-columns: 1fr auto; } .rel-notes, .rel-meta.pub { display: none; } }

/* ---------- Editor modal ---------- */
.modal-mask {
  position: fixed; inset: 0; z-index: 50;
  background: rgba(11, 11, 11, 0.45);
  display: flex; align-items: flex-start; justify-content: center;
  padding: 40px 20px;
  overflow-y: auto;
}
.modal {
  width: 760px; max-width: 100%;
  background: var(--surface-1);
  border: 1px solid var(--border);
  border-radius: 16px;
  box-shadow: var(--shadow);
}
.modal-head { display: flex; align-items: center; padding: 18px 24px 0; }
.modal-title { font-size: 16px; font-weight: 650; }
.modal-body { padding: 8px 24px 24px; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0 18px; }
@media (max-width: 640px) { .form-grid { grid-template-columns: 1fr; } }
.check-row { display: flex; gap: 22px; margin-top: 14px; color: var(--ink-2); font-size: 13px; }
.check-row label { display: flex; align-items: center; gap: 6px; cursor: pointer; }
textarea.text-input { min-height: 130px; resize: vertical; line-height: 1.6; }
.assets-box { border: 1px solid var(--border); border-radius: 12px; margin-top: 8px; overflow: hidden; }
.asset-row {
  display: flex; align-items: center; gap: 12px;
  padding: 10px 14px; border-bottom: 1px solid var(--grid); font-size: 13px;
}
.asset-row:last-child { border-bottom: none; }
.asset-name { font-weight: 550; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.asset-sub { color: var(--ink-3); font-size: 11.5px; font-variant-numeric: tabular-nums; }
.asset-actions { margin-left: auto; display: flex; gap: 6px; flex: none; }
.upload-zone {
  border: 1.5px dashed var(--axis); border-radius: 12px;
  padding: 18px; text-align: center; color: var(--ink-3);
  margin-top: 10px; transition: border-color .12s ease, background .12s ease;
}
.upload-zone.dragover { border-color: var(--accent); background: var(--accent-soft); color: var(--ink-1); }
.upload-progress { margin-top: 10px; }
.progress-track { height: 6px; border-radius: 3px; background: var(--accent-soft); overflow: hidden; }
.progress-fill { height: 100%; width: 0%; border-radius: 3px; background: var(--accent); transition: width .15s ease; }
.progress-text { font-size: 12px; color: var(--ink-2); margin-top: 5px; font-variant-numeric: tabular-nums; }
.modal-foot { display: flex; gap: 10px; padding: 0 24px 24px; }
.modal-foot .spacer { flex: 1; }

/* ---------- Toast ---------- */
.toast-wrap { position: fixed; bottom: 24px; left: 50%; transform: translateX(-50%); z-index: 90; display: flex; flex-direction: column; gap: 8px; }
.toast {
  background: var(--ink-1); color: var(--surface-1);
  border-radius: 10px; padding: 9px 16px; font-size: 13px;
  box-shadow: var(--shadow); opacity: 0; transition: opacity .18s ease;
}
.toast.show { opacity: 1; }
.toast.error { background: var(--critical); color: #fff; }

.config-hint {
  border: 1px solid var(--border); border-left: 3px solid var(--series-3, #eda100);
  background: var(--surface-1); border-radius: 10px;
  color: var(--ink-2); font-size: 13px; padding: 12px 16px; margin-bottom: 18px;
}
.policy-form { max-width: 760px; }
.policy-form .config-hint { margin-top: 14px; }
.policy-actions { display: flex; align-items: center; gap: 10px; margin-top: 16px; }
.qr-form { max-width: 760px; }
.qr-preview {
  display: block; width: 220px; max-height: 340px; object-fit: contain;
  margin: 14px 0; border: 1px solid var(--border); border-radius: 10px;
  background: #fff;
}
.qr-actions { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-top: 14px; }
code { background: var(--accent-soft); border-radius: 5px; padding: 1px 6px; font-size: 12px; }
</style>
</head>
<body>

<!-- Login -->
<div id="view-login" class="login-wrap">
  <div class="login-card">
    <div class="brand"><span class="brand-dot"></span><span class="brand-name">OpenOmniBot 更新管理</span></div>
    <p class="login-sub">应用版本发布 · 更新日志 · 使用统计</p>
    <label class="field-label" for="token-input">管理令牌(ADMIN_TOKEN)</label>
    <input id="token-input" class="text-input" type="password" autocomplete="current-password" placeholder="粘贴访问令牌">
    <div id="login-error" class="login-error"></div>
    <button id="login-btn" class="btn btn-primary btn-block">登录</button>
  </div>
</div>

<!-- App -->
<div id="view-app" class="hidden">
  <header class="topbar">
    <div class="brand"><span class="brand-dot"></span><span class="brand-name">OpenOmniBot 更新管理</span></div>
    <nav class="nav">
      <button id="nav-analytics" class="active">数据统计</button>
      <button id="nav-releases">版本管理</button>
      <button id="nav-cloud-policy">云服务门禁</button>
      <button id="nav-community-qr">社群二维码</button>
    </nav>
    <div class="topbar-right">
      <span id="worker-host"></span>
      <button id="logout-btn" class="btn btn-sm">退出</button>
    </div>
  </header>

  <main class="container">
    <!-- Analytics -->
    <section id="page-analytics">
      <div id="analytics-config-hint" class="config-hint hidden">
        尚未配置统计查询:请为 Worker 设置 <code>CF_ACCOUNT_ID</code> 变量与 <code>CF_ANALYTICS_API_TOKEN</code> 密钥,并绑定 Analytics Engine 数据集 <code>UPDATE_ANALYTICS</code>。数据上报(写入)只需绑定数据集即可生效。
      </div>
      <div class="filter-row">
        <div class="segmented" id="days-seg" role="group" aria-label="时间范围">
          <button data-days="7">近 7 天</button>
          <button data-days="14">近 14 天</button>
          <button data-days="30" class="active">近 30 天</button>
          <button data-days="90">近 90 天</button>
        </div>
        <span class="filter-note">按 UTC 日期聚合 · Analytics Engine 数据保留约 90 天</span>
        <button id="analytics-refresh" class="btn btn-sm" style="margin-left:auto">刷新</button>
      </div>

      <div class="tile-row" id="tile-row"></div>

      <div class="card" id="daily-card">
        <div class="card-head">
          <div>
            <div class="card-title">每日请求趋势</div>
            <div class="card-caption">更新检查与 APK 下载的每日次数</div>
          </div>
          <div class="card-tools"><button class="btn btn-sm" data-tableswitch="daily">表格</button></div>
        </div>
        <div class="legend" id="daily-legend"></div>
        <div class="card-body" id="daily-body"></div>
      </div>

      <div class="chart-grid">
        <div class="card">
          <div class="card-head">
            <div><div class="card-title">设备型号 Top 10</div><div class="card-caption">按独立设备数排序</div></div>
            <div class="card-tools"><button class="btn btn-sm" data-tableswitch="devices">表格</button></div>
          </div>
          <div class="card-body" id="devices-body"></div>
        </div>
        <div class="card">
          <div class="card-head">
            <div><div class="card-title">应用版本分布</div><div class="card-caption">检查更新时上报的当前版本</div></div>
            <div class="card-tools"><button class="btn btn-sm" data-tableswitch="versions">表格</button></div>
          </div>
          <div class="card-body" id="versions-body"></div>
        </div>
        <div class="card">
          <div class="card-head">
            <div><div class="card-title">Android 版本分布</div><div class="card-caption">按独立设备数排序</div></div>
            <div class="card-tools"><button class="btn btn-sm" data-tableswitch="os">表格</button></div>
          </div>
          <div class="card-body" id="os-body"></div>
        </div>
        <div class="card">
          <div class="card-head">
            <div><div class="card-title">下载明细</div><div class="card-caption">按版本与安装包统计</div></div>
          </div>
          <div class="card-body" id="downloads-body"></div>
        </div>
      </div>
    </section>

    <!-- Releases -->
    <section id="page-releases" class="hidden">
      <div class="rel-toolbar">
        <button id="new-release-btn" class="btn btn-primary">新建版本</button>
        <button id="releases-refresh" class="btn">刷新</button>
        <span class="filter-note" id="releases-count"></span>
      </div>
      <div class="card"><div id="releases-list"></div></div>
    </section>

    <!-- Cloud-service policy -->
    <section id="page-cloud-policy" class="hidden">
      <div class="card">
        <div class="card-head">
          <div>
            <div class="card-title">账号与官方云服务最低版本</div>
            <div class="card-caption">低于最低版本的客户端不能注册、登录或使用官方云 AI;未登录 BYOK 不受影响</div>
          </div>
        </div>
        <div class="card-body policy-form">
          <label class="field-label" for="cloud-minimum-version">最低允许版本</label>
          <input id="cloud-minimum-version" class="text-input" inputmode="numeric" placeholder="例如 0.5.7;留空表示关闭门禁">
          <label class="field-label" for="cloud-policy-message">拦截提示(可选)</label>
          <textarea id="cloud-policy-message" class="text-input" maxlength="500" placeholder="请升级到最新版后继续使用账号与官方 AI 服务"></textarea>
          <div id="cloud-policy-status" class="config-hint">正在读取当前策略…</div>
          <div class="policy-actions">
            <button id="cloud-policy-save" class="btn btn-primary">保存并应用</button>
            <button id="cloud-policy-refresh" class="btn">重新读取</button>
            <span class="filter-note">最低版本按数字分段比较,且包含该版本</span>
          </div>
        </div>
      </div>
    </section>

    <!-- Community QR code -->
    <section id="page-community-qr" class="hidden">
      <div class="card">
        <div class="card-head">
          <div>
            <div class="card-title">微信群二维码</div>
            <div class="card-caption">README 始终引用同一个公开地址;上传新图片即可原位替换</div>
          </div>
        </div>
        <div class="card-body qr-form">
          <div id="community-qr-status" class="config-hint">正在读取当前图片…</div>
          <img id="community-qr-preview" class="qr-preview hidden" alt="当前微信群二维码预览">
          <label class="field-label" for="community-qr-input">选择新二维码图片</label>
          <input id="community-qr-input" class="text-input" type="file" accept="image/jpeg,image/png,image/webp,.jpg,.jpeg,.png,.webp">
          <div class="qr-actions">
            <button id="community-qr-upload" class="btn btn-primary">上传并替换</button>
            <button id="community-qr-refresh" class="btn">重新读取</button>
            <button id="community-qr-copy" class="btn">复制公开链接</button>
            <span class="filter-note">支持 JPEG、PNG、WebP,最大 5 MB</span>
          </div>
        </div>
      </div>
    </section>
  </main>
</div>

<!-- Release editor -->
<div id="editor-mask" class="modal-mask hidden">
  <div class="modal" role="dialog" aria-modal="true" aria-labelledby="editor-title">
    <div class="modal-head"><div class="modal-title" id="editor-title">编辑版本</div></div>
    <div class="modal-body">
      <div class="form-grid">
        <div>
          <label class="field-label" for="f-tag">标签(tag)</label>
          <input id="f-tag" class="text-input" placeholder="v1.6.2">
        </div>
        <div>
          <label class="field-label" for="f-version">版本号</label>
          <input id="f-version" class="text-input" placeholder="1.6.2(留空则取自标签)">
        </div>
        <div>
          <label class="field-label" for="f-track">发布轨道</label>
          <select id="f-track" class="text-input">
            <option value="stable">stable(正式版,x.y.z)</option>
            <option value="beta">beta(测试版,x.y.z.n)</option>
          </select>
        </div>
        <div>
          <label class="field-label" for="f-published">发布时间</label>
          <input id="f-published" class="text-input" type="datetime-local">
        </div>
      </div>
      <div class="check-row">
        <label><input id="f-draft" type="checkbox"> 草稿(客户端不可见)</label>
        <label><input id="f-prerelease" type="checkbox"> 预发布</label>
      </div>
      <label class="field-label" for="f-url">发布页链接(可选)</label>
      <input id="f-url" class="text-input" placeholder="https://github.com/.../releases/tag/v1.6.2">
      <label class="field-label" for="f-notes">更新日志(展示在 App 更新弹窗中)</label>
      <textarea id="f-notes" class="text-input" placeholder="- 新增 …&#10;- 修复 …&#10;- 优化 …"></textarea>

      <label class="field-label">安装包(APK)</label>
      <div class="assets-box" id="assets-box"></div>
      <div class="upload-zone" id="upload-zone">
        <div>拖拽 APK 到此处,或 <a href="javascript:void(0)" id="upload-pick">选择文件</a> 上传</div>
        <div style="font-size:12px;margin-top:4px">大文件自动分片上传;完成后自动计算并记录 SHA-256</div>
        <input id="upload-input" type="file" accept=".apk,.sha256" class="hidden" multiple>
        <div class="upload-progress hidden" id="upload-progress">
          <div class="progress-track"><div class="progress-fill" id="progress-fill"></div></div>
          <div class="progress-text" id="progress-text"></div>
        </div>
      </div>
    </div>
    <div class="modal-foot">
      <button id="editor-delete" class="btn btn-danger">删除版本</button>
      <span class="spacer"></span>
      <button id="editor-cancel" class="btn">取消</button>
      <button id="editor-save" class="btn btn-primary">保存</button>
    </div>
  </div>
</div>

<div class="toast-wrap" id="toast-wrap"></div>

<script>
(function () {
  'use strict';

  var TOKEN_KEY = 'omnibot_admin_token';
  var state = {
    token: null,
    days: 30,
    analyticsConfigured: true,
    releases: [],
    cloudServicePolicy: null,
    communityQr: null,
    analytics: {},          // metric -> rows
    tableMode: {},          // chart id -> boolean
    editor: null,           // { isNew, original, assets }
    uploading: false
  };

  // ---------- helpers ----------
  function $(id) { return document.getElementById(id); }

  function el(tag, className, text) {
    var node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined && text !== null) node.textContent = String(text);
    return node;
  }

  function svgEl(tag, attrs) {
    var node = document.createElementNS('http://www.w3.org/2000/svg', tag);
    if (attrs) {
      for (var key in attrs) {
        if (Object.prototype.hasOwnProperty.call(attrs, key)) node.setAttribute(key, attrs[key]);
      }
    }
    return node;
  }

  function fmtNum(value) {
    var num = Number(value) || 0;
    return num.toLocaleString('zh-CN');
  }

  function fmtBytes(size) {
    var num = Number(size) || 0;
    if (num <= 0) return '—';
    var units = ['B', 'KB', 'MB', 'GB'];
    var index = 0;
    while (num >= 1024 && index < units.length - 1) { num /= 1024; index += 1; }
    return (index === 0 ? num : num.toFixed(1)) + ' ' + units[index];
  }

  function fmtDate(ms) {
    if (!ms) return '—';
    var d = new Date(Number(ms));
    if (isNaN(d.getTime())) return '—';
    function pad(v) { return v < 10 ? '0' + v : String(v); }
    return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) +
      ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
  }

  function toast(message, isError) {
    var wrap = $('toast-wrap');
    var node = el('div', 'toast' + (isError ? ' error' : ''), message);
    wrap.appendChild(node);
    requestAnimationFrame(function () { node.classList.add('show'); });
    setTimeout(function () {
      node.classList.remove('show');
      setTimeout(function () { wrap.removeChild(node); }, 250);
    }, isError ? 5000 : 2600);
  }

  function api(path, options) {
    options = options || {};
    var headers = options.headers || {};
    headers['authorization'] = 'Bearer ' + state.token;
    options.headers = headers;
    return fetch(path, options).then(function (response) {
      if (response.status === 401) {
        logout('令牌无效或已过期,请重新登录');
        throw new Error('unauthorized');
      }
      return response.text().then(function (text) {
        var payload = null;
        try { payload = JSON.parse(text); } catch (err) { /* keep null */ }
        if (!response.ok) {
          var message = payload && payload.error ? payload.error : ('HTTP ' + response.status);
          var error = new Error(message);
          error.status = response.status;
          throw error;
        }
        return payload;
      });
    });
  }

  // ---------- auth ----------
  function login(token) {
    state.token = token;
    return api('/admin/api/session').then(function (payload) {
      localStorage.setItem(TOKEN_KEY, token);
      state.analyticsConfigured = Boolean(payload && payload.analyticsConfigured);
      showApp();
    });
  }

  function logout(message) {
    localStorage.removeItem(TOKEN_KEY);
    state.token = null;
    $('view-app').classList.add('hidden');
    $('view-login').classList.remove('hidden');
    if (message) { $('login-error').textContent = message; }
  }

  function showApp() {
    $('view-login').classList.add('hidden');
    $('view-app').classList.remove('hidden');
    $('worker-host').textContent = location.host;
    $('analytics-config-hint').classList.toggle('hidden', state.analyticsConfigured);
    loadReleases();
    loadCloudServicePolicy();
    loadCommunityQr();
    loadAnalytics();
  }

  $('login-btn').addEventListener('click', function () {
    var token = $('token-input').value.trim();
    if (!token) { $('login-error').textContent = '请输入令牌'; return; }
    $('login-error').textContent = '';
    $('login-btn').disabled = true;
    login(token).catch(function (error) {
      if (error.message !== 'unauthorized') {
        $('login-error').textContent = '登录失败:' + error.message;
      }
    }).then(function () { $('login-btn').disabled = false; });
  });
  $('token-input').addEventListener('keydown', function (event) {
    if (event.key === 'Enter') $('login-btn').click();
  });
  $('logout-btn').addEventListener('click', function () { logout(''); });

  // ---------- navigation ----------
  function switchPage(name) {
    var names = ['analytics', 'releases', 'cloud-policy', 'community-qr'];
    for (var i = 0; i < names.length; i += 1) {
      var active = names[i] === name;
      $('page-' + names[i]).classList.toggle('hidden', !active);
      $('nav-' + names[i]).classList.toggle('active', active);
    }
    if (name === 'cloud-policy') loadCloudServicePolicy();
    if (name === 'community-qr') loadCommunityQr();
  }
  $('nav-analytics').addEventListener('click', function () { switchPage('analytics'); });
  $('nav-releases').addEventListener('click', function () { switchPage('releases'); });
  $('nav-cloud-policy').addEventListener('click', function () { switchPage('cloud-policy'); });
  $('nav-community-qr').addEventListener('click', function () { switchPage('community-qr'); });

  // ---------- cloud-service policy ----------
  function loadCloudServicePolicy() {
    $('cloud-policy-refresh').disabled = true;
    return api('/admin/cloud-service-policy').then(function (payload) {
      state.cloudServicePolicy = payload.policy || null;
      renderCloudServicePolicy();
    }).catch(function (error) {
      if (error.message !== 'unauthorized') {
        $('cloud-policy-status').textContent = '读取失败:' + error.message;
        toast('读取云服务门禁失败:' + error.message, true);
      }
    }).then(function () { $('cloud-policy-refresh').disabled = false; });
  }

  function renderCloudServicePolicy() {
    var policy = state.cloudServicePolicy || {};
    $('cloud-minimum-version').value = policy.minimumVersion || '';
    $('cloud-policy-message').value = policy.message || '';
    if (policy.enabled) {
      var updated = policy.updatedAt ? ' · 更新于 ' + fmtDate(policy.updatedAt) : '';
      $('cloud-policy-status').textContent = '门禁已启用 · 最低允许版本 v' + policy.minimumVersion + updated;
    } else {
      $('cloud-policy-status').textContent = '门禁当前已关闭;保存非空最低版本后立即启用';
    }
  }

  $('cloud-policy-refresh').addEventListener('click', function () { loadCloudServicePolicy(); });
  $('cloud-policy-save').addEventListener('click', function () {
    var minimumVersion = $('cloud-minimum-version').value.trim().replace(/^[vV]/, '');
    if (minimumVersion && !/^\\d+(\\.\\d+)*$/.test(minimumVersion)) {
      toast('最低版本只能包含以点分隔的数字', true);
      return;
    }

    var latest = latestStableVersion().replace(/^v/, '');
    if (minimumVersion && (!latest || compareVersions(minimumVersion, latest) > 0)) {
      var warning = latest
        ? '最低版本 v' + minimumVersion + ' 高于当前最新正式版 v' + latest + ',用户可能无法升级到可用版本。仍然保存?'
        : '当前没有可用的正式版安装包,启用门禁后用户可能无法升级。仍然保存?';
      if (!confirm(warning)) return;
    }

    $('cloud-policy-save').disabled = true;
    api('/admin/cloud-service-policy', {
      method: 'PUT',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        minimumVersion: minimumVersion,
        message: $('cloud-policy-message').value.trim()
      })
    }).then(function (payload) {
      state.cloudServicePolicy = payload.policy || null;
      renderCloudServicePolicy();
      toast(minimumVersion ? '已保存,将应用于后续更新检查' : '云服务门禁已关闭');
    }).catch(function (error) {
      toast('保存云服务门禁失败:' + error.message, true);
    }).then(function () { $('cloud-policy-save').disabled = false; });
  });

  // ---------- community QR code ----------
  function loadCommunityQr() {
    $('community-qr-refresh').disabled = true;
    return api('/admin/community/wechat-qr').then(function (payload) {
      state.communityQr = payload.image || null;
      renderCommunityQr();
    }).catch(function (error) {
      if (error.message !== 'unauthorized') {
        $('community-qr-status').textContent = '读取失败:' + error.message;
        toast('读取社群二维码失败:' + error.message, true);
      }
    }).then(function () { $('community-qr-refresh').disabled = false; });
  }

  function renderCommunityQr() {
    var image = state.communityQr || {};
    var preview = $('community-qr-preview');
    if (!image.configured) {
      $('community-qr-status').textContent = '尚未上传二维码;公开地址将在首次上传后可用';
      preview.classList.add('hidden');
      preview.removeAttribute('src');
      return;
    }
    var details = [];
    if (image.contentType) details.push(image.contentType);
    if (image.size) details.push(fmtBytes(image.size));
    if (image.uploadedAt) details.push('更新于 ' + fmtDate(image.uploadedAt));
    $('community-qr-status').textContent = '当前公开地址: ' + image.publicUrl + (details.length ? ' · ' + details.join(' · ') : '');
    preview.src = image.publicUrl + '?preview=' + encodeURIComponent(image.etag || String(Date.now()));
    preview.classList.remove('hidden');
  }

  $('community-qr-refresh').addEventListener('click', function () { loadCommunityQr(); });
  $('community-qr-copy').addEventListener('click', function () {
    var publicUrl = state.communityQr && state.communityQr.publicUrl
      ? state.communityQr.publicUrl
      : location.origin + '/community/wechat-qr';
    navigator.clipboard.writeText(publicUrl).then(function () { toast('二维码公开链接已复制'); });
  });
  $('community-qr-upload').addEventListener('click', function () {
    var file = $('community-qr-input').files[0];
    if (!file) { toast('请先选择二维码图片', true); return; }
    if (file.size > 5 * 1024 * 1024) { toast('图片不能超过 5 MB', true); return; }
    var allowed = ['image/jpeg', 'image/png', 'image/webp'];
    if (allowed.indexOf(file.type) < 0) { toast('仅支持 JPEG、PNG 或 WebP 图片', true); return; }

    $('community-qr-upload').disabled = true;
    api('/admin/community/wechat-qr', {
      method: 'PUT',
      headers: { 'content-type': file.type },
      body: file
    }).then(function (payload) {
      state.communityQr = payload.image || null;
      $('community-qr-input').value = '';
      renderCommunityQr();
      toast('微信群二维码已替换');
    }).catch(function (error) {
      toast('上传二维码失败:' + error.message, true);
    }).then(function () { $('community-qr-upload').disabled = false; });
  });

  // ---------- analytics ----------
  var daysSeg = $('days-seg');
  daysSeg.addEventListener('click', function (event) {
    var button = event.target.closest('button');
    if (!button) return;
    var buttons = daysSeg.querySelectorAll('button');
    for (var i = 0; i < buttons.length; i += 1) buttons[i].classList.remove('active');
    button.classList.add('active');
    state.days = Number(button.getAttribute('data-days')) || 30;
    loadAnalytics();
  });
  $('analytics-refresh').addEventListener('click', function () { loadAnalytics(); });

  function analyticsBodies() {
    return ['daily-body', 'devices-body', 'versions-body', 'os-body', 'downloads-body'];
  }

  function loadAnalytics() {
    if (!state.analyticsConfigured) {
      renderTiles();
      var bodies = analyticsBodies();
      for (var i = 0; i < bodies.length; i += 1) {
        var body = $(bodies[i]);
        body.textContent = '';
        body.appendChild(el('div', 'empty-note', '统计查询未配置'));
      }
      return;
    }
    var ids = analyticsBodies();
    for (var j = 0; j < ids.length; j += 1) $(ids[j]).classList.add('loading');

    var metrics = ['summary', 'daily', 'devices', 'versions', 'os', 'downloads'];
    Promise.all(metrics.map(function (metric) {
      return api('/admin/analytics/' + metric + '?days=' + state.days + '&limit=10')
        .then(function (payload) { return payload.rows || []; })
        .catch(function (error) {
          if (error.status === 501) { state.analyticsConfigured = false; }
          return { error: error.message };
        });
    })).then(function (results) {
      for (var k = 0; k < metrics.length; k += 1) state.analytics[metrics[k]] = results[k];
      $('analytics-config-hint').classList.toggle('hidden', state.analyticsConfigured);
      renderAnalytics();
    }).then(function () {
      for (var m = 0; m < ids.length; m += 1) $(ids[m]).classList.remove('loading');
    });
  }

  function metricRows(metric) {
    var rows = state.analytics[metric];
    return Array.isArray(rows) ? rows : null;
  }

  function renderAnalytics() {
    renderTiles();
    renderDaily();
    renderDeviceBars('devices-body', 'devices', function (row) { return (row.brand ? row.brand + ' ' : '') + row.model; });
    renderDeviceBars('versions-body', 'versions', function (row) { return 'v' + row.version; });
    renderDeviceBars('os-body', 'os', function (row) { return 'Android ' + row.osVersion; });
    renderDownloads();
  }

  function summaryFor(eventName) {
    var rows = metricRows('summary') || [];
    for (var i = 0; i < rows.length; i += 1) {
      if (rows[i].event === eventName) return rows[i];
    }
    return { total: 0, uniqueDevices: 0 };
  }

  function renderTiles() {
    var wrap = $('tile-row');
    wrap.textContent = '';
    var check = summaryFor('check');
    var download = summaryFor('download');
    var latest = latestStableVersion();
    var subtitle = '近 ' + state.days + ' 天';
    var tiles = [
      { label: '更新检查次数', value: fmtNum(check.total), sub: subtitle },
      { label: '独立设备数', value: fmtNum(check.uniqueDevices), sub: subtitle + ' · 按匿名安装 ID 去重' },
      { label: 'APK 下载次数', value: fmtNum(download.total), sub: subtitle },
      { label: '最新正式版', value: latest || '—', sub: '来自版本管理' }
    ];
    for (var i = 0; i < tiles.length; i += 1) {
      var tile = el('div', 'tile');
      tile.appendChild(el('div', 'tile-label', tiles[i].label));
      tile.appendChild(el('div', 'tile-value', tiles[i].value));
      tile.appendChild(el('div', 'tile-sub', tiles[i].sub));
      wrap.appendChild(tile);
    }
  }

  function latestStableVersion() {
    var best = '';
    for (var i = 0; i < state.releases.length; i += 1) {
      var release = state.releases[i];
      if (release.draft || release.track !== 'stable') continue;
      if (!best || compareVersions(release.version, best) > 0) best = release.version;
    }
    return best ? 'v' + best : '';
  }

  function compareVersions(left, right) {
    var a = String(left || '').split('.');
    var b = String(right || '').split('.');
    var length = Math.max(a.length, b.length);
    for (var i = 0; i < length; i += 1) {
      var x = Number(a[i] || 0), y = Number(b[i] || 0);
      if (x !== y) return x > y ? 1 : -1;
    }
    return 0;
  }

  // Build a contiguous UTC day series for the selected window, filling gaps with zero.
  function dailySeries() {
    var rows = metricRows('daily') || [];
    var byDay = {};
    for (var i = 0; i < rows.length; i += 1) {
      var row = rows[i];
      var key = String(row.day || '').slice(0, 10);
      if (!key) continue;
      if (!byDay[key]) byDay[key] = { checks: 0, downloads: 0, uniques: 0 };
      if (row.event === 'check') {
        byDay[key].checks = Number(row.total) || 0;
        byDay[key].uniques = Number(row.uniqueDevices) || 0;
      } else if (row.event === 'download') {
        byDay[key].downloads = Number(row.total) || 0;
      }
    }
    var days = [];
    var now = new Date();
    for (var offset = state.days - 1; offset >= 0; offset -= 1) {
      var date = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() - offset));
      var key2 = date.toISOString().slice(0, 10);
      var value = byDay[key2] || { checks: 0, downloads: 0, uniques: 0 };
      days.push({ day: key2, checks: value.checks, downloads: value.downloads, uniques: value.uniques });
    }
    return days;
  }

  function renderDaily() {
    var body = $('daily-body');
    var legend = $('daily-legend');
    body.textContent = '';
    legend.textContent = '';

    var rows = metricRows('daily');
    if (!rows) { body.appendChild(el('div', 'empty-note', '查询失败')); return; }
    var days = dailySeries();
    var hasData = days.some(function (d) { return d.checks > 0 || d.downloads > 0; });

    if (state.tableMode['daily']) {
      body.appendChild(buildTable(
        ['日期', '更新检查', '独立设备', 'APK 下载'],
        days.map(function (d) { return [d.day, fmtNum(d.checks), fmtNum(d.uniques), fmtNum(d.downloads)]; }),
        [false, true, true, true]
      ));
      return;
    }
    if (!hasData) { body.appendChild(el('div', 'empty-note', '暂无数据 — 客户端开始检查更新后这里会出现趋势')); return; }

    var series = [
      { key: 'checks', label: '更新检查', colorVar: 'var(--series-1)' },
      { key: 'downloads', label: 'APK 下载', colorVar: 'var(--series-2)' }
    ];
    for (var s = 0; s < series.length; s += 1) {
      var item = el('span');
      var lineKey = el('span', 'legend-key');
      lineKey.style.borderTopColor = series[s].colorVar;
      item.appendChild(lineKey);
      item.appendChild(document.createTextNode(series[s].label));
      legend.appendChild(item);
    }
    drawLineChart(body, days, series);
  }

  function niceTicks(maxValue) {
    if (maxValue <= 4) return [0, 1, 2, 3, 4].slice(0, Math.max(2, maxValue + 1));
    var rough = maxValue / 4;
    var magnitude = Math.pow(10, Math.floor(Math.log(rough) / Math.LN10));
    var candidates = [1, 2, 2.5, 5, 10];
    var step = magnitude;
    for (var i = 0; i < candidates.length; i += 1) {
      if (candidates[i] * magnitude >= rough) { step = candidates[i] * magnitude; break; }
    }
    var ticks = [];
    for (var value = 0; value <= maxValue + step * 0.999; value += step) ticks.push(Math.round(value));
    return ticks;
  }

  function drawLineChart(container, days, series) {
    var width = Math.max(320, container.clientWidth - 4);
    var height = 240;
    var pad = { left: 46, right: 18, top: 12, bottom: 28 };
    var innerW = width - pad.left - pad.right;
    var innerH = height - pad.top - pad.bottom;

    var maxValue = 1;
    days.forEach(function (d) { maxValue = Math.max(maxValue, d.checks, d.downloads); });
    var ticks = niceTicks(maxValue);
    var yMax = ticks[ticks.length - 1] || 1;

    function xPos(index) {
      if (days.length === 1) return pad.left + innerW / 2;
      return pad.left + (innerW * index) / (days.length - 1);
    }
    function yPos(value) { return pad.top + innerH - (innerH * value) / yMax; }

    var svg = svgEl('svg', { width: width, height: height, viewBox: '0 0 ' + width + ' ' + height, role: 'img' });
    svg.setAttribute('aria-label', '每日请求趋势折线图');

    ticks.forEach(function (tick) {
      var y = yPos(tick);
      svg.appendChild(svgEl('line', { x1: pad.left, x2: width - pad.right, y1: y, y2: y, stroke: tick === 0 ? 'var(--axis)' : 'var(--grid)', 'stroke-width': 1 }));
      var label = svgEl('text', { x: pad.left - 8, y: y + 4, 'text-anchor': 'end', 'font-size': 11, fill: 'var(--ink-3)' });
      label.textContent = tick.toLocaleString('zh-CN');
      svg.appendChild(label);
    });

    var labelEvery = Math.max(1, Math.ceil(days.length / 8));
    days.forEach(function (d, index) {
      if (index % labelEvery !== 0 && index !== days.length - 1) return;
      var label = svgEl('text', { x: xPos(index), y: height - 8, 'text-anchor': 'middle', 'font-size': 11, fill: 'var(--ink-3)' });
      label.textContent = d.day.slice(5);
      svg.appendChild(label);
    });

    series.forEach(function (spec) {
      var path = '';
      days.forEach(function (d, index) {
        path += (index === 0 ? 'M' : 'L') + xPos(index).toFixed(1) + ' ' + yPos(d[spec.key]).toFixed(1) + ' ';
      });
      svg.appendChild(svgEl('path', {
        d: path.trim(), fill: 'none', stroke: spec.colorVar,
        'stroke-width': 2, 'stroke-linecap': 'round', 'stroke-linejoin': 'round'
      }));
    });

    var crosshair = svgEl('line', { y1: pad.top, y2: pad.top + innerH, stroke: 'var(--axis)', 'stroke-width': 1, opacity: 0 });
    svg.appendChild(crosshair);
    var hoverDots = series.map(function (spec) {
      var dot = svgEl('circle', { r: 4.5, fill: spec.colorVar, stroke: 'var(--surface-1)', 'stroke-width': 2, opacity: 0 });
      svg.appendChild(dot);
      return dot;
    });

    var tooltip = el('div', 'viz-tooltip');
    container.appendChild(svg);
    container.appendChild(tooltip);

    var overlay = svgEl('rect', { x: pad.left, y: pad.top, width: innerW, height: innerH, fill: 'transparent' });
    svg.appendChild(overlay);

    function hideHover() {
      crosshair.setAttribute('opacity', 0);
      hoverDots.forEach(function (dot) { dot.setAttribute('opacity', 0); });
      tooltip.style.display = 'none';
    }

    overlay.addEventListener('pointermove', function (event) {
      var rect = svg.getBoundingClientRect();
      var px = event.clientX - rect.left;
      var ratio = (px - pad.left) / Math.max(1, innerW);
      var index = Math.round(ratio * (days.length - 1));
      index = Math.max(0, Math.min(days.length - 1, index));
      var day = days[index];
      var cx = xPos(index);

      crosshair.setAttribute('x1', cx);
      crosshair.setAttribute('x2', cx);
      crosshair.setAttribute('opacity', 1);
      series.forEach(function (spec, si) {
        hoverDots[si].setAttribute('cx', cx);
        hoverDots[si].setAttribute('cy', yPos(day[spec.key]));
        hoverDots[si].setAttribute('opacity', 1);
      });

      tooltip.textContent = '';
      tooltip.appendChild(el('div', 'tt-title', day.day));
      series.forEach(function (spec) {
        var row = el('div', 'tt-row');
        var key = el('span', 'tt-key');
        key.style.borderTopColor = spec.colorVar;
        row.appendChild(key);
        row.appendChild(el('span', 'tt-val', fmtNum(day[spec.key])));
        row.appendChild(el('span', 'tt-label', spec.label));
        tooltip.appendChild(row);
      });
      var extra = el('div', 'tt-row');
      extra.appendChild(el('span', 'tt-key'));
      extra.appendChild(el('span', 'tt-val', fmtNum(day.uniques)));
      extra.appendChild(el('span', 'tt-label', '独立设备'));
      tooltip.appendChild(extra);

      tooltip.style.display = 'block';
      var containerRect = container.getBoundingClientRect();
      var left = cx + 14;
      if (left + tooltip.offsetWidth > containerRect.width - 8) {
        left = cx - tooltip.offsetWidth - 14;
      }
      tooltip.style.left = Math.max(4, left) + 'px';
      tooltip.style.top = '10px';
    });
    overlay.addEventListener('pointerleave', hideHover);
  }

  function renderDeviceBars(bodyId, metric, labelOf) {
    var body = $(bodyId);
    body.textContent = '';
    var rows = metricRows(metric);
    if (!rows) { body.appendChild(el('div', 'empty-note', '查询失败')); return; }

    if (state.tableMode[metric]) {
      body.appendChild(buildTable(
        ['名称', '独立设备', '请求次数'],
        rows.map(function (row) { return [labelOf(row), fmtNum(row.uniqueDevices), fmtNum(row.total)]; }),
        [false, true, true]
      ));
      return;
    }
    if (!rows.length) { body.appendChild(el('div', 'empty-note', '暂无数据')); return; }

    var maxValue = 1;
    rows.forEach(function (row) { maxValue = Math.max(maxValue, Number(row.uniqueDevices) || 0); });

    rows.forEach(function (row) {
      var value = Number(row.uniqueDevices) || 0;
      var rowEl = el('div', 'hbar-row');
      var label = el('div', 'hbar-label', labelOf(row));
      label.title = labelOf(row) + ' · 独立设备 ' + fmtNum(value) + ' · 请求 ' + fmtNum(row.total);
      var track = el('div', 'hbar-track');
      var bar = el('div', 'hbar-bar');
      bar.style.width = Math.max(1, (value / maxValue) * 100) + '%';
      track.appendChild(bar);
      track.appendChild(el('span', 'hbar-value', fmtNum(value)));
      rowEl.appendChild(label);
      rowEl.appendChild(track);
      body.appendChild(rowEl);
    });
  }

  function renderDownloads() {
    var body = $('downloads-body');
    body.textContent = '';
    var rows = metricRows('downloads');
    if (!rows) { body.appendChild(el('div', 'empty-note', '查询失败')); return; }
    if (!rows.length) { body.appendChild(el('div', 'empty-note', '暂无下载记录')); return; }
    body.appendChild(buildTable(
      ['版本标签', '安装包', '下载次数'],
      rows.map(function (row) { return [row.tag, row.asset, fmtNum(row.total)]; }),
      [false, false, true]
    ));
  }

  function buildTable(headers, rows, numeric) {
    var table = el('table', 'data-table');
    var thead = el('thead');
    var headRow = el('tr');
    headers.forEach(function (header, index) {
      var th = el('th', numeric[index] ? 'num' : '', header);
      headRow.appendChild(th);
    });
    thead.appendChild(headRow);
    table.appendChild(thead);
    var tbody = el('tbody');
    rows.forEach(function (cells) {
      var tr = el('tr');
      cells.forEach(function (cell, index) {
        tr.appendChild(el('td', numeric[index] ? 'num' : '', cell));
      });
      tbody.appendChild(tr);
    });
    table.appendChild(tbody);
    return table;
  }

  document.addEventListener('click', function (event) {
    var button = event.target.closest('[data-tableswitch]');
    if (!button) return;
    var metric = button.getAttribute('data-tableswitch');
    state.tableMode[metric] = !state.tableMode[metric];
    button.textContent = state.tableMode[metric] ? '图表' : '表格';
    renderAnalytics();
  });

  window.addEventListener('resize', function () {
    clearTimeout(window.__redrawTimer);
    window.__redrawTimer = setTimeout(function () {
      if (!$('page-analytics').classList.contains('hidden')) renderDaily();
    }, 180);
  });

  // ---------- releases ----------
  function loadReleases() {
    return api('/admin/releases').then(function (payload) {
      state.releases = payload.releases || [];
      renderReleases();
      renderTiles();
    }).catch(function (error) {
      if (error.message !== 'unauthorized') toast('加载版本列表失败:' + error.message, true);
    });
  }
  $('releases-refresh').addEventListener('click', function () { loadReleases(); });

  function renderReleases() {
    var list = $('releases-list');
    list.textContent = '';
    $('releases-count').textContent = '共 ' + state.releases.length + ' 个版本';
    if (!state.releases.length) {
      list.appendChild(el('div', 'empty-note', '还没有版本 — 点击「新建版本」或由 CI 自动发布'));
      return;
    }
    state.releases.forEach(function (release) {
      var row = el('div', 'rel-row');
      row.setAttribute('role', 'button');

      var left = el('div');
      var tag = el('span', 'rel-tag', release.tag);
      left.appendChild(tag);
      left.appendChild(el('span', 'chip ' + (release.track === 'stable' ? 'chip-stable' : 'chip-beta'), release.track));
      if (release.draft) left.appendChild(el('span', 'chip chip-draft', '草稿'));
      row.appendChild(left);

      row.appendChild(el('div', 'rel-notes', firstLine(release.releaseNotes) || '(未填写更新日志)'));

      var apkAssets = (release.assets || []).filter(function (asset) {
        return String(asset.name || '').toLowerCase().slice(-4) === '.apk';
      });
      var totalSize = apkAssets.reduce(function (sum, asset) { return sum + (Number(asset.size) || 0); }, 0);
      row.appendChild(el('div', 'rel-meta', apkAssets.length + ' 个安装包' + (totalSize ? ' · ' + fmtBytes(totalSize) : '')));
      row.appendChild(el('div', 'rel-meta pub', fmtDate(release.publishedAt)));

      var editBtn = el('button', 'btn btn-sm', '编辑');
      row.appendChild(editBtn);
      row.addEventListener('click', function () { openEditor(release); });
      list.appendChild(row);
    });
  }

  function firstLine(text) {
    var value = String(text || '').trim();
    if (!value) return '';
    var line = value.split('\\n')[0].trim();
    return line.length > 80 ? line.slice(0, 80) + '…' : line;
  }

  // ---------- editor ----------
  $('new-release-btn').addEventListener('click', function () { openEditor(null); });
  $('editor-cancel').addEventListener('click', closeEditor);
  $('editor-mask').addEventListener('click', function (event) {
    if (event.target === $('editor-mask') && !state.uploading) closeEditor();
  });

  function openEditor(release) {
    state.editor = {
      isNew: !release,
      original: release,
      assets: release ? (release.assets || []).map(function (asset) { return Object.assign({}, asset); }) : []
    };
    $('editor-title').textContent = release ? '编辑版本 ' + release.tag : '新建版本';
    $('f-tag').value = release ? release.tag : '';
    $('f-tag').disabled = Boolean(release);
    $('f-version').value = release ? release.version : '';
    $('f-track').value = release && release.track === 'beta' ? 'beta' : 'stable';
    $('f-draft').checked = Boolean(release && release.draft);
    $('f-prerelease').checked = Boolean(release && release.prerelease);
    $('f-url').value = release ? (release.releaseUrl || '') : '';
    $('f-notes').value = release ? (release.releaseNotes || '') : '';
    $('f-published').value = toLocalInput(release ? release.publishedAt : Date.now());
    $('editor-delete').classList.toggle('hidden', !release);
    renderAssets();
    $('upload-progress').classList.add('hidden');
    $('editor-mask').classList.remove('hidden');
  }

  function closeEditor() {
    if (state.uploading) { toast('正在上传,请稍候…', true); return; }
    $('editor-mask').classList.add('hidden');
    state.editor = null;
  }

  function toLocalInput(ms) {
    var d = new Date(Number(ms) || Date.now());
    function pad(v) { return v < 10 ? '0' + v : String(v); }
    return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) +
      'T' + pad(d.getHours()) + ':' + pad(d.getMinutes());
  }

  function renderAssets() {
    var box = $('assets-box');
    box.textContent = '';
    var assets = state.editor ? state.editor.assets : [];
    if (!assets.length) {
      var none = el('div', 'asset-row');
      none.appendChild(el('span', 'asset-sub', '尚未关联安装包'));
      box.appendChild(none);
      return;
    }
    assets.forEach(function (asset, index) {
      var row = el('div', 'asset-row');
      var info = el('div');
      info.style.minWidth = '0';
      info.appendChild(el('div', 'asset-name', asset.name));
      var subParts = [];
      if (asset.size) subParts.push(fmtBytes(asset.size));
      if (asset.sha256) subParts.push('SHA-256 ' + String(asset.sha256).slice(0, 12) + '…');
      info.appendChild(el('div', 'asset-sub', subParts.join(' · ') || '无校验信息'));
      row.appendChild(info);

      var actions = el('div', 'asset-actions');
      var copyBtn = el('button', 'btn btn-sm', '复制链接');
      copyBtn.addEventListener('click', function (event) {
        event.stopPropagation();
        var tag = state.editor.original ? state.editor.original.tag : $('f-tag').value.trim();
        var link = location.origin + '/downloads/' + encodeURIComponent(tag) + '/' + encodeURIComponent(asset.name);
        navigator.clipboard.writeText(link).then(function () { toast('下载链接已复制'); });
      });
      actions.appendChild(copyBtn);

      var removeBtn = el('button', 'btn btn-sm btn-danger', '删除');
      removeBtn.addEventListener('click', function (event) {
        event.stopPropagation();
        removeAsset(asset, index);
      });
      actions.appendChild(removeBtn);
      row.appendChild(actions);
      box.appendChild(row);
    });
  }

  function removeAsset(asset, index) {
    if (!confirm('删除安装包 ' + asset.name + '?该操作会同时删除 R2 中的文件。')) return;
    var editor = state.editor;
    if (editor.isNew) {
      editor.assets.splice(index, 1);
      renderAssets();
      return;
    }
    var tag = editor.original.tag;
    api('/admin/releases/' + encodeURIComponent(tag) + '/assets/' + encodeURIComponent(asset.name), { method: 'DELETE' })
      .then(function () {
        editor.assets.splice(index, 1);
        renderAssets();
        toast('已删除 ' + asset.name);
        loadReleases();
      })
      .catch(function (error) { toast('删除失败:' + error.message, true); });
  }

  // ---------- upload ----------
  var PART_SIZE = 50 * 1024 * 1024;
  var SIMPLE_LIMIT = 45 * 1024 * 1024;

  var uploadZone = $('upload-zone');
  $('upload-pick').addEventListener('click', function () { $('upload-input').click(); });
  $('upload-input').addEventListener('change', function () {
    var files = Array.prototype.slice.call(this.files || []);
    this.value = '';
    uploadQueue(files);
  });
  uploadZone.addEventListener('dragover', function (event) {
    event.preventDefault();
    uploadZone.classList.add('dragover');
  });
  uploadZone.addEventListener('dragleave', function () { uploadZone.classList.remove('dragover'); });
  uploadZone.addEventListener('drop', function (event) {
    event.preventDefault();
    uploadZone.classList.remove('dragover');
    uploadQueue(Array.prototype.slice.call(event.dataTransfer.files || []));
  });

  function uploadQueue(files) {
    files = files.filter(function (file) {
      var name = file.name.toLowerCase();
      return name.slice(-4) === '.apk' || name.slice(-11) === '.apk.sha256';
    });
    if (!files.length) { toast('仅支持 .apk 与 .apk.sha256 文件', true); return; }
    var tag = state.editor.original ? state.editor.original.tag : $('f-tag').value.trim();
    if (!tag) { toast('请先填写版本标签(tag)再上传', true); return; }

    var chain = Promise.resolve();
    files.forEach(function (file) {
      chain = chain.then(function () { return uploadOne(tag, file); });
    });
    chain.catch(function (error) {
      toast('上传失败:' + error.message, true);
    }).then(function () {
      state.uploading = false;
      $('upload-progress').classList.add('hidden');
    });
  }

  function setProgress(label, ratio) {
    $('upload-progress').classList.remove('hidden');
    $('progress-fill').style.width = Math.round(ratio * 100) + '%';
    $('progress-text').textContent = label + ' ' + Math.round(ratio * 100) + '%';
  }

  function sha256Hex(buffer) {
    return crypto.subtle.digest('SHA-256', buffer).then(function (hash) {
      var bytes = new Uint8Array(hash);
      var hex = '';
      for (var i = 0; i < bytes.length; i += 1) hex += (bytes[i] < 16 ? '0' : '') + bytes[i].toString(16);
      return hex;
    });
  }

  function uploadOne(tag, file) {
    state.uploading = true;
    setProgress('计算 SHA-256:' + file.name, 0);
    return file.arrayBuffer().then(function (buffer) {
      return sha256Hex(buffer);
    }).then(function (digest) {
      if (file.size <= SIMPLE_LIMIT) {
        return uploadSimple(tag, file, digest);
      }
      return uploadMultipart(tag, file, digest);
    }).then(function (digest) {
      if (file.name.toLowerCase().slice(-4) !== '.apk') return;
      var assets = state.editor.assets;
      var existing = null;
      for (var i = 0; i < assets.length; i += 1) {
        if (assets[i].name === file.name) { existing = assets[i]; break; }
      }
      var entry = existing || { name: file.name };
      entry.sha256 = digest;
      entry.size = file.size;
      if (!existing) assets.push(entry);
      renderAssets();
      if (!state.editor.isNew) {
        return api('/admin/releases/' + encodeURIComponent(tag), {
          method: 'PATCH',
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify({ assets: assets })
        }).then(function () { loadReleases(); });
      }
    }).then(function () {
      toast(file.name + ' 上传完成');
    });
  }

  function assetUrl(tag, name, params) {
    var query = '';
    if (params) {
      var pairs = [];
      for (var key in params) {
        if (Object.prototype.hasOwnProperty.call(params, key)) {
          pairs.push(encodeURIComponent(key) + '=' + encodeURIComponent(params[key]));
        }
      }
      query = '?' + pairs.join('&');
    }
    return '/admin/releases/' + encodeURIComponent(tag) + '/assets/' + encodeURIComponent(name) + query;
  }

  function xhrUpload(method, url, body, headers, onProgress) {
    return new Promise(function (resolve, reject) {
      var xhr = new XMLHttpRequest();
      xhr.open(method, url);
      xhr.setRequestHeader('authorization', 'Bearer ' + state.token);
      for (var key in headers) {
        if (Object.prototype.hasOwnProperty.call(headers, key)) xhr.setRequestHeader(key, headers[key]);
      }
      if (xhr.upload && onProgress) {
        xhr.upload.addEventListener('progress', function (event) {
          if (event.lengthComputable) onProgress(event.loaded / event.total);
        });
      }
      xhr.addEventListener('load', function () {
        var payload = null;
        try { payload = JSON.parse(xhr.responseText); } catch (err) { /* keep null */ }
        if (xhr.status >= 200 && xhr.status < 300) { resolve(payload); return; }
        reject(new Error(payload && payload.error ? payload.error : ('HTTP ' + xhr.status)));
      });
      xhr.addEventListener('error', function () { reject(new Error('网络错误')); });
      xhr.send(body);
    });
  }

  function uploadSimple(tag, file, digest) {
    return xhrUpload('PUT', assetUrl(tag, file.name, null), file, {
      'content-type': file.type || 'application/vnd.android.package-archive',
      'x-content-sha256': digest
    }, function (ratio) {
      setProgress('上传 ' + file.name, ratio);
    }).then(function () { return digest; });
  }

  function uploadMultipart(tag, file, digest) {
    var uploadId = null;
    return api(assetUrl(tag, file.name, { action: 'mpu-create' }), {
      method: 'POST',
      headers: {
        'content-type': file.type || 'application/vnd.android.package-archive',
        'x-content-sha256': digest,
        'x-content-size': String(file.size)
      }
    }).then(function (payload) {
      uploadId = payload.upload.uploadId;
      var totalParts = Math.ceil(file.size / PART_SIZE);
      var parts = [];
      var chain = Promise.resolve();
      for (var partNumber = 1; partNumber <= totalParts; partNumber += 1) {
        (function (pn) {
          chain = chain.then(function () {
            var start = (pn - 1) * PART_SIZE;
            var chunk = file.slice(start, Math.min(file.size, start + PART_SIZE));
            return xhrUpload('PUT', assetUrl(tag, file.name, {
              action: 'mpu-uploadpart', uploadId: uploadId, partNumber: String(pn)
            }), chunk, {}, function (ratio) {
              setProgress('上传 ' + file.name + '(分片 ' + pn + '/' + totalParts + ')', (pn - 1 + ratio) / totalParts);
            }).then(function (payload2) {
              parts.push(payload2.part);
            });
          });
        })(partNumber);
      }
      return chain.then(function () {
        return api(assetUrl(tag, file.name, { action: 'mpu-complete', uploadId: uploadId }), {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify({ parts: parts, sha256: digest, size: file.size })
        });
      });
    }).then(function () {
      return digest;
    }).catch(function (error) {
      if (uploadId) {
        api(assetUrl(tag, file.name, { action: 'mpu-abort', uploadId: uploadId }), { method: 'DELETE' }).catch(function () {});
      }
      throw error;
    });
  }

  // ---------- save / delete ----------
  $('editor-save').addEventListener('click', function () {
    var editor = state.editor;
    if (!editor) return;
    var tag = $('f-tag').value.trim();
    if (!tag) { toast('版本标签(tag)不能为空', true); return; }
    var publishedLocal = $('f-published').value;
    var publishedAt = publishedLocal ? new Date(publishedLocal).getTime() : Date.now();
    var body = {
      tag: tag,
      version: $('f-version').value.trim() || tag.replace(/^v/i, ''),
      track: $('f-track').value,
      draft: $('f-draft').checked,
      prerelease: $('f-prerelease').checked,
      publishedAt: publishedAt,
      releaseUrl: $('f-url').value.trim(),
      releaseNotes: $('f-notes').value,
      assets: editor.assets
    };
    $('editor-save').disabled = true;
    var request = editor.isNew
      ? api('/admin/releases', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) })
      : api('/admin/releases/' + encodeURIComponent(tag), { method: 'PATCH', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) });
    request.then(function () {
      toast('已保存 ' + tag);
      $('editor-mask').classList.add('hidden');
      state.editor = null;
      loadReleases();
    }).catch(function (error) {
      toast('保存失败:' + error.message, true);
    }).then(function () { $('editor-save').disabled = false; });
  });

  $('editor-delete').addEventListener('click', function () {
    var editor = state.editor;
    if (!editor || editor.isNew) return;
    var tag = editor.original.tag;
    if (!confirm('删除版本 ' + tag + '?客户端将不再收到该版本。R2 中已上传的安装包不会被删除。')) return;
    api('/admin/releases/' + encodeURIComponent(tag), { method: 'DELETE' }).then(function () {
      toast('已删除 ' + tag);
      $('editor-mask').classList.add('hidden');
      state.editor = null;
      loadReleases();
    }).catch(function (error) { toast('删除失败:' + error.message, true); });
  });

  // ---------- boot ----------
  var savedToken = localStorage.getItem(TOKEN_KEY);
  if (savedToken) {
    login(savedToken).catch(function () { /* stays on login view */ });
  }
})();
</script>
</body>
</html>`;

export default ADMIN_HTML;
