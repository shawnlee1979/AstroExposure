# AstroExposure 曝光参谋

**Offline Android exposure planner for astrophotography.** Given your location, date, camera, lens, tracking setup and target type, it computes a set of starting exposure settings — aperture, shutter speed and ISO — that you can dial straight into the camera, each with bracketing suggestions and a one-line rationale.

Everything runs on-device: no account, no cloud, no network required in the field.

> All recommended values are **starting points**, not "correct exposures". Every parameter card carries the reminder: check the histogram and a test shot; protecting the highlights comes first.

---

## Status

**Implemented and in active iteration (current release: 0.9.1).** The full three-layer codebase (domain / data / app) lives in this repository, and pre-release APKs are published on the [Releases](https://github.com/shawnlee1979/AstroExposure/releases) page. The design document is in [docs/design.md](docs/design.md) (Chinese; the app itself is bilingual).

## Features

Exposure recommendations for five target classes, plus planning tools:

| Target class | What you get |
|---|---|
| **Nightscape with foreground** | Two parameter sets — one for the sky, one for the foreground — covering blue-hour, long-exposure and stacked foreground techniques, with a two-stage shooting workflow |
| **Wide-field sky (incl. the Milky Way)** | Untracked: NPF rule with per-camera pixel pitch. Tracked: drift-limited exposure from your mount's measured drift profile |
| **Deep-sky objects** | Sub-exposure length from the sky-background "swamp" criterion, plus total integration time, frame count and data-volume estimates |
| **Moon & eclipses** | Moon: Looney 11 rule corrected for phase. Eclipses: phase-by-phase exposure tables with a mandatory, non-dismissible solar-filter safety gate |
| **Meteor showers** | Single-frame settings capped by sky brightness, a built-in shower calendar, radiant altitude timeline, moonlight impact assessment and battery/storage budget for continuous shooting |

Supporting features:

- **Best targets tonight** — given the date and GPS location, a cross-category shortlist (deep-sky, wide-field, eclipses, meteor showers, constellations) with the best time window for each (simplified overview in v1.0; full scoring engine in v1.1).
- **Night timeline** — twilight boundaries, moonrise/moonset, lunar phase and illumination, galactic-core window, target altitude key points.
- **Built-in light-pollution dataset** — sky brightness by GPS coordinates from a bundled, resampled offline atlas; no network lookup.
- **Camera & lens libraries** — per-camera sensor data (pixel pitch, dual-gain points, read noise), built-in mirrorless lens lists for Sony / Canon / Nikon / Tamron / Sigma, plus user-defined entries.
- **Tracking profiles** — store measured mount drift rates (typical / worst case) per calibration level; the engine converts them into per-combination exposure limits.
- **Three strictness levels** — strict / standard / relaxed, recommended automatically from your gear and tracking state, adjustable at any time.
- **Red-light night mode** and a **bilingual UI** (Chinese by default, English optional).

Deliberately out of scope: mount control, camera control, image processing, star charts / GOTO. AstroExposure only answers *"what settings, and when"*.

## Methods in brief

- **NPF rule** — untracked shutter limit from focal length, pixel pitch and target declination, with a strictness factor (k = 1 / 2 / 3).
- **Drift model** — tracked exposure limit = allowed pixel drift (≤1 px strict, ≤2 px relaxed) ÷ measured angular drift rate, using each camera's pixel scale.
- **Swamp criterion** — deep-sky sub-exposure long enough for sky background to swamp read noise (target background ≈ C × RN², C = 10 strict/standard, 5 relaxed); sky rate from light pollution **plus moonlight**.
- **Looney 11** — lunar exposure baseline at f/11, corrected by phase-illumination steps.
- **Moonlight contribution model** — total sky brightness = light-pollution baseline + moonlight term (Krisciunas & Schaefer 1991, with Winkler 2022), driven by lunar illumination, moon altitude and moon–target separation. On bright-moon nights the app says so plainly instead of fabricating usable deep-sky settings.

Ephemeris (Sun/Moon positions, twilight, moon phases, eclipse search, target altitude) is provided by the Astronomy Engine — see the tech stack below — so the astronomy is library-grade and unit-tested upstream rather than hand-rolled.

## Tech stack

- **Language / platform:** Kotlin, native Android, fully offline.
- **Ephemeris:** [Astronomy Engine](https://github.com/cosinekitty/astronomy) (MIT) — official Kotlin version.
- **Deep-sky data:** [OpenNGC](https://github.com/mattiaverga/OpenNGC) (CC BY-SA 4.0), trimmed at build time.
- **Star data (planned, not yet integrated):** HYG Database subset (CC BY-SA 4.0) — the shipped catalogue is OpenNGC only; HYG bright stars are scheduled with the full scoring engine.
- **Light pollution:** Falchi et al., *The New World Atlas of Artificial Night Sky Brightness* (CC BY-NC 4.0), resampled at build time into a compact offline grid.
- **Eclipse & meteor data:** static tables built from the NASA GSFC eclipse catalogues (Espenak & Meeus) and the IMO shower calendar.

Full selection rationale: [docs/opensource-survey.md](docs/opensource-survey.md).

## Roadmap

- **v1.0 (MVP, shipped as the 0.x series)** — All five exposure models; Astronomy Engine ephemeris; offline light-pollution lookup; strictness levels; moonlight model; simplified "tonight" overview; parameter cards; shooting plans with plain-text export; red-light mode; JSON export/import of plans and libraries. *Explicitly not in v1.0: local eclipse contact times (event-level forecasts and phase exposure tables only).*
- **v1.1** — Full "best targets" scoring engine over the complete catalogues; per-target deep-sky integration times; local eclipse contact times computed from Besselian elements (with ΔT handling); completed per-ISO read-noise tables. (The graphical timeline originally scheduled here shipped early, in 0.4.0–0.6.0.)
- **v1.2** — Narrowband filter model; horizon-obstruction profiles; light-pollution dataset refresh evaluation; mosaic exposure-consistency hints; shot-log review fields.
- **Beyond v1.2 (ideas, unscheduled)** — An aurora module (dedicated exposure model plus Kp / geomagnetic-latitude visibility assessment) and general long-exposure landscape tools (ND-filter exposure conversion, bulb timing).

## Documentation

- [Design document](docs/design.md) — product definition, input/calculation/output layers, models, phasing, decision log (Chinese).
- [Open-source survey](docs/opensource-survey.md) — framework and dataset selection with per-item licence verification (Chinese).
- [Compass and orientation survey](docs/compass-survey.md) — library selection for the device-orientation port: platform capabilities, candidates and licence checks (Chinese).
- [UI design spec](docs/ui-design.md) — interface design tokens, key screens and state presentation (Chinese), with mockup boards [docs/ui-design-board.html](docs/ui-design-board.html) and [docs/home-design-board.html](docs/home-design-board.html) (tonight-page integration layout), plus [docs/timeline-compare-board.html](docs/timeline-compare-board.html) (timeline layout comparison).
- [Data licences](DATA-LICENSES.md) — third-party data and library attributions and obligations.
- [Development rules](DEVELOPMENT-RULES.md) — layering, third-party adapter isolation, contract tests, data-pipeline and change-process rules all code must follow.
- Architecture decision records — [ADR-0001 development rules and layering](docs/adr/0001-development-rules-and-layering.md), [ADR-0002 package name](docs/adr/0002-package-name.md), [ADR-0003 catalog ports and resources](docs/adr/0003-catalog-ports-and-resources.md).
- [Eclipse table verification](docs/eclipse-table-verification.md) — cell-by-cell check of the built-in eclipse exposure table against its published source.

## Licence

The **code** in this repository is released under the [MIT License](LICENSE).

The **data** is not all MIT: bundled datasets and tables carry their own licences (CC BY-SA 4.0, CC BY-NC 4.0, NASA/IMO attribution terms). See [DATA-LICENSES.md](DATA-LICENSES.md) before reusing any data from this repository. In particular, the light-pollution data is **non-commercial only**.

## Contributing

Issues and pull requests are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md). Data corrections (camera read-noise figures, catalogue entries) are especially valuable; please cite a measurable source.

---

# 中文版

**曝光参谋 AstroExposure —— 离线运行的 Android 天文摄影曝光参数推荐工具。** 按拍摄地点、日期、相机、镜头、跟踪条件与目标类型，给出一组可以直接拨到相机上的起步曝光参数（光圈 / 快门 / ISO），附包围建议与一句话理由。

全部计算在手机本地完成：无账号、无云端、野外无网络也能用。

> 所有推荐值都是**起步值**，不是「正确曝光」。每张参数卡片都固定标注：请以直方图与试拍为准，亮部不溢出优先。

## 项目状态

**已实现并持续迭代（当前版本 0.12.1）。** 完整三层代码（domain / data / app）已在本仓库，预发布 APK 在 [Releases](https://github.com/shawnlee1979/AstroExposure/releases) 页提供下载。完整设计方案见 [docs/design.md](docs/design.md)。

## 功能概览

五类拍摄目标的曝光推荐，加规划工具：

| 目标类别 | 输出 |
|---|---|
| **带地景的星野** | 双套参数——天空帧 + 地景帧（蓝调时刻 / 夜间长曝 / 高 ISO 多张三种地景拍法），附机位不动的两段式拍摄流程 |
| **广域星空（含银河）** | 不跟踪：按相机像素间距的 NPF 法则；跟踪：按云台实测漂移档案换算的最长单张 |
| **深空目标** | 按天光「淹没」读出噪声的 swamp 判据算子曝光时长，并给总累积时长、张数与数据量预算 |
| **月 / 日食** | 月亮：Looney 11 法则 + 月相修正；日食：分阶段曝光表 + 不可关闭的减光镜安全确认闸门 |
| **流星雨** | 单张由天光封顶的连续连拍参数、内置流星雨表、辐射点高度时间线、月光干扰评估与电量 / 存储预算 |

配套功能：

- **当日最佳目标库** —— 按日期 + GPS 跨类别（深空、广域星空、日 / 月食、流星雨、星座）给出今晚最值得拍的少数目标与最佳时间窗（v1.0 为简化版「今晚概览」，完整评分引擎在 v1.1）。
- **当晚时间线** —— 暮光起止、月出月落、月相与照明比、银心窗口、目标高度关键时刻。
- **内置光害数据集** —— 按 GPS 坐标查天光亮度的离线图谱，全程不联网。
- **相机与镜头库** —— 相机传感器参数（像素间距、双增益点、读出噪声）；内置 Sony / Canon / Nikon / Tamron / Sigma 无反镜头清单，用户可自定义任意镜头。
- **跟踪档案** —— 按校准档位保存实测漂移率（典型 / 最差双值），引擎按当前组合现算像素容忍时长。
- **严苛三档** —— 严格 / 标准 / 宽松，系统按设备与跟踪状态推荐，用户可改；卡片标注所用档位。
- **红光模式**（v1.0 必做）与**中英双语界面**（汉语默认、英语可选）。

明确不做：云台控制、相机控制、后期处理、星图 / GOTO。本 App 只回答「用什么参数、何时拍」。

## 核心方法（一句版）

- **NPF 法则** —— 由焦距、像素间距与目标赤纬算不跟踪最长快门，严苛系数 k = 1 / 2 / 3 随档位取。
- **漂移模型** —— 跟踪最长单张 = 容忍像素漂移（严格 ≤1px、宽松 ≤2px）÷ 实测角漂移率，按各机身像素尺度换算。
- **Swamp 判据** —— 深空子曝光须让天光背景淹没读出噪声（目标背景 ≈ C × RN²，严格 / 标准 C=10、宽松 C=5）；天光速率由光害与月光共同决定。
- **Looney 11** —— 月亮曝光基线（f/11），按月相照明比逐档修正。
- **月光贡献模型** —— 总天光 = 光害基线 + 月光项（Krisciunas & Schaefer 1991 + Winkler 2022 简化口径，由月相照明比、月亮高度、月–目标角距驱动）。满月之夜系统会直言不适合深空，而不是硬凑参数。

天文历算（日月位置、暮光、月相、食搜索、目标高度）由 Astronomy Engine 提供——使用经上游严格单元测试的库，而不是手写公式。

## 技术栈

- **语言 / 平台：** Kotlin，Android 原生，全离线。
- **历算：** [Astronomy Engine](https://github.com/cosinekitty/astronomy)（MIT），官方 Kotlin 版。
- **深空数据：** [OpenNGC](https://github.com/mattiaverga/OpenNGC)（CC BY-SA 4.0），构建期裁剪。
- **恒星数据（规划中，尚未接入）：** HYG 数据库亮星子集（CC BY-SA 4.0）——现行目标库仅 OpenNGC，HYG 亮星随完整评分引擎一并接入。
- **光害数据：** Falchi 等《全球人工夜空亮度图谱》（CC BY-NC 4.0），构建期重采样为离线网格。
- **日月食与流星雨数据：** 按 NASA GSFC 日月食目录（Espenak & Meeus）与 IMO 流星雨日历在构建期生成的静态表。

选型全过程见 [docs/opensource-survey.md](docs/opensource-survey.md)。

## 路线图

- **v1.0（MVP）** —— 五类模型全量；Astronomy Engine 历算；离线光害查表；严苛三档；月光模型；简化版「今晚概览」；参数卡片与图形时间轴（已随 0.4.0–0.6.0 分期提前交付）；计划保存与纯文本导出；红光模式；计划与设备库的 JSON 导出 / 导入。*明确不含：日食本地接触时刻（只到事件提醒 + 分阶段曝光参考）。*
- **v1.1** —— 当日最佳完整评分引擎与全库接入；深空按目标给总时长；日食本地接触时刻自算（贝塞尔元素 + ΔT）；逐 ISO 读噪表补齐转正。（原排于此的图形时间轴已提前于 0.4.0–0.6.0 分期交付。）
- **v1.2** —— 窄带滤镜模型；地平线遮挡档案；光害数据时效更新评估；接片曝光一致性提示；实拍复盘字段。
- **v1.2 之后（展望，未排期）** —— 极光模块（独立曝光模型 + Kp 指数 / 地磁纬度可见性评估）与长曝光风光工具（ND 减光镜换算、Bulb 计时）。

## 文档

- [设计方案](docs/design.md) —— 产品定义、输入 / 计算 / 输出三层、五类模型、分期与决议记录。
- [开源选型调研](docs/opensource-survey.md) —— 框架与数据集选型，逐项核实许可证。
- [罗盘与姿态指向选型调研](docs/compass-survey.md) —— 方向传感器端口的库选型：平台自带能力、候选核查与许可证结论。
- [UI 设计规格](docs/ui-design.md) —— 界面设计令牌、关键界面与状态呈现；配套样张 [docs/ui-design-board.html](docs/ui-design-board.html) 与首页整合样张 [docs/home-design-board.html](docs/home-design-board.html)、时间轴对照样张 [docs/timeline-compare-board.html](docs/timeline-compare-board.html)。
- [数据许可说明](DATA-LICENSES.md) —— 第三方数据与库的来源、署名与使用义务。
- [开发规则](DEVELOPMENT-RULES.md) —— 分层架构、第三方接口隔离、契约测试、数据管线与变更流程的硬性纪律。
- 架构决策记录 —— [ADR-0001 开发规则与分层](docs/adr/0001-development-rules-and-layering.md)、[ADR-0002 包名](docs/adr/0002-package-name.md)、[ADR-0003 目录端口与资源](docs/adr/0003-catalog-ports-and-resources.md)。
- [日食曝光表核对记录](docs/eclipse-table-verification.md) —— 内置日食曝光表与原始发表表逐格核对。

## 许可证

本仓库的**代码**以 [MIT 许可证](LICENSE) 发布。

**数据并非全部 MIT**：内置数据集与静态表各有其许可（CC BY-SA 4.0、CC BY-NC 4.0、NASA / IMO 署名条款）。复用本仓库任何数据前请先看 [DATA-LICENSES.md](DATA-LICENSES.md)。特别注意：光害数据**仅限非商业用途**。

## 参与贡献

欢迎 issue 与 PR，见 [CONTRIBUTING.md](CONTRIBUTING.md)。尤其欢迎数据纠错（相机读出噪声、星表条目），请附可核查的数据来源。
