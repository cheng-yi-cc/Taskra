# Taskra 同步服务

同步服务监听服务器本机 `127.0.0.1:38127`，由 Cloudflare Tunnel 的 `/taskra-sync` 提供 HTTPS；同网段可通过受防火墙限制的 TLS 代理直连。服务器实例由用户级 `taskra-sync.service` 管理，数据位于 `~/taskra-sync/data/`。操作与验证状态见 `docs/SYNC-DESIGN.md`。

本地运行内核测试：

```powershell
cd "D:\My Project\Taskra-tongbu\sync-server"
python -m unittest -v test_store test_pairing test_blobs test_service
```

不把真实作业、配对码、设备令牌或服务器数据库放入仓库。已连接设备可生成短时有效的数字配对码。同步不替代独立备份；服务器保存的记录和附件不是端到端加密的。
