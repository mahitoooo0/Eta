# 二改补丁清单（PATCHES.md）

> 上游链：`Mangi-11/Eta` → `y2485871697/Eta`（我跟随的一层，remote `upstream`）→ `mahitoooo0/Eta`（本仓库）
> 同步上游时照此表逐条确认"补丁还在、还必要"。

| 补丁 | 目的 | 触及文件 | 上游更新后怎么验 |
|---|---|---|---|
| `ci: 二改 dev 构建流水线` | 不依赖签名 secrets 的 `assembleDebug` 出包；`-PetaDevSuffix` 让包名变 `io.github.mangi.eta.dev`，与官方版并存安装 | `.github/workflows/build-dev.yml`、`app/build.gradle.kts` | dispatch 一次，产物 `unzip -l` 有 classes.dex，装到手机 `pm list packages | grep eta.dev` |
| `ci: probe workflow write permission` | 一次性探针，已废弃 | `.github/workflows/probe.yml` | 合并前删除 |

## 维护约定

- 一个补丁一个 commit，message 用 `feat:` / `fix:` / `ci:` 前缀。
- 尽量别碰上游正在大改的文件（`gradle/libs.versions.toml`、`buildSrc/`）。
- 补丁被上游吸收 → 从本表移除并删 commit（rebase 时 `--skip`）。
