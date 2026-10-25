# 群体数据统计服务 · 部署与使用说明

目标:接收多台手机的匿名电池采样,算出**每个机型的群体健康度曲线**和**同机型到达 80% 的循环次数分布**,并给 App 提供"官方循环寿命表"在线核对数据。

服务是单文件 `battery_stats_server.py`,只用 Python 标准库,**不需要 pip 安装任何东西**。

---

## 一、你要搭什么(三选一)

| 方案 | 成本 | 适合 | 备注 |
|---|---|---|---|
| **国内轻量云服务器**(腾讯云/阿里云,2 核 2G 起步) | ~¥50-100/年活动价 | 正式对外用 | 用域名必须 **ICP 备案**(约 2-3 周);用 `http://IP:端口` 可不备案但 App 走明文 HTTP,仅测试可接受 |
| **海外 VPS**(香港/新加坡轻量) | ~$5/月 | 免备案、快速上线 | 延迟稍高;App 明文 HTTP 会有"是否允许明文流量"的系统提示,建议配 HTTPS |
| **家里旧电脑 / 树莓派 + 内网穿透**(如 Tailscale/frp) | ¥0 | 只给自己和熟人用 | 最简单,Tailscale 组网后 App 直接填内网 IP |

> App 的 AndroidManifest 已允许明文 HTTP(`usesCleartextTraffic="true"`),所以 HTTP 也能用;
> 但只要域名 + 备案/海外机可行,**建议上 HTTPS**(用 Caddy 一行配置自动办证)。

## 二、部署(以 Linux 为例)

```bash
# 1. 把 BatteryHealthApp/server/ 目录传到服务器
# 2. 启动(先手动跑一下看看)
python3 battery_stats_server.py
# 看到 listening on 0.0.0.0:8300 即成功,Ctrl+C 停掉

# 3. 常驻运行(二选一)
#    a) systemd:
sudo tee /etc/systemd/system/battery-stats.service <<'EOF'
[Unit]
Description=battery stats
After=network.target
[Service]
WorkingDirectory=/opt/battery-stats
ExecStart=/usr/bin/python3 /opt/battery-stats/battery_stats_server.py
Environment=API_KEY=换成一串随机字符
Restart=always
[Install]
WantedBy=multi-user.target
EOF
sudo systemctl enable --now battery-stats

#    b) 或最粗暴:nohup python3 battery_stats_server.py >/dev/null 2>&1 &

# 4. 有域名的话,HTTPS 用 Caddy(自动申请证书):
#    caddy reverse-proxy --from stats.你的域名.com --to localhost:8300
```

防火墙/安全组放行 8300 端口(或 443 走反代)。

## 三、App 怎么连(现在就能用的部分)

App v4.2 的「详细页 → 到达 80% 推断 → 基准地址」填:

```
http://服务器IP:8300/api/baseline        (HTTP)
https://stats.你的域名.com/api/baseline   (HTTPS,推荐)
```

点「立即同步」就会拉到:群体基准曲线(points)+ 官方循环寿命表(claims)。
**App 只从这一个地址下载数据,不上传任何东西**——上传是下一步的事(见五)。

## 四、接口一览(给网页/脚本用)

```bash
curl http://localhost:8300/                      # 健康检查
curl http://localhost:8300/api/baseline          # 曲线 + 官方寿命表
curl -X POST http://localhost:8300/api/reports \
  -H "Content-Type: application/json" -H "X-API-Key: 你的密钥" \
  -d '{"device_id":"我自己起的随机串abc123","model":"24129pn74c","market":"Xiaomi 15",
       "ts":1760000000000,"cycle":303,"health":87.5,
       "actual_mah":4725,"design_mah":5400,"level":100,"temp_c":39.3,"source":"bugreport"}'
curl http://localhost:8300/api/stats             # 全部机型概览
curl http://localhost:8300/api/stats/24129pn74c  # 单机型群体曲线 + 到80%循环分布
```

统计口径:
- **同机型到 80% 的循环数**:每台设备用自己的多点(循环,健康度)做线性拟合外推,
  只收"至少 2 份报告、循环跨度 ≥10、确实在下降"的设备;最后给中位数和 P25/P75。
  设备少于 3 台时这个数字没有群体意义,别当结论用。
- **群体曲线**:按循环数每 100 次分桶取健康度中位数;总样本 <200 时退回内置典型值。
- `claims.json`(与脚本同目录,改完即生效,不用重启)可以增补新机型的官方寿命,
  格式:`[["正则", 次数, "来源"], ...]`,正则匹配的是机型名/model 码。

## 五、隐私与合规(部署前必读)

1. **device_id 必须是用户自造的随机字符串**,不是 IMEI/序列号——服务端对疑似设备标识
   字段(imei/serial/meid/mac/phone/imsi)直接拒收整条记录;
2. 数据库不记录上传者 IP;
3. App 端的上传功能(现在还没有)会做成**默认关闭、明确opt-in**的开关,并在声明页说明;
4. 你是服务端的管理者,对收集的数据负合规责任,建议在页面/声明里写清用途。

## 六、搭好之后给我什么

把下面两样发我,我就在 App 里接上传/群体对比的客户端(默认关,用户自己开):

1. `https://你的地址/api/baseline` 这个 URL
2. API_KEY(如果设了)
