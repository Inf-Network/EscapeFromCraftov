# RaidCore 数据库表结构

RaidCore 只支持 **PostgreSQL**，由 **Hibernate ORM 7** 按 `database.orm.schema-action`
（默认 `update`）在插件启动时自动建表。Lobby 与 Game 连接同一个库、同一个
schema（默认 `public`），共享下列全部表。

实体定义位于 `core/src/main/java/com/birditch/raidcore/core/database/entity/`，
本文档由实体注解整理而来，实体是唯一权威来源。

## 命名规则与表前缀

- 所有表名固定以 `raid_` 开头，全小写下划线风格。
- `config.yml` 的 `database.table-prefix` 可在自动建表时给**所有表名与索引名**
  再统一加一个前缀（由 `TablePrefixNamingStrategy` 实现）。默认空字符串即不加。
  - 约束：以小写字母开头，仅含小写字母、数字、下划线，最长 20 字符。
  - 示例：`table-prefix: "ec_"` 时，`raid_profiles` 建为 `ec_raid_profiles`，
    索引 `idx_raid_economy_player_time` 建为 `ec_idx_raid_economy_player_time`。
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
| `raid_profiles` | `PlayerProfileEntity` | 玩家档案：身份、金币余额、等级与经验 |
| `raid_player_settings` | `PlayerSettingEntity` | 玩家开关型偏好设置 |
| `raid_player_cosmetics` | `PlayerCosmeticEntity` | 玩家当前装备的大厅装扮 |
| `raid_player_items` | `PlayerItemEntity` | 玩家物品堆：仓库 / 出战栏 / 行动中 |
| `raid_inventory_settlements` | `RaidInventorySettlementEntity` | 行动结算幂等标记 |
| `raid_economy_transactions` | `EconomyTransactionEntity` | 金币变动审计流水（不可变） |
| `raid_progression_transactions` | `ProgressionTransactionEntity` | 经验发放审计流水（不可变） |

## raid_profiles — 玩家档案

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

## raid_player_settings — 玩家设置

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `player_id` | uuid | 复合 PK | 玩家 UUID |
| `setting_key` | varchar(255) | 复合 PK | 设置键 |
| `enabled` | boolean | NOT NULL | 是否开启 |
| `updated_at` | timestamptz(6) | NOT NULL | 最近更新时间 |
| `version` | bigint | | 乐观锁版本 |

主键：`(player_id, setting_key)`（`@EmbeddedId PlayerSettingId`）。

## raid_player_cosmetics — 玩家装扮

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `player_id` | uuid | PK | 玩家 UUID |
| `cosmetic_id` | varchar(255) | NOT NULL | 当前装备的装扮 ID |
| `updated_at` | timestamptz(6) | NOT NULL | 最近更新时间 |
| `version` | bigint | | 乐观锁版本 |

## raid_player_items — 玩家物品

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `storage_id` | uuid | PK，不可更新 | 物品堆唯一 ID |
| `player_id` | uuid | NOT NULL，不可更新 | 归属玩家 |
| `item_id` | varchar(255) | NOT NULL | 物品定义 ID |
| `quantity` | integer | NOT NULL | 数量 |
| `location` | varchar(255) | NOT NULL | 枚举 `InventoryLocation`：`WAREHOUSE` / `LOADOUT` / `RAID` |
| `raid_id` | varchar(255) | 可空 | 处于行动中时所属的 raid 实例 ID |
| `updated_at` | timestamptz(6) | NOT NULL | 最近更新时间 |
| `version` | bigint | | 乐观锁版本 |

索引：

- `idx_raid_items_owner_location` on `(player_id, location)`
- `idx_raid_items_owner_raid` on `(player_id, raid_id)`

## raid_inventory_settlements — 行动结算幂等标记

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `settlement_id` | varchar(96) | PK，不可更新 | 结算幂等键 |
| `player_id` | uuid | NOT NULL，不可更新 | 玩家 UUID |
| `raid_id` | varchar(255) | NOT NULL，不可更新 | raid 实例 ID |
| `outcome` | varchar(255) | NOT NULL，不可更新 | 枚举 `RaidInventoryOutcome`：`EXTRACTED` / `LOST` |
| `created_at` | timestamptz(6) | NOT NULL，不可更新 | 结算时间 |

索引：`idx_raid_settlement_player` on `(player_id, created_at)`。

## raid_economy_transactions — 金币流水（不可变审计）

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `transaction_id` | uuid | PK，不可更新 | 交易 ID（幂等防重） |
| `player_id` | uuid | NOT NULL，不可更新 | 玩家 UUID |
| `transaction_type` | varchar(255) | NOT NULL，不可更新 | 枚举 `TransactionType`：`LOOT_SELL` / `SHOP_BUY` / `SHOP_SELL` / `MARKET_BUY` / `MARKET_SELL` / `RAID_REWARD` 等 |
| `direction` | varchar(255) | NOT NULL，不可更新 | 枚举 `EconomyDirection`：`CREDIT` / `DEBIT` |
| `currency` | varchar(255) | NOT NULL，不可更新 | 枚举 `Currency`：`COINS`（金币） |
| `amount` | bigint | NOT NULL，不可更新 | 变动金额 |
| `balance_after` | bigint | NOT NULL，不可更新 | 变动后余额 |
| `description` | varchar(200) | NOT NULL，不可更新 | 变动说明 |
| `created_at` | timestamptz(6) | NOT NULL，不可更新 | 发生时间 |

索引：`idx_raid_economy_player_time` on `(player_id, created_at)`。

## raid_progression_transactions — 经验流水（不可变审计）

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

索引：`idx_raid_progression_player_time` on `(player_id, created_at)`。

## 运维备注

- 表之间没有外键约束，实体互不关联；`player_id` 的一致性由业务层保证。
- 时间列统一 UTC 写入（`hibernate.jdbc.time_zone = UTC`）。
- 生产结构稳定后建议把 `database.orm.schema-action` 改为 `validate`，
  用受控迁移管理后续变更；`create` / `create-drop` 会破坏数据，严禁在生产使用。
- `raid_player_items` 上的仓库/出战栏写路径使用 `pg_advisory_xact_lock`
  事务级咨询锁做玩家级串行化，不依赖表结构。
