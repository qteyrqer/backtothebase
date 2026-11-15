# backtothebase

让机器人「回基地」的 xinbot 插件：白名单玩家在游戏里私聊 `back [基地编号]`，机器人通过
[MovementSync](https://github.com/huangdihd/MovementSync) 走到该玩家配置的基地活板门坐标，并像玩家一样右键活板门（开/关由服务器切换）。

- 依赖：xinbot ≥ 2.4.3、MovementSync ≥ 1.12.0（`plugin.yml` 已声明 `depend: [MovementSync]`）
- 私聊事件由服务器对应的 MetaPlugin 解析后触发 `PrivateChatEvent`（如 `3c3uMetaPlugin`），本插件只消费该事件，不解析原始聊天包
- 控制台管理白名单、机器人账号；配置持久化到 `<插件目录>/backtothebase.json`

## 1. 安装

1. `./build.sh` 生成 `build/backtothebase-1.0.0.jar`
   （本工作区已把构建好的 jar 复制到 `src/plugin/backtothebase-1.0.0.jar`）
2. 把 jar 放进 xinbot 的 `plugin/` 目录
3. 启动 xinbot，确认日志中出现 `backtothebase enabled`

也可以用 Maven（需要能访问 JitPack）：

```bash
mvn -q package
```

## 2. 控制台命令

命令别名：`backtothebase`、`bttb`、`back`。

| 命令 | 说明 |
| --- | --- |
| `bttb account <账号>` | 设置机器人账号（默认自动读取 xinbot 配置里的账号） |
| `bttb add <玩家> <x> <y> <z> [基地编号]` | 添加/更新某玩家某基地的活板门坐标，基地编号默认 1 |
| `bttb remove <玩家> [基地编号]` | 删除某基地；省略编号则删除该玩家所有基地 |
| `bttb list [玩家]` | 查看白名单 |
| `bttb test <玩家> [基地编号]` | 在控制台模拟一次返回请求（调试用） |
| `bttb status` | 查看当前请求进度 |
| `bttb cancel` | 取消当前请求 |
| `bttb reload` | 重新读取 `backtothebase.json` |

示例：

```text
bttb account YourBot
bttb add Steve 100 64 200 1
bttb add Steve 120 70 -30 2
bttb add Alex 88 65 12 1
bttb list
```

## 3. 游戏内使用

白名单玩家私聊机器人（把 `<机器人>` 换成 xinbot 登录的账号名）：

```text
/w <机器人> back        # 基地 1
/w <机器人> back 2      # 基地 2
```

流程：

1. 校验白名单和基地编号；
2. 回复 `收到返回请求，处理中...`；
3. 寻路到该玩家配置的基地活板门坐标（优先站到活板门上方，站不上则找附近地面）；
4. 到达后回复 `已到达基地附近，正在拉下活板门...`，注视并右键活板门（不判断当前开关状态）；
5. 成功回复 `已返回基地并处理活板门。`，失败回复 `返回失败：<原因>`。

同一时间只处理一个请求，期间其他请求会收到「当前正在处理其他返回请求」。

## 4. 配置文件

路径：`plugin/backtothebase.json`（首次启动自动生成）。

```json
{
  "account": "YourBot",
  "requestTimeoutSeconds": 300,
  "pathfindExpansions": 5000,
  "whitelist": {
    "Steve": {
      "1": { "x": 100, "y": 64, "z": 200 },
      "2": { "x": 120, "y": 70, "z": -30 }
    }
  }
}
```

| 字段 | 说明 |
| --- | --- |
| `account` | 机器人账号，用于忽略机器人自己的私聊；`bttb account` 会写回这里 |
| `requestTimeoutSeconds` | 单个请求超时（30–3600 秒），超时会取消导航并私聊通知玩家 |
| `pathfindExpansions` | D* Lite 最大扩展节点数（500–50000），越大越能处理复杂地形、也越慢 |
| `whitelist` | 玩家名 → 基地编号 → 活板门方块坐标 |

## 5. 行为细节

- **寻路**：`DStarLite` + 无放置策略的 `PathfindingContextBuilder`，即只走路/跳跃/下落/跨沟，不会搭桥或挖方块；
  规划在后台线程完成，路径交给 `MovementSync` 的 `MovementController` 执行。
- **活板门**：就是游戏里的右键——注视活板门后发 `ServerboundUseItemOnPacket`，让服务器自己切换开/关，
  不读取也不判断当前状态；若配置坐标已不是活板门，会在水平 4 格、垂直 ±3 格内寻找最近的活板门。
  交互前会切到空手（热栏没有空位则用空副手），避免“手里拿着方块右键变成放方块”——那会出现挥手动画
  但活板门不动；点击的面和光标位置按活板门的实际碰撞箱做射线计算，减少被服务端/反作弊拒绝的情况。
- **回执**：通过 `/w <玩家> <文本>` 发送，走 xinbot 自带聊天队列（约 3 秒一条，多条回执会依次发出）。
- **目标选择**：目标是白名单里配置的基地活板门，不是玩家实体，所以不会跟着玩家跑；活板门所在区块
  未加载时按活板门上方乐观寻路，区块加载后由 `PathMovement` 继续重规划。
- **失败处理**：无路线、目标区块未加载、活板门太远、超时都会私聊失败原因并取消导航。

## 6. 开发

```bash
./build.sh --test     # 编译 + 打包 + 运行解析/配置/命令/真实插件加载测试
```

源码结构：

```text
src/main/java/xin/bbtt/backtothebase/
├── BackToTheBasePlugin.java       生命周期、配置、语言
├── BackCommandExecutor.java       控制台命令
├── PrivateChatListener.java       PrivateChatEvent → ReturnService
├── ReturnService.java             请求编排、寻路、超时、回执
├── ChatNotifier.java              /w 回执
├── BackRequest.java               "back [n]" 解析
├── config/                        配置模型与持久化
├── movement/PullTrapdoorMovement.java  到达后的注视 + 右键活板门
└── util/                          玩家坐标、活板门查找
```
