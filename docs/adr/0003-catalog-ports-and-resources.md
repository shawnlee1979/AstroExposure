# ADR-0003: 目录数据经端口接入、以 :data classpath 资源分发、JSON 自写小解析器

- 日期：2026-10-26
- 状态：Accepted

## 背景

二期引入四类静态目录数据（深空目标、日月食、流星雨、内置镜头库）与光害网格。它们由构建期数据管线从公开来源（OpenNGC、NASA GSFC 食目录、IMO 流星雨年表、Falchi 图谱）生成，体积从百 KB 到数十 MB 不等，随版本更新替换。若业务代码直接读文件或直接依赖某份数据的字段形态，管线产物一变就会牵动领域层；同时，运行时解析 JSON 与二进制网格需要一个解析实现，为四份小文档与一份网格引入新的第三方运行时依赖，与 8.1「核心依赖只有一个」的收敛口径不符。

## 决定

- **端口化**：目录数据一律经领域侧端口消费（`ObjectCatalog`、`EclipseCatalog`、`MeteorShowerCatalog`、`LensCatalog`、`LightPollutionSource`，见 `domain/catalog/CatalogPorts.kt`），端口只用领域类型，带 `dataVersion`；实现全部落在 :data 的 `catalog` 包，DTO 转换不穿透适配器（DEVELOPMENT-RULES B 节）。
- **分发形态**：数据产品以 :data 的 classpath 资源分发（`catalog/objects.json`、`catalog/eclipses.json`、`catalog/showers.json`、`catalog/lenses.json`、`catalog/lp-grid.bin`），运行时只读、无联网、无抓取。JSON 文档带 `schemaVersion` 与 `dataVersion`；光害网格用自描述二进制格式（魔数 `AEGL` + 大端头长度 + JSON 头 + uint16 小端数据区，值 × scale = mcd/m²，0xFFFF 为无数据，多格网细者优先）。
- **解析器自写**：JSON 由 :data 内自写的小解析器 `JsonMini` 解析（完整 JSON 数据模型、严格语法校验、错误带字符位置），不新增第三方依赖；非法文档、缺失资源、未知枚举值一律显式抛错，禁止静默返回空目录。光害查不到覆盖时端口返回 null，调用方走设计 2.8 的显式降级。

## 后果

- 管线产物可整体替换：换数据版本只动资源文件与 `dataVersion`，领域与用例代码零改动；同一套与实现无关的契约测试对夹具与真实产物复用。
- 自写解析器的维护成本归本项目：其语法覆盖面以本项目文档 schema 为限（不支持流式解析），schema 演进时解析器与校验须同步补测试；若将来文档规模或复杂度显著增长（如需要流式读取大星表），须以新的 ADR 重新评估引入成熟解析库。
- 二进制网格格式成为自有格式：生成端（管线脚本）与读取端（适配器）必须同版本演进，格式变更走 `schemaVersion` 显式升级并留旧版本样本测试。
