/**
 * 仅 admin 后台 + 已登录 时显示「运营工具」。
 * 前台、未登录、其它子域一律不显示。
 */
(function () {
  if (window.__alucardOpsInjected) return;
  window.__alucardOpsInjected = true;

  // 非管理域直接退出（前台 home 即使误引入也不执行）
  if (location.hostname.indexOf("admin.") !== 0) return;

  var D = {
    ops: "https://ops.alucard.top",
    daily: "https://daily.alucard.top",
    stats: "https://stats.alucard.top",
    down: "https://down.alucard.top",
    ads: "https://ads.alucard.top",
    ota: "https://ota.alucard.top",
    home: "https://home.alucard.top",
  };

  var LINKS = [
    { title: "每日更新登记", desc: "热播/最新按日存档", href: D.daily + "/" },
    { title: "用户统计", desc: "日活 · 下载 · 版本", href: D.stats + "/" },
    { title: "App 下载页", desc: "对外分发落地页", href: D.down + "/" },
    { title: "广告合作", desc: "广告位说明", href: D.ads + "/" },
    { title: "OTA 清单", desc: "版本信息", href: D.ota + "/v.json" },
    { title: "Web 前台", desc: "用户站点", href: D.home + "/" },
    { title: "服务健康", desc: "运维状态页", href: D.ops + "/" },
  ];

  function isLoggedIn() {
    try {
      var raw = localStorage.getItem("auth");
      if (!raw) return false;
      var a = JSON.parse(raw);
      return !!(a && a.value && String(a.value).length > 0);
    } catch (e) {
      return false;
    }
  }

  /** 管理后台路由（含登录页不展示工具） */
  function isManageBackendRoute() {
    var p = location.pathname || "";
    if (p === "/login" || p.indexOf("/login") === 0) return false;
    return p === "/manage" || p.indexOf("/manage/") === 0 || p.indexOf("/manage") === 0;
  }

  function shouldShow() {
    return isLoggedIn() && isManageBackendRoute();
  }

  function ensureUi() {
    var root = document.getElementById("alucard-ops-root");
    if (!shouldShow()) {
      if (root) {
        root.style.display = "none";
        var panel = document.getElementById("alucard-ops-panel");
        var mask = document.getElementById("alucard-ops-mask");
        if (panel) panel.hidden = true;
        if (mask) mask.hidden = true;
      }
      return;
    }

    if (!root) {
      root = document.createElement("div");
      root.id = "alucard-ops-root";
      document.body.appendChild(root);
      injectStyles();
      root.innerHTML =
        '<button type="button" id="alucard-ops-fab" title="运营工具">运营工具</button>' +
        '<div id="alucard-ops-mask" hidden></div>' +
        '<aside id="alucard-ops-panel" hidden>' +
        '  <div class="ops-hd">' +
        "    <div>" +
        '      <div class="ops-title">运营工具</div>' +
        '      <div class="ops-sub">登录后台后可见</div>' +
        "    </div>" +
        '    <button type="button" id="alucard-ops-close" aria-label="关闭">×</button>' +
        "  </div>" +
        '  <div class="ops-list" id="alucard-ops-list"></div>' +
        "</aside>";

      document.getElementById("alucard-ops-list").innerHTML = LINKS.map(function (item) {
        return (
          '<a class="ops-item" href="' +
          item.href +
          '" target="_blank" rel="noopener">' +
          '<div class="ops-item-t">' +
          item.title +
          "</div>" +
          '<div class="ops-item-d">' +
          item.desc +
          "</div>" +
          "</a>"
        );
      }).join("");

      document.getElementById("alucard-ops-fab").onclick = function () {
        document.getElementById("alucard-ops-panel").hidden = false;
        document.getElementById("alucard-ops-mask").hidden = false;
      };
      function closePanel() {
        document.getElementById("alucard-ops-panel").hidden = true;
        document.getElementById("alucard-ops-mask").hidden = true;
      }
      document.getElementById("alucard-ops-close").onclick = closePanel;
      document.getElementById("alucard-ops-mask").onclick = closePanel;
    }
    root.style.display = "block";
  }

  function injectStyles() {
    if (document.getElementById("alucard-ops-style")) return;
    var s = document.createElement("style");
    s.id = "alucard-ops-style";
    s.textContent =
      "#alucard-ops-fab{position:fixed;right:18px;bottom:22px;z-index:2147483000;background:#f5c542;color:#1a1000;border:0;border-radius:999px;padding:12px 16px;font-weight:800;font-size:13px;cursor:pointer;box-shadow:0 10px 28px rgba(245,197,66,.35);font-family:system-ui,sans-serif}" +
      "#alucard-ops-mask{position:fixed;inset:0;background:rgba(0,0,0,.45);z-index:2147483001}" +
      "#alucard-ops-panel{position:fixed;top:0;right:0;height:100%;width:min(360px,92vw);background:#12121a;color:#f2f2f5;z-index:2147483002;border-left:1px solid #2a2a36;display:flex;flex-direction:column;font-family:system-ui,sans-serif}" +
      "#alucard-ops-panel[hidden],#alucard-ops-mask[hidden]{display:none!important}" +
      "#alucard-ops-panel .ops-hd{display:flex;justify-content:space-between;padding:18px 16px 12px;border-bottom:1px solid #2a2a36}" +
      "#alucard-ops-panel .ops-title{font-size:17px;font-weight:800;color:#f5c542}" +
      "#alucard-ops-panel .ops-sub{font-size:12px;color:#9a9aa8;margin-top:4px}" +
      "#alucard-ops-close{background:transparent;border:0;color:#9a9aa8;font-size:24px;cursor:pointer}" +
      "#alucard-ops-panel .ops-list{padding:12px;overflow:auto;flex:1}" +
      "#alucard-ops-panel .ops-item{display:block;text-decoration:none;color:inherit;border:1px solid #2a2a36;background:#15151e;border-radius:12px;padding:12px 14px;margin-bottom:8px}" +
      "#alucard-ops-panel .ops-item:hover{border-color:#5a4a20}" +
      "#alucard-ops-panel .ops-item-t{font-size:14px;font-weight:700}" +
      "#alucard-ops-panel .ops-item-d{font-size:12px;color:#9a9aa8;margin-top:4px}";
    document.head.appendChild(s);
  }

  function boot() {
    ensureUi();
  }
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", boot);
  } else {
    boot();
  }
  // SPA 路由 / 登录状态变化
  var last = location.href + "|" + isLoggedIn();
  setInterval(function () {
    var cur = location.href + "|" + isLoggedIn();
    if (cur !== last) {
      last = cur;
      ensureUi();
    }
  }, 400);
  window.addEventListener("popstate", ensureUi);
  window.addEventListener("storage", ensureUi);
})();
