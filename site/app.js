(function () {
  function fmtDate(iso) {
    if (!iso) return "";
    var d = new Date(iso);
    if (isNaN(d.getTime())) return "";
    return d.getFullYear() + "-" +
      String(d.getMonth() + 1).padStart(2, "0") + "-" +
      String(d.getDate()).padStart(2, "0");
  }

  fetch("version.json", { cache: "no-store" })
    .then(function (r) { return r.ok ? r.json() : null; })
    .then(function (v) {
      if (!v || !v.apk) return;
      document.getElementById("version").textContent = v.version || v.apk;
      var btn = document.getElementById("downloadBtn");
      btn.setAttribute("href", v.apk);
      if (v.updated) {
        document.getElementById("updated").textContent = "更新于 " + fmtDate(v.updated);
      }
      if (v.sha256) {
        document.getElementById("sha").textContent = "SHA-256: " + v.sha256;
      }
      if (v.releaseUrl) {
        document.getElementById("releaseLink").setAttribute("href", v.releaseUrl);
      }
    })
    .catch(function () { /* 保持指向 GitHub Releases 的兜底链接 */ });
})();
