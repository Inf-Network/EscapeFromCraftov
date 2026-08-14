# EscapeFromCraftov 数据库表结构

EscapeFromCraftov 只支持 **PostgreSQL**，由 **Hibernate ORM 7** 按 `database.orm.schema-action`
（默认 `update`）在插件启动时自动建表。Lobby 与 Game 连接同一个库、同一个
schema（默认 `public`），共享下列全部表。

实体定义位于 `core/src/main/java/com/birditch/escapefromcraftov/core/database/entity/`，
本文档由实体注解整理而来，实体是唯一权威来源。

## 命名规则与表前缀

- 所有表名固定以 `craftov_` 开头，全小写下划线风格。
- `config.yml` 的 `database.table-prefix` 可在自动建表时给**所有表名与索引名**
  再统一加一个前缀（由 `TablePrefixNamingStrategy` 实现）。默认空字符串即不加。
  - 约束：以小写字母开头，仅含小写字母、数字、下划线，最长 20 字符。
  - 示例：`table-prefix: "ec_"` 时，`craftov_profiles` 建为 `ec_craftov_profiles`，
    索引 `idx_craftov_economy_player_time` 建为 `ec_idx_craftov_economy_player_time`。
  - Lobby 与 Game 必须配置相同前缀；已投产后修改前缀等于换一套全新表，
    旧表数据不会自动迁移。
- 类型按 Hibernate 7 对 PostgreSQL 的默认映射：`UUID → uuid`、
  `long → bigint`、`int → integer`、`boolean → boolean`、
  `String → varchar(255)`（有 `length` 时按声明长度）、
  `Instant → timestamp(6) with time zone`（JDBC 时区固定 UTC）、
  枚举按 `@Enumerated(STRING)` 存字符串。
- 带 `version` 列的表使用 Hibernate `@Version` 乐观锁；审计/流水表不可变，
  无 `version` 列。

## 表一览

| 表名 | 实体 | 用途 |
|---|---|---|
| `craftov_profiles` | `PlayerProfileEntity` | 玩家档案：身份、金币余额、等级与经验 |
| `craftov_player_settings` | `PlayerSettingEntity` | 玩家开关型偏好设置 |
| `craftov_player_cosmetics` | `PlayerCosmeticEntity` | 玩家当前装备的大厅装扮 |
| `craftov_player_items` | `PlayerItemEntity` | 玩家物品堆：仓库 / 出战栏 / 行动中 |
| `craftov_inventory_settlements` | `CraftovInventorySettlementEntity` | 行动结算幂等标记 |
| `craftov_economy_transactions` | `EconomyTransactionEntity` | 金币变动审计流水（不可变） |
| `craftov_progression_transactions` | `ProgressionTransactionEntity` | 经验发放审计流水（不可变） |
| `craftov_market_listings` | `MarketListingEntity` | 玩家交易行挂单与精确物品快照 |
| `craftov_market_operations` | `MarketOperationEntity` | 交易行全局幂等操作记录（不可变） |

## craftov_profiles — 玩家档案

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `player_id` | uuid | PK，不可更新 | 玩家 Minecraft UUID |
| `name` | varchar(255) | NOT NULL | 最近一次登录名 |
| `balance` | bigint | NOT NULL | 金币余额 |
| `level` | integer | NOT NULL | 等级 |
| `experience` | bigint | NOT NULL | 累计经验 |
| `created_at` | timestamptz(6) | NOT NULL，不可更新 | 建档时间 |
| `updated_at` | timestamptz(6) | NOT NULL | 最近更新时间 |
| `version` | bigint | | 乐观锁版本 |

## craftov_player_settings — 玩家设置

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `player_id` | uuid | 复合 PK | 玩家 UUID |
| `setting_key` | varchar(255) | 复合 PK | 设置键 |
| `enabled` | boolean | NOT NULL | 是否开启 |
| `updated_at` | timestamptz(6) | NOT NULL | 最近更新时间 |
| `version` | bigint | | 乐观锁版本 |

主键：`(player_id, setting_key)`（`@EmbeddedId PlayerSettingId`）。

## craftov_player_cosmetics — 玩家装扮

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `player_id` | uuid | PK | 玩家 UUID |
| `cosmetic_id` | varchar(255) | NOT NULL | 当前装备的装扮 ID |
| `updated_at` | timestamptz(6) | NOT NULL | 最近更新时间 |
| `version` | bigint | | 乐观锁版本 |

## craftov_player_items — 玩家物品

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `storage_id` | uuid | PK，不可更新 | 物品堆唯一 ID |
| `player_id` | uuid | NOT NULL，不可更新 | 归属玩家 |
| `instance_id` | uuid | 可空，唯一 | 跨仓库、行动与撤离保持不变的物理实例 ID；旧行首次读取时回退为 `storage_id` |
| `item_id` | varchar(255) | NOT NULL | 物品定义 ID |
| `quantity` | integer | NOT NULL | 数量 |
| `location` | varchar(255) | NOT NULL | 枚举 `InventoryLocation`：`WAREHOUSE` / `LOADOUT` / `CRAFTOV` / `MARKET` |
| `craftov_id` | varchar(255) | 可空 | 处于行动中时所属的 craftov 实例 ID |
| `item_data` | text | 可空 | Paper 版本化物品数据，保留耐久、附魔、Lore、药水与 PDC |
| `updated_at` | timestamptz(6) | NOT NULL | 最近更新时间 |
| `version` | bigint | | 乐观锁版本 |

索引：

- `idx_craftov_items_owner_location` on `(player_id, location)`
- `idx_craftov_items_owner_craftov` on `(player_id, craftov_id)`
- `uk_craftov_items_instance` UNIQUE on `(instance_id)`；PostgreSQL 允许多条旧行保持 `NULL`

## craftov_inventory_settlements — 行动结算幂等标记

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `settlement_id` | varchar(96) | PK，不可更新 | 结算幂等键 |
| `player_id` | uuid | NOT NULL，不可更新 | 玩家 UUID |
| `craftov_id` | varchar(255) | NOT NULL，不可更新 | craftov 实例 ID |
| `outcome` | varchar(255) | NOT NULL，不可更新 | 枚举 `CraftovInventoryOutcome`：`EXTRACTED` / `LOST` |
| `created_at` | timestamptz(6) | NOT NULL，不可更新 | 结算时间 |

索引：`idx_craftov_settlement_player` on `(player_id, created_at)`。

## craftov_economy_transactions — 金币流水（不可变审计）

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `transaction_id` | uuid | PK，不可更新 | 交易 ID（幂等防重） |
| `player_id` | uuid | NOT NULL，不可更新 | 玩家 UUID |
| `transaction_type` | varchar(255) | NOT NULL，不可更新 | 枚举 `TransactionType`：`LOOT_SELL` / `SHOP_BUY` / `SHOP_SELL` / `MARKET_BUY` / `MARKET_SELL` / `CRAFTOV_REWARD` 等 |
| `direction` | varchar(255) | NOT NULL，不可更新 | 枚举 `EconomyDirection`：`CREDIT` / `DEBIT` |
| `currency` | varchar(255) | NOT NULL，不可更新 | 枚举 `Currency`：`COINS`（金币） |
| `amount` | bigint | NOT NULL，不可更新 | 变动金额 |
| `balance_after` | bigint | NOT NULL，不可更新 | 变动后余额 |
| `description` | varchar(200) | NOT NULL，不可更新 | 变动说明 |
| `created_at` | timestamptz(6) | NOT NULL，不可更新 | 发生时间 |

索引：`idx_craftov_economy_player_time` on `(player_id, created_at)`。

## craftov_progression_transactions — 经验流水（不可变审计）

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `transaction_id` | uuid | PK，不可更新 | 交易 ID（幂等防重） |
| `player_id` | uuid | NOT NULL，不可更新 | 玩家 UUID |
| `awarded_experience` | bigint | NOT NULL，不可更新 | 本次发放经验 |
| `experience_after` | bigint | NOT NULL，不可更新 | 发放后累计经验 |
| `level_before` | integer | NOT NULL，不可更新 | 发放前等级 |
| `level_after` | integer | NOT NULL，不可更新 | 发放后等级 |
| `reason` | varchar(200) | NOT NULL，不可更新 | 发放原因 |
| `created_at` | timestamptz(6) | NOT NULL，不可更新 | 发生时间 |

索引：`idx_craftov_progression_player_time` on `(player_id, created_at)`。

## craftov_market_listings — 玩家交易行挂单

挂单期间原 `craftov_player_items` 行保留不变，仅把 `location` 设为 `MARKET`；
成交时原地修改 `player_id` 并回到 `WAREHOUSE`，所以 `storage_id`、`instance_id`
和 `item_data` 全程不重建。取消或到期也会把同一行退回卖家仓库。

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `listing_id` | uuid | PK | 挂单 ID |
| `create_operation_id` | uuid | NOT NULL | 上架幂等键；全局唯一性由操作表主键保证 |
| `seller_id` | uuid | NOT NULL | 卖家 UUID |
| `storage_id` | uuid | NOT NULL | 被锁定的物品仓储行；撤单后允许同一原物再次上架 |
| `instance_id` | uuid | NOT NULL | 物理物品实例 ID 快照 |
| `item_id` | varchar(255) | NOT NULL | 物品定义 ID 快照 |
| `quantity` | integer | NOT NULL | 整组数量 |
| `item_data` | text | 可空 | 完整 Paper 物品数据快照 |
| `price` | bigint | NOT NULL | 卖家定价，1..1,000,000,000 金币 |
| `status` | varchar(255) | NOT NULL | `ACTIVE` / `SOLD` / `CANCELLED` / `EXPIRED` |
| `buyer_id` | uuid | 可空 | 成交买家 |
| `close_operation_id` | uuid | 可空 | 购买或撤单幂等键 |
| `created_at` / `expires_at` | timestamptz(6) | NOT NULL | 创建与到期时间 |
| `closed_at` | timestamptz(6) | 可空 | 成交、撤单或过期时间 |
| `version` | bigint | | 乐观锁版本 |

索引：`idx_craftov_market_active_expiry`、`idx_craftov_market_seller_status`。

## craftov_market_operations — 交易行幂等操作

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `operation_id` | uuid | PK | 客户端操作幂等键 |
| `operation_type` | varchar(255) | NOT NULL | `LIST` / `BUY` / `CANCEL` |
| `player_id` | uuid | NOT NULL | 操作玩家 |
| `listing_id` | uuid | NOT NULL | 目标挂单 |
| `created_at` | timestamptz(6) | NOT NULL | 提交时间 |

索引：`idx_craftov_market_operation_listing` on `(listing_id)`。

## craftov_raid_results — 行动战报

每名玩家在每个行动实例中最多一条终局战报。`result_id` 由行动 ID 与玩家 UUID
确定性生成，死亡、退出、撤离和超时等并发终局路径共享同一幂等键。

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `result_id` | uuid | PK | 确定性战报 ID |
| `game_id` | varchar(96) | NOT NULL | 行动实例 ID |
| `player_id` | uuid | NOT NULL | 玩家 UUID |
| `map_id` | varchar(96) | NOT NULL | 地图 ID |
| `raid_mode` | varchar(24) | NOT NULL | `NORMAL` / `CONFIDENTIAL` |
| `difficulty` | varchar(24) | NOT NULL | `EASY` / `NORMAL` / `HARD` |
| `outcome` | varchar(24) | NOT NULL | `EXTRACTED` / `KILLED` / `QUIT` / `TIMED_OUT` / `FAILED` / `ABORTED` |
| `started_at` / `ended_at` | timestamptz(6) | NOT NULL | 实际行动起止时间 |
| `kills` | integer | NOT NULL | 玩家与地图 AI 的总击杀数 |
| `pve_kills` | integer | NOT NULL, DEFAULT 0 | 地图 AI 击杀数；必须在 `0..kills` 内，玩家击杀数为两者之差 |
| `extracted_item_count` | integer | NOT NULL | 最终保留物资数量 |
| `extracted_value` | bigint | NOT NULL | 最终保留物资基础价值 |
| `experience` | bigint | NOT NULL | 本局经验收益 |
| `earnings` | bigint | NOT NULL | 撤离收益价值 |

唯一约束：`uk_craftov_raid_result_game_player` on `(game_id, player_id)`；索引：
`idx_craftov_raid_result_player_time` on `(player_id, ended_at)`。

## 运维备注

- 表之间没有外键约束，实体互不关联；`player_id` 的一致性由业务层保证。
- 时间列统一 UTC 写入（`hibernate.jdbc.time_zone = UTC`）。
- 生产结构稳定后建议把 `database.orm.schema-action` 改为 `validate`，
  用受控迁移管理后续变更；`create` / `create-drop` 会破坏数据，严禁在生产使用。
- 旧库升级物品实例结构时，先执行
  `database/migrations/20260814_item_instance_payload.sql`。该脚本须由 psql
  以 autocommit 执行；使用 `table-prefix` 时先替换脚本中的表名与索引名。
- 启用玩家交易行前执行 `database/migrations/20260814_player_market.sql`；脚本使用
  普通事务，并会把 `craftov_player_items.location` 的枚举约束扩展为允许 `MARKET`；
  执行成功后再以 `schema-action: validate` 启动 Lobby。
- 启用行动战报前执行 `database/migrations/20260814_raid_results.sql`；Lobby 与 Game
  共用该表，需在任一端升级启动前完成迁移。
- 已有行动战报库升级 AI 击杀统计时，再执行
  `database/migrations/20260814_raid_results_pve_kills.sql`；脚本会把旧记录回填为 0，
  并添加 `pve_kills <= kills` 约束。
- 运行时开启 Hibernate 的 named-index validation；`validate` 模式会在唯一实例
  索引缺失时拒绝启动，不能只添加两列而漏掉索引。
- `craftov_player_items` 上的仓库/出战栏写路径使用 `pg_advisory_xact_lock`
  事务级咨询锁做玩家级串行化，不依赖表结构。
