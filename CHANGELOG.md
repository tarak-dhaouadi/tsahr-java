# Changelog

All notable changes to **tsahr-java** are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/). tsahr-java is the Java edition of the R package
[tsahr](https://github.com/tarak-dhaouadi/tsahr); each release states which tsahr version it mirrors.

<!--
  NOTE FOR THE MAINTAINER: the 0.1.0 section was written without the 0.1.0 source tree at hand.
  Entries in 0.1.1 marked "(verify)" are inferred from the 0.1.1 code and file history and should be
  checked against a diff of 0.1.0..0.1.1 before release. Delete this comment when done.
-->

## [0.1.1]

Parity release: follows **tsahr 0.2.8.18** (0.1.0 followed tsahr 0.2.8.17).

### Changed

- **Parity with tsahr 0.2.8.18.** The Java port of the R package was brought up to date: the recursive-integration
  boundary engine (`RtsaEngine`, from `src/rtsa_core.h`), the boundary orchestration for the design and analysis
  routes (`RtsaBounds`, from `R/rtsa_engine.R`), the `tsa_hr()` pipeline (`TsaHr`) and the argument defaults
  (`TsaHrSettings`) now correspond to tsahr 0.2.8.18. `TsaHr.TSAHR_PARITY_VERSION` and the README were updated together,
  as the project's parity rule requires.
- The application and the command line report version 0.1.1 and the tsahr version they mirror
  (*About* dialog, window title, `--help`).
- The README was revised: it now documents the chart options (label placement, legend / caption / reference-line
  toggles, axis margin, label and caption font sizes), PNG export at 150 / 300 / 600 / 1200 dpi, and the verification
  status of the engine and statistics layer. (verify)

### Improved

- **Boundary-engine diagnostics (verify).** Departures from RTSA's own behaviour are counted and reported as
  warnings instead of passing silently: a reversed integration interval, a collapsed (degenerate) integration grid,
  and a boundary search that converged only on the slow, iteration-capped path (accepted within a loose tolerance
  of 1e-6). A reversed interval is flagged as *not* a valid RTSA computation, so boundaries from that look onward
  are not reported as RTSA-equivalent. The warnings appear in the *Warnings* tab and on stderr.
- **Unreachable beta target (verify).** When the futility (beta-spending) target cannot be reached at a look, the
  engine signals it explicitly and records the look, rather than returning an unreliable boundary.
- **Desktop application (verify).** `AppPanel` was revised alongside the README changes above (chart options and
  chart export controls).

### Added

- **Continuous integration.** A GitHub Actions workflow (`.github/workflows/check.yaml`) builds the JAR with
  `build.sh` and runs the engine parity test (against the frozen RTSA 0.2.2 reference) and the regression test on
  Java 11, 17 and 21, across Ubuntu, Windows and macOS. The Java 11 build is uploaded as the `tsahr-java-jar`
  artifact. (verify)
- **Release workflow** (`.github/workflows/release.yaml`): publishing a GitHub Release builds `tsahr-java.jar`, runs the
  tests, and attaches the JAR to that release, so users can download it from the Releases page. It can also be run
  manually for an existing tag.
- `.gitattributes`: consistent LF line endings (shell scripts always LF), and the bundled `.xlsx` example data
  treated as binary.
- `CITATION.cff`: citation metadata, also enabling GitHub's *Cite this repository* button.
- This `CHANGELOG.md`.

### Documentation

- The README gained status / licence / Java-version badges and a screenshot of the application (`docs/screenshot.png`).
- `LICENSE` and `COPYRIGHTS-tsahr.txt` were converted to Markdown (`LICENSE.md`, `COPYRIGHTS-tsahr.md`). The GPL
  text is unchanged; the README and source comments point to the new file names.

## [0.1.0]

First release of the Java edition, mirroring **tsahr 0.2.8.17**.

### Added

- Stand-alone desktop application and command-line tool (`java -jar tsahr-java.jar`), no R required, Java 11+.
- Java port of `tsa_hr()`: Schoenfeld required events (generalised for unequal allocation), diversity (D²)
  adjustment and DARIS, historical-rate projection of the additional events / studies needed.
- O'Brien-Fleming-type alpha-spending and non-binding beta-spending (futility) boundaries from the RTSA-derived
  recursive-integration engine, with the **design** and **analysis** routes and the analysis → design fallback.
- Random-effects τ² estimators `DL, HE, HS, HSk, SJ, ML, REML, EB, PM, PMM`, with standard or HKSJ inference
  (`standard`, `hksj` / `knha`, `hksj_adhoc` / `knha_adhoc`).
- The TSA chart (cumulative Z-curve, alpha / futility / naive boundaries, DARIS markers) with PNG export.
- Data input from `.xlsx`, `.csv`, `.tsv` and `.txt`; two bundled example datasets (20 and 40 studies).
- Engine parity test against numbers printed by RTSA 0.2.2, a regression test, and `build.sh` (JDK-only build)
  plus a Maven `pom.xml`.

### Not included

- The legacy R-only boundary engine of tsahr (an approximate fallback) is not ported; if the RTSA-derived engine
  fails, the Java edition stops with an error.

[0.1.1]: https://github.com/tarak-dhaouadi/tsahr-java/releases/tag/v0.1.1
[0.1.0]: https://github.com/tarak-dhaouadi/tsahr-java/releases/tag/v0.1.0
