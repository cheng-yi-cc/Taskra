(function () {
  var REPO = "cheng-yi-cc/Taskra";
  var FALLBACK_LATEST_APK = "https://github.com/" + REPO + "/releases/latest/download/taskra-latest.apk";
  var FALLBACK_RELEASES_URL = "https://github.com/" + REPO + "/releases/latest";

  function fmtDate(iso) {
    if (!iso) return "";
    var d = new Date(iso);
    if (isNaN(d.getTime())) return "";
    return d.getFullYear() + "-" +
      String(d.getMonth() + 1).padStart(2, "0") + "-" +
      String(d.getDate()).padStart(2, "0");
  }

  function parseSemver(ver) {
    if (!ver || typeof ver !== "string") return [0, 0, 0];
    var clean = ver.trim().replace(/^v/i, "");
    var parts = clean.split(/[.-]/);
    return [
      parseInt(parts[0], 10) || 0,
      parseInt(parts[1], 10) || 0,
      parseInt(parts[2], 10) || 0
    ];
  }

  function compareSemver(a, b) {
    var pa = parseSemver(a);
    var pb = parseSemver(b);
    for (var i = 0; i < 3; i++) {
      if (pa[i] !== pb[i]) return pa[i] - pb[i];
    }
    return 0;
  }

  function extractGhRelease(data) {
    if (!data || !data.tag_name) return null;
    var tag = data.tag_name;
    var assets = Array.isArray(data.assets) ? data.assets : [];
    var exactName = "taskra-" + tag + ".apk";
    var apkAsset = null;
    for (var i = 0; i < assets.length; i++) {
      var a = assets[i];
      if (a && a.name === exactName) {
        apkAsset = a;
        break;
      }
    }
    if (!apkAsset) {
      for (var j = 0; j < assets.length; j++) {
        var b = assets[j];
        if (b && typeof b.name === "string" && /\.apk$/i.test(b.name)) {
          apkAsset = b;
          break;
        }
      }
    }
    var sha = "";
    if (apkAsset && typeof apkAsset.digest === "string") {
      sha = apkAsset.digest.replace(/^sha256:/i, "");
    }
    return {
      version: tag,
      apkUrl: (apkAsset && apkAsset.browser_download_url) ||
        ("https://github.com/" + REPO + "/releases/download/" + tag + "/taskra-" + tag + ".apk"),
      sha256: sha,
      updated: (apkAsset && apkAsset.updated_at) || data.published_at || "",
      releaseUrl: data.html_url || ("https://github.com/" + REPO + "/releases/tag/" + tag)
    };
  }

  function checkRelativeApkReachable(url) {
    if (!url || /^https?:\/\//i.test(url)) {
      return Promise.resolve(Boolean(url));
    }
    return fetch(url, { method: "HEAD", cache: "no-store" })
      .then(function (r) {
        var ct = (r.headers && r.headers.get("content-type")) || "";
        return r.ok && ct.indexOf("text/html") === -1;
      })
      .catch(function () { return false; });
  }

  function applyReleaseUI(info) {
    if (!info) return;
    var verEl = document.getElementById("version");
    var updatedEl = document.getElementById("updated");
    var btnEl = document.getElementById("downloadBtn");
    var btnTextEl = document.getElementById("downloadBtnText");
    var releaseLinkEl = document.getElementById("releaseLink");
    var shaEl = document.getElementById("sha");
    var copyBtn = document.getElementById("copyShaBtn");

    if (verEl && info.version) {
      verEl.textContent = info.version;
    }
    if (updatedEl) {
      var dateStr = fmtDate(info.updated);
      updatedEl.textContent = dateStr ? ("发布于 " + dateStr) : "最新正式版";
    }
    if (btnEl && info.apkUrl) {
      btnEl.setAttribute("href", info.apkUrl);
    }
    if (btnTextEl && info.version) {
      btnTextEl.textContent = "下载最新安装包（" + info.version + " APK）";
    }
    if (releaseLinkEl && info.releaseUrl) {
      releaseLinkEl.setAttribute("href", info.releaseUrl);
    }
    if (shaEl) {
      if (info.sha256) {
        shaEl.textContent = info.sha256;
        if (copyBtn) {
          copyBtn.hidden = false;
          copyBtn.onclick = function () {
            if (navigator.clipboard && navigator.clipboard.writeText) {
              navigator.clipboard.writeText(info.sha256).then(function () {
                copyBtn.textContent = "已复制";
                setTimeout(function () { copyBtn.textContent = "复制"; }, 1600);
              });
            }
          };
        }
      } else {
        shaEl.textContent = "详见 GitHub Releases 校验文件";
      }
    }
  }

  // 并行拉取同源 version.json 与 GitHub Releases 最新版，始终保证指向最新 Release 安装包
  var localPromise = fetch("version.json", { cache: "no-store" })
    .then(function (r) { return r.ok ? r.json() : null; })
    .catch(function () { return null; });

  var ghPromise = fetch("https://api.github.com/repos/" + REPO + "/releases/latest", {
    headers: { "Accept": "application/vnd.github+json" },
    cache: "no-store"
  })
    .then(function (r) { return r.ok ? r.json() : null; })
    .then(extractGhRelease)
    .catch(function () { return null; });

  Promise.all([localPromise, ghPromise]).then(function (results) {
    var local = results[0];
    var gh = results[1];

    // 若 GitHub Releases 比本地 version.json 更新，优先采用 GitHub 最新 Release
    if (gh && (!local || !local.version || compareSemver(gh.version, local.version) > 0)) {
      applyReleaseUI(gh);
      return;
    }

    if (local && local.version && compareSemver(local.version, "v0.0.0") > 0) {
      var candidateApk = local.apk || "";
      checkRelativeApkReachable(candidateApk).then(function (reachable) {
        var finalApkUrl = reachable
          ? candidateApk
          : (local.githubApk || (gh && gh.apkUrl) ||
             ("https://github.com/" + REPO + "/releases/download/" + local.version + "/taskra-" + local.version + ".apk"));
        applyReleaseUI({
          version: local.version,
          apkUrl: finalApkUrl,
          sha256: local.sha256 || (gh && gh.sha256) || "",
          updated: local.updated || (gh && gh.updated) || "",
          releaseUrl: local.releaseUrl || (gh && gh.releaseUrl) || FALLBACK_RELEASES_URL
        });
      });
      return;
    }

    if (gh) {
      applyReleaseUI(gh);
    } else {
      applyReleaseUI({
        version: "v1.0.1",
        apkUrl: FALLBACK_LATEST_APK,
        sha256: "",
        updated: "",
        releaseUrl: FALLBACK_RELEASES_URL
      });
    }
  });

  // 主题切换（暖白方格纸 / 暖灰深色纸）
  var root = document.documentElement;
  var themeToggle = document.getElementById("themeToggle");
  var themeLabel = document.getElementById("themeLabel");

  function setTheme(mode) {
    root.setAttribute("data-theme", mode);
    if (themeLabel) {
      themeLabel.textContent = mode === "dark" ? "暖灰深色纸" : "暖白方格纸";
    }
  }

  var savedTheme = null;
  try { savedTheme = localStorage.getItem("taskra-site-theme"); } catch (e) {}
  if (savedTheme === "dark" || savedTheme === "light") {
    setTheme(savedTheme);
  } else if (window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches) {
    setTheme("dark");
  }

  if (themeToggle) {
    themeToggle.addEventListener("click", function () {
      var next = root.getAttribute("data-theme") === "dark" ? "light" : "dark";
      setTheme(next);
      try { localStorage.setItem("taskra-site-theme", next); } catch (e) {}
    });
  }

  // 交互式预览 Tab 切换
  var tabs = document.querySelectorAll(".preview-tab");
  var screens = {
    todo: document.getElementById("screen-todo"),
    editor: document.getElementById("screen-editor"),
    template: document.getElementById("screen-template")
  };

  function switchScreen(target) {
    if (!screens[target]) return;
    tabs.forEach(function (t) {
      var active = t.getAttribute("data-tab") === target;
      t.classList.toggle("active", active);
      t.setAttribute("aria-selected", active ? "true" : "false");
    });
    Object.keys(screens).forEach(function (k) {
      if (screens[k]) {
        screens[k].hidden = k !== target;
      }
    });
  }

  tabs.forEach(function (tab) {
    tab.addEventListener("click", function () {
      switchScreen(tab.getAttribute("data-tab"));
    });
  });

  document.querySelectorAll("[data-switch-tab]").forEach(function (btn) {
    btn.addEventListener("click", function () {
      switchScreen(btn.getAttribute("data-switch-tab"));
    });
  });

  // 给自己的话便签点击切换示例句子
  var mottoSheet = document.getElementById("mottoSheet");
  var mottoText = document.getElementById("mottoText");
  var mottos = [
    "先做好眼前这一件。",
    "把今天这页方格纸写完，就安心去休息。",
    "难题拆成小步，一笔一划总能做完。"
  ];
  var mottoIdx = 0;
  if (mottoSheet && mottoText) {
    mottoSheet.addEventListener("click", function () {
      mottoIdx = (mottoIdx + 1) % mottos.length;
      mottoText.textContent = mottos[mottoIdx];
    });
  }

  // 待办卡片勾选与纸片消散演示
  var hwCards = document.querySelectorAll(".hw-card");
  var todoCountText = document.getElementById("todoCountText");
  var resetDemoBtn = document.getElementById("resetDemoBtn");

  function updateTodoSummary() {
    var remaining = 0;
    var overdue = 0;
    hwCards.forEach(function (card) {
      if (!card.hidden) {
        remaining++;
        if (card.querySelector(".due-text.overdue")) overdue++;
      }
    });
    if (todoCountText) {
      if (remaining === 0) {
        todoCountText.textContent = "今日待办已全部完成";
      } else {
        todoCountText.textContent = remaining + " 项待办" + (overdue > 0 ? (" · " + overdue + " 项逾期") : "");
      }
    }
    if (resetDemoBtn) {
      resetDemoBtn.hidden = remaining === hwCards.length;
    }
  }

  hwCards.forEach(function (card) {
    var checkBtn = card.querySelector(".hw-check");
    if (!checkBtn) return;
    checkBtn.addEventListener("click", function () {
      if (card.classList.contains("checked")) return;
      card.classList.add("checked");
      setTimeout(function () {
        card.classList.add("dissolving");
      }, 180);
      setTimeout(function () {
        card.hidden = true;
        updateTodoSummary();
      }, 620);
    });
  });

  if (resetDemoBtn) {
    resetDemoBtn.addEventListener("click", function () {
      hwCards.forEach(function (card) {
        card.hidden = false;
        card.classList.remove("checked", "dissolving");
      });
      updateTodoSummary();
    });
  }

  // 编辑器录音播放模拟
  var audioPlayBtn = document.getElementById("audioPlayBtn");
  var audioFill = document.getElementById("audioFill");
  var audioTime = document.getElementById("audioTime");
  var audioTimer = null;
  var audioSec = 15;
  if (audioPlayBtn && audioFill && audioTime) {
    audioPlayBtn.addEventListener("click", function () {
      if (audioTimer) {
        clearInterval(audioTimer);
        audioTimer = null;
        audioPlayBtn.textContent = "▶";
        return;
      }
      audioPlayBtn.textContent = "❚❚";
      audioTimer = setInterval(function () {
        audioSec = (audioSec + 1) % 43;
        var pct = Math.round((audioSec / 42) * 100);
        audioFill.style.width = pct + "%";
        audioTime.textContent = "00:" + String(audioSec).padStart(2, "0");
      }, 350);
    });
  }
})();
