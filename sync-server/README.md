# Taskra 同步服务（开发中）

同步服务和安卓客户端已接入，服务器上的独立实例处于验收中；独立外网和真实手机数据迁移尚未完成，**暂不能用于真实数据**。服务监听服务器本机 `127.0.0.1:38127`，由 Tailscale Funnel 的 `/taskra-sync` 单独转发，现有网站根路径不变。服务器实例由用户级 `taskra-sync.service` 管理，数据位于 `~/taskra-sync/data/`。

本地运行内核测试：

```powershell
cd "D:\My Project\Taskra-tongbu\sync-server"
python -m unittest -v test_store test_pairing test_blobs test_service
```

不把真实作业、配对码、设备令牌或服务器数据库放入仓库。首次配对码仅能在服务器本机生成，具体操作应在安全备份及上线验证完成后进行。同步不替代独立备份；服务器保存的记录和附件不是端到端加密的。尚需按 `docs/SYNC-DESIGN.md` 完成跨网络、真实设备升级和同步验收。
