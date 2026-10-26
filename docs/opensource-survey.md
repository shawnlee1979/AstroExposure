# 开源生态调研：可复用的框架、库与数据集

> 调研日期：2026-10-26。所有 star 数、提交/发布活跃度、许可证均于当日实际打开仓库页核对；未能核实的项明确标注「未核实」，不以印象填充。
> 本 App 前提：Kotlin Android 原生、全离线、自用侧载（用自有 keystore 签名，仅在自己设备上安装）。许可证义务按「自用」评估，但如实记录——将来若改变分发方式，结论需要重审。

---

## 总览结论

| 技术层 | 结论 |
|---|---|
| ① 天文历算 | **直接用 Astronomy Engine（Kotlin 版）**，这一层基本不用自研 |
| ② 目标数据库 | **用数据不用代码**：OpenNGC（深空）+ HYG（恒星）现成取数；流星雨、日月食按权威源自建静态表 |
| ③ 光害数据 | **用 Falchi 世界图谱数据自行重采样**，没有现成的「下载即用」离线网格包 |
| ④ 规划/曝光逻辑 | **仍需自研**，但有可借鉴的模型选型与评分方法（DarkHours、KStars 等），NPF 等公式本身是公开的 |

---

## ① 天文历算层

### 推荐：cosinekitty/astronomy（Astronomy Engine）

- 仓库：https://github.com/cosinekitty/astronomy
- 核实数据（2026-10-26 仓库页）：**1,073 stars**、142 forks、2,242 次提交、17 个 Release；许可证 **MIT**；commits 页最近提交为 2025-01-27，仓库徽章所示最新版本 v2.19（具体发布日期未核实）。作者在 README 中明确承诺长期维护。更新节奏近两年放缓，但项目已高度成熟、算法冻结式稳定，对本 App 这种「装上就不想动」的场景反而是优点。
- **确有官方 Kotlin/JVM 版**：源码在仓库 `source/kotlin`（单文件 `astronomy.kt`，包名 `io.github.cosinekitty.astronomy`），经 **JitPack** 分发（仓库根目录有 `jitpack.yml`，README 有 JitPack 徽章），Gradle 一行依赖即可；也可以把单个 Kotlin 文件直接拷进工程——零外部依赖，这是它刻意的设计目标。
- 功能覆盖本 App 历算需求的几乎全部：
  - 太阳/月亮/行星位置（赤道、黄道、地平、银道坐标互转，可选大气折射修正）；
  - 日月升落与中天时刻（含极地特殊情形处理）；
  - **民用/航海/天文暮光**起止时刻；
  - **月相**：朔、上弦、望、下弦及任意黄经差对应的时刻，月球照明相关量（月亮视星等、月地距离、近地点/远地点）；
  - **日月食搜索**：`SearchSolarEclipse` / `SearchLunarEclipse`（及按年搜索）可给出下一次食的时刻、类型（全/环/偏/混合、月全食/月偏食/半影）与食甚量级；
  - **星座判定**：`Constellation` 函数由坐标直接给出所属星座——省掉一整套星座边界数据；
  - `DefineStar` 可把任意 RA/Dec 定义为「自定义天体」，再用同一套 `Equator`（转地平高度角/方位角）与升落搜索函数处理深空目标——目标高度曲线、银心窗口都靠它算，不必另写球面三角。
- 精度：全库以 ±1 角分为设计目标，与 NOVAS、JPL Horizons 对照做了严格单元测试；对曝光规划（分钟级时刻、角分级高度）绰绰有余。
- **缺口（必须知道）**：它的日食是「事件级」预报——何时发生、什么类型、全球最大食分；**给不出某一地点的接触时刻（C1–C4）、本地食分与全食持续时间**。后者需要贝塞尔元素另行计算（见 ② 日月食目录）。设计里日食模块若只做「近期食事件提醒 + 分阶段曝光表」，Engine 已足够；若要做「我家几点几分开始全食」，需自研贝塞尔元素计算或查表，这是本层唯一留给自研的硬骨头。
- 风险：Kotlin 版由代码生成器从同一模板生成（与 C/C#/JS/Python 结果一致性有测试保障）；JitPack 构建偶有不稳定，稳妥做法是把单文件源码纳入工程并注明版本。

### 备选：shred/commons-suncalc

- 仓库：https://github.com/shred/commons-suncalc （主开发已迁至 Codeberg，GitHub 为镜像）
- 核实数据：**159 stars**、29 个 Release；许可证 **Apache-2.0**；Maven Central 分发，Android 需 API 26+，无其他依赖。
- 只有六件套：SunTimes、MoonTimes、MoonPhase、SunPosition、MoonPosition、MoonIllumination。作者自述精度约 1 分钟量级，并明说「probably not sufficient for astronomical purposes」。
- 不作主引擎的原因：**没有日月食、没有任意目标（RA/Dec）的高度角**、月相只给关键节点。适合做「只要日出日落月相」的极简场景，或作为 Engine 结果的交叉验证源。

### 对照（不选）：JS 与 Rust 路线

- **mourner/suncalc**（JS）：3,335 stars、BSD-2-Clause、2025-08 仍有提交（数据经 GitHub API 转引核实）。功能只有日月位置/时刻/月相照明，精度低于 commons-suncalc，且在 Android 里用它意味着嵌入 JS 引擎或转译——Kotlin 已有原生更强选项，跨语言无收益。
- **Rust**：`astro` crate 年久失修（lib.rs 页面显示多年未更新）；`siderust` 功能全面（VSOP87/ELP2000、高度角 API）但项目新、抽象重；`solunatus` 等小众。共同問題是 Android 集成要走 NDK/JNI 桥接，自研胶水成本高于 Astronomy Engine 直接依赖。仅作对照，不采用。

### 本层结论

**直接用 Astronomy Engine（Kotlin 版，MIT）。** 设计文档中历算模块的自研部分（太阳/月亮位置、升落、暮光、月相、目标高度角、银心窗口、星座判定）可整体删除，改为薄封装；仅保留「日食本地接触时刻」为待决策的自研项。

---

## ② 目标数据库层

### 深空：OpenNGC（mattiaverga）

- 仓库：https://github.com/mattiaverga/OpenNGC
- 核实数据：**148 stars**、290 次提交、20 个 Release；commits 页最近提交 **2026-10-18**，仍在活跃修订；许可证 **CC BY-SA 4.0**（数据）。
- 字段（NGC_guide.txt 与 README 核实）：名称（NGC/IC 编号）、类型、RA/Dec（J2000）、所属星座、长轴/短轴（角分）、位置角、B/V/J/H/K 星等、**SurfBr 表面亮度（仅星系，B 波段 25 mag 等照度面内平均，mag/arcsec²）**、星系 Hubble 类型、行星状星云中心星星等、**Messier 交叉编号**、其他星表标识符与来源标注列。另有 Addendum（非 NGC/IC 的著名天体，含 M40、M45 及部分矮星系等）与主要星云的轮廓数据。
- 集成方式：**仅取数据**。构建期把 CSV 按设计需要裁剪（Messier 全表 + 精选亮 NGC/IC + Addendum），转成 SQLite 或精简二进制打包进 APK；全表约 1.4 万条，裁剪后体积在百 KB 量级。
- 注意点：
  - 表面亮度只覆盖星系；星云/星团类目标需要按「总星等 + 角尺寸」自行估算平均表面亮度（评分引擎里注明为估算值）。
  - CC BY-SA 4.0 义务：署名 OpenNGC/Mattia Verga；若对外分发 App 或衍生数据集，衍生数据须以相同许可开放。自用侧载无现实障碍，但 App 内应保留数据来源页。
  - 上游数据源（NED、HyperLEDA、SIMBAD、HEASARC）的致谢链建议一并保留在来源页里。

### 恒星：HYG Database（astronexus）

- 现行仓库：https://codeberg.org/astronexus/hyg （GitHub 旧仓 https://github.com/astronexus/HYG-Database 已归档存档，789 stars，README 明确指向 Codeberg 为唯一更新地）
- 核实数据：当前版本 **v4.4，共 119,614 颗星**；许可证 **CC BY-SA 4.0**。由 Hipparcos、Yale Bright Star、Gliese 三表合并，字段含 RA/Dec、视星等、光谱型、颜色指数、专名、Bayer/Flamsteed 编号、距离、自行等。
- 集成方式：**仅取数据，且只取亮星子集**。本 App 的恒星需求只是「亮星参照/星座骨架/校准星候选」，按视星等截断到约 6.5 等即约 9 千颗（同类项目 Celeris 即以 HYG v4.1 截断得 8,920 颗并 gzip 打包，路线已被验证）。全量 12 万颗没有必要进包。
- 同为 CC BY-SA 4.0：署名 Astronexus/David Nash，衍生子集若分发须同许可。

### 星座

- 88 星座的基本表（中英文名、缩写、中心坐标、面积、主要亮星）数据量极小，自建即可。
- 「某坐标属于哪个星座」直接用 Astronomy Engine 的 `Constellation` 函数，不需要星座边界数据，省一整块。
- 若将来做星图连线：星座 stick figures 有现成数据集（如 dcf21/constellation-stick-figures，CC BY 4.0——经第三方页面转引，本次未逐项打开其仓库复核，引用前需再核）；Stellarium 的天文文化数据许可混杂（GPL/CC BY-SA），不要整包取用。

### 流星雨

- **结论：没有核实到成熟、可直接取用的 GitHub 数据仓库。** 多轮检索未见有维护中的 IMO 数据镜像仓；声称包含流星雨数据的多为观测软件或一次性脚本。
- 权威源在 GitHub 之外：**IMO（国际流星组织）Shower Calendar / Working List of Visual Meteor Showers** 与 **IAU Meteor Data Center**（ta3.sk）。字段口径可参考 Stellarium 的 MeteorShowers 插件数据文件 `showers.json`（位于 Stellarium 仓库内，GPL-2.0 项目）：三字母 IAU 代码、IAU 编号、活动期与峰值（以太阳黄经表示起止/峰值）、ZHR、辐射点 RA/Dec 及逐日漂移、地心速度、母体天体、数量指数——字段设计完整，值得照搬结构；其数值本身源自 IMO 工作列表。
- 落地做法：构建期从 IMO 当年日历整理一张静态表打进 App。v1 只需主要流星雨约 20–30 个（象限仪、宝瓶座 η、英仙、猎户、狮子、双子等），字段照上面结构；全量已确立流星雨约 110 个可后续补。流星雨的峰值年际变化小，以太阳黄经存峰值比存公历日期更耐久（Engine 可由黄经反推当年日期）。数据为事实性天文数据，使用时在来源页标注 IMO/IAU MDC 出处。

### 日月食目录

- **结论：没有核实到可信的、维护中的 NASA 历表 GitHub 整理版仓库**（检索到的多为个人抓取脚本或失效链接，不作为依赖）。
- 权威源：**NASA GSFC Eclipse Web Site（Espenak & Meeus《Five Millennium Canon/Catalog》）**，https://eclipse.gsfc.nasa.gov 。官方直接提供机器可读文件：日食目录 ASCII 文本（全量约 1.3MB）、**日食贝塞尔元素 CSV**、月食目录同理。Espenak 的使用条款为「可自由转载，须附致谢 Eclipse Predictions by Fred Espenak and Jean Meeus (NASA's GSFC)」——美国政府作品，无版权障碍，署名即可。
- 落地做法（两条腿）：
  1. **事件发现**用 Astronomy Engine 的食搜索（运行时算，无数据包袱），并与 NASA 目录交叉验证；
  2. **本地食况**（某地是否可见、食分、接触时刻）从 NASA 目录/贝塞尔元素取 2026–2035 年子集自建小表；若要精确到分钟的本地接触时刻，再在贝塞尔元素之上自研本地计算，并注意 ΔT（地球自转改正）的不确定性会给日食时刻带来分钟级误差，产品文案需如实标注。

### 本层结论

**用数据不用代码**：OpenNGC + HYG 子集直接取数（同为 CC BY-SA 4.0，义务仅署名与衍生同许可）；流星雨、日月食按 IMO/NASA 权威源自建静态表，工作量在「整理与校验」，不在「造数据」。

---

## ③ 光害数据层

### Falchi 等《The New World Atlas of Artificial Night Sky Brightness》（2016）

- 数据发布：GFZ Data Services，DOI 10.5880/GFZ.1.4.2016.001（Supplement）。核实：**许可证 CC BY-NC 4.0**；数据为 **GeoTIFF 约 2.9GB、30 角秒网格**，数值是模拟的天顶人工亮度（mcd/m²），基于 VIIRS DNB 卫星数据经大气辐射传输模型传播，**只含人工光、不含自然天光**（自然底需另加约 0.17–0.2 mcd/m² 量级的天然背景，换算 mpsas 时注意口径）。
- 这是目前唯一「全球覆盖、有明确出处与许可、可离线取用」的成品数据集。但两个硬约束：
  - **NC（非商业）**：自用完全没问题；但它决定了本 App 未来若收费分发或商业化即违规，届时必须换数据源。在设计文档中应把这条记为已知边界。
  - **体积**：2.9GB 原始栅格不可能进 APK。必须自建重采样管线：降采样到约 2–5 角分网格（对「查某地 Bortle 估计」足够）、亮度值量化编码、仅保留陆地像元——量级估算可压到数 MB 至数十 MB（**此为工程估算，未实测**，开发期需验证）。这条管线是一次性构建期工作，不进运行时。
- 数据年份为 2016 年前后的卫星数据，城市扩张地区会系统性偏暗；产品上应允许用户用实测（SQM 读数或肉眼 Bortle）覆盖图谱值——与设计里「收藏地点可存实测修正」正好衔接。

### Lorenz Light Pollution Atlas（djlorenz.github.io）

- https://djlorenz.github.io/astronomy/lp/ ：作者 David Lorenz 在 Cinzano 原始图谱方法上用 **2025 年** VIIRS 年均数据（Colorado School of Mines EOG 处理）重算，时效性优于 Falchi 2016；大陆图分辨率 1/120°、世界中图 1/40°。
- 但其发布形态是**地图图像与在线叠加层**，本次调研**未核实到**可离线下载的原始网格数据，也**未核实到**明确的数据再利用许可（作者仅就「不要把图谱等同于 Bortle 量表」作了说明）。结论：可作人工对照校验，**不作为数据源**，更不要抓取其瓦片打包——许可不明是实质风险。

### 其他

- lightpollutionmap.info 等在线站点的数据同样源自 Falchi 图谱，在线 API 不满足全离线要求，不考虑。
- 从 VIIRS 原始年均辐射数据（EOG 官方发布）自建传播模型在理论上可绕开 NC 条款，但等于重做 Falchi 的大气传播建模，工作量以周计，与本 App 规模完全不成比例，不建议。

### 本层结论

**Falchi GeoTIFF 自行重采样**是唯一可落地的离线路线：省掉了光害建模本身，只剩一次性数据管线；代价是接受 CC BY-NC 边界与 2016 年数据时效。设计中的 Bortle 输出应标注「图谱估算」并支持实测覆盖。

---

## ④ 天文规划 / NPF / 曝光逻辑层

这一层的实情是：**没有可整体复用的开源 Android 框架**。曝光推荐（按相机读噪、Bortle、月光、目标类型给参数）是本 App 的差异化核心，市面开源项目要么是桌面规划软件，要么是 Web 工具；可复用的是**模型与方法**，不是代码。

### 可借鉴的项目与方法

- **DarkHours**（https://darkhours.app ，Web 应用，自称免费开源；第三方报道称其仓库为 MIT 许可——仓库地址与 star 数本次未核实）：与本 App 定位最接近的规划器。其两个方法值得直接借鉴：
  - **月光贡献模型**用已发表的 Krisciunas & Schaefer (1991, PASP) 月光亮度模型 + Winkler (2022) 单散射模型，而不是简单的「月亮在不在地平线上」——本 App 第 4 项拍板意见（月光并入天光计算）应按此选型实现，公式有文献出处、可验证；
  - **评分聚合用几何平均**而非算术平均，防止「月光满分掩盖光害短板」——本 App 的「当日最佳目标」评分与「今晚条件总评」可沿用这个聚合思想。
  - 其地景广域目标库的过滤口径（角尺寸约 ≥9′、表面亮度下限）也与本 App 的广域类目标筛选直接相关。
  - 注意：它是 Web 技术栈且深度依赖在线天气 API，不能整仓移植，只借模型与口径。
- **mapboxer/backyard-astro-planner**（GitHub，Python/Jupyter Notebook）：OpenNGC + JPL 星历 + 暮光/月光建模，做「受限视域（阳台/树墙）内目标排序」——其「先定义可见窗口、再在窗口内排名」的思路与用户前院树高的真实场景高度契合，「当日最佳目标库」的遮挡档案逻辑可参考其方法；代码形态是 Notebook，不可直接复用（许可证与 star 数未核实）。
- **KStars/Ekos**（GPL-2.0，桌面）：「What's Up Tonight」的目标推荐逻辑成熟，可作产品行为对照；C++/桌面栈，Android 不可用，不取代码。
- **Stardroid / Sky Map**（https://github.com/sky-map-team/stardroid ，1,892 stars，69 个 Release，2026-09 刚发布 2.0，活跃）：许可证结构已变化，引用时必须分清——**v1 旧代码 Apache-2.0；v2 重写版 GPLv3（附应用商店分发许可），且图标等品牌资产为保留权利、不在开源范围内；合并分发整体按 GPLv3**。本 App 当前形态（参数卡 + 时间线，无实时星图）不需要它；若未来加「举起手机找目标」的星图页，可参考 v1 的传感器/坐标代码（Apache-2.0 部分），避开 v2 的 GPL 与品牌资产。其内置星表来源混杂，数据仍以 HYG/OpenNGC 为准。
- **Stellarium**（GPL-2.0，C++/Qt）：同理，仅作行为对照与数据结构参考（如流星雨字段），不集成。

### NPF 与曝光公式

- NPF 法则是**公开公式**（Frédéric Michaud / Société Astronomique du Havre 提出）：简化式 t = (35N + 30p) / f 并按目标赤纬 cos 修正，完整式还含像元拖影容忍项。本次检索未发现有公认的权威开源实现库，各在线计算器（商业的 PhotoPills、众多个人站点）口径一致。自研成本就是把公式按设计文档的严/宽三档口径实现并用公开计算器交叉验证，属于「实现公式」而非「造轮子」。
- 同理：500 法则、Looney 11、深空 swamp（天光淹没读噪）判据、日食分阶段曝光表（AstroPix 公开表）都是公开知识，本 App 的工作在「组合、按设备库参数化、与月光/光害模型耦合」，这部分正是产品价值所在，无现成框架可买。

### 本层结论

**逻辑仍需自研**，但三处不必从零想：月光模型按 Krisciunas & Schaefer + Winkler 的文献模型实现；评分聚合借 DarkHours 的几何平均；受限视域排序借 backyard-astro-planner 的窗口优先思想。NPF/swamp 等按公开公式实现并交叉验证。

---

## 选型建议表

| 层 | 推荐 | 备选 | 许可证 | 预计节省的工作量 |
|---|---|---|---|---|
| 历算 | Astronomy Engine（Kotlin/JitPack，单文件亦可） | commons-suncalc（仅日月，作校验源） | MIT（备选 Apache-2.0） | 历算模块自研与验证的绝大部分：日月位置、升落、暮光、月相、食搜索、坐标变换、目标高度角、星座判定 |
| 深空数据 | OpenNGC 裁剪取数 | —（Stellarium DSO 表许可混杂，不用） | CC BY-SA 4.0 | 整个深空库的建库与清洗；表面亮度、尺寸、Messier 交叉现成 |
| 恒星数据 | HYG v4.4 亮星子集（Codeberg 取数） | — | CC BY-SA 4.0 | 恒星表建库；只需一次截断与打包 |
| 星座 | 自建 88 星座小表 + Engine 判定归属 | stick figures 数据集（未来星图用） | 自有数据 / 函数来自 MIT 库 | 省掉星座边界数据与归属算法 |
| 流星雨 | 按 IMO 日历自建静态表（字段仿 Stellarium showers.json 结构） | IAU MDC 原始表 | 事实数据，标注 IMO/IAU 出处 | 省掉字段设计与峰值黄经口径的摸索；整理工作仍在 |
| 日月食 | NASA GSFC 目录/贝塞尔元素 CSV 取子集 + Engine 食搜索 | —（无可信 GitHub 镜像） | NASA/Espenak 署名即可转载 | 省掉五千年目录的整理；本地接触时刻计算仍可能自研 |
| 光害 | Falchi 图谱 GeoTIFF 构建期重采样 | 用户实测值覆盖（产品功能） | CC BY-NC 4.0 | 省掉光害传播建模本身；只剩一次性重采样管线 |
| 曝光/评分逻辑 | 自研（公式公开）+ 借鉴 DarkHours 月光模型与几何平均评分 | backyard-astro-planner 的窗口优先排序思想 | 自有代码；文献模型注明出处 | 省掉月光模型与评分聚合的方法论摸索；核心参数化逻辑仍自研 |

---

## 不建议用的热门选项及原因

- **Stellarium 源码或其数据整包**：GPL-2.0 + C++/Qt 桌面栈，无法以库形式嵌入 Kotlin Android App；其星表/DSO 数据许可来源混杂，整包取用会把许可问题一起搬进来。只借数据结构设计（如流星雨字段）。
- **Stardroid v2**：GPLv3 传染性 + 品牌资产保留权利 + 商标限制；且 v1 代码库已宣布退役、贡献关闭。若未来确需星图代码，只取 v1 的 Apache-2.0 部分并独立核对每个文件头。
- **commons-suncalc 作主历算引擎**：功能缺日月食与任意目标高度角，作者自述精度定位非天文级；作主引擎会在做到一半时被迫换轨。
- **JS（mourner/suncalc 等）与 Rust 历算库**：语言桥接（JS 引擎/JNI）的集成与维护成本高于 Kotlin 原生方案，功能还更弱。热门不等于适合。
- **抓取 Lorenz 图谱或 lightpollutionmap 的瓦片/接口**：前者许可未明示、发布物是图像不是数据；后者需联网。两者都与「全离线 + 许可干净」的前提冲突。
- **Skyfield / Astropy 等 Python 科学栈**：桌面/服务端生态，Android 上无现实部署路径；其星历文件体积与运行时都不匹配移动端离线小工具。

---

## 许可证义务速记（自用侧载场景）

- **MIT / Apache-2.0 / BSD-2**（Astronomy Engine、commons-suncalc、suncalc）：保留版权与许可声明即可，无开源传染。
- **CC BY-SA 4.0**（OpenNGC、HYG 数据）：必须署名；**衍生数据集若对外分发须同许可开放**。自用不触发分发义务，但 App 内置「数据来源」页是低成本合规动作，建议照做。
- **CC BY-NC 4.0**（Falchi 光害数据）：署名 + **不得商业使用**。自用合规；这是本 App 未来商业化的唯一硬边界，换数据（自建 VIIRS 传播或购买商业图谱）才能解除。
- **GPL-2.0/GPLv3**（Stellarium、KStars、Stardroid v2）：本方案不集成其代码、不打包其数据文件，仅借鉴公开的方法与字段结构——GPL 不传染「思想」，但不要复制其代码片段或整表数据。
- **NASA/Espenak 数据**：附致谢语即可转载。

---

## 最值得注意的一个风险

**日食的「本地时刻」缺口。** Astronomy Engine 只给事件级食预报，NASA 目录给的是全球参数；用户真正想看的「我家院子里初亏、食甚、复圆各是几点」需要贝塞尔元素本地计算（含 ΔT 改正），这块自研躲不掉且误差敏感。若 v1.0 不做这块，日食模块的文案必须明确停在「事件提醒 + 分阶段曝光参考」，不要承诺本地接触时刻，避免给出错误的安全相关时间（全食期摘镜时机与时刻直接相关）。
