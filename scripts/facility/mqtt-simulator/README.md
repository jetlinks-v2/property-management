# 本地设施设备 MQTT 上报

用 MQTT 模拟水、电、气表，实际经过 Cloud 协议解析和数据存储，再由设施服务采集。
启动时上报一次，之后每小时上报累计量、周期增量、余额和状态；电表还上报功率。
故障状态每30秒检查，仅在异常触发或恢复时额外上报状态，不重复上报用量增量。
累计读数在重启后继续，支持平台读取属性指令，不修改业务数据库生成读数。

依赖本地 Docker、已启用的 MQTT 直连网关与设备。模拟器与平台容器使用同一个 Docker 网络，
不占用宿主机 MQTT 端口，也不需要重启平台。

当前本地接入使用 `fe_local_mqtt_network` / `fe_local_mqtt_gateway`，监听容器内1884。
网络组件指定节点 `all-in-one-service:8200`，模拟器通过 Docker 网络访问该节点。
宿主机1883是平台消息通道，不能作为这里的设备上报端口。

设施端读取历史还需要 Cloud 装配 `property-facility-energy-core` 和
`property-facility-energy-cloud-support`，详见 `deploy/facility-energy/README.md`。
本次已补入本地容器的 `BOOT-INF/lib`；重建或更换该容器时需将这两个制品重新装配进镜像。
10台设备已开启能源采集，配置累计量、增量、余额、状态映射，电表另映射功率。

本地配置放在 Git 忽略的 `.agent-runtime/facility-mqtt/config.json`，结构为：

```json
{
  "host": "jetlinks-all-in-one-service",
  "port": 1884,
  "devices": [{
    "id": "设备ID",
    "productId": "产品ID",
    "energyType": "electricity",
    "secureId": "本地设备的认证ID",
    "secureKey": "本地设备的认证密钥",
    "initialTotal": 1000,
    "initialBalance": 200
  }]
}
```

设备ID末段以 `_数字` 结尾；`energyType` 支持 `water`、`electricity`、`gas`。
首次运行使用初始读数，后续使用 `state/readings.json`；保留该文件避免累计量回退。
配置中的密钥只用于本地设备，不提交 Git，不使用线上密钥。

演示设备需要随机故障时，可在专用配置中显式添加：

```json
"faultSimulation": {
  "enabled": true,
  "normalSeconds": [3600, 7200],
  "faultSeconds": [540, 660]
}
```

默认不开启。启用后每次只有一台配置内设备上报 `fault=1`，持续指定时间后恢复为0；
默认两次故障开始时间相隔1～2小时、故障持续9～11分钟；首次启动同样随机等待1～2小时，
可用 `firstDelaySeconds` 单独指定首次等待。故障变化以30秒为粒度检查并单独上报，不受每小时读数周期影响。
MQTT保持在线，累计读数继续递增。故障和等待时间保存在 `state/faults.json`，配置不变时重启继续当前周期。
修改间隔或持续时间后，旧故障周期会恢复正常并重新随机等待，不会沿用之前几分钟的短周期；累计读数不受影响。
SaaS 告警中心需配置 `fault > 0` 触发、恢复正常自动解除的规则；
设施设备接收应用绑定设备的 SaaS 告警，模拟器不直接生成告警或通知。

```powershell
./scripts/facility/mqtt-simulator/Start.ps1
docker logs --tail 20 property-facility-mqtt-simulator
./scripts/facility/mqtt-simulator/Stop.ps1
python -m unittest discover -s scripts/facility/mqtt-simulator -p "test_*.py"
```

有限轮次验证可对容器追加 `--rounds 3 --interval 3 --only 设备ID` 参数。
发布收到 MQTT PUBACK 只代表传输成功，验收还需核对 Cloud 属性历史与设施能源读数。
