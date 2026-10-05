# tsahr-java — Trial Sequential Analysis for meta-analyses of hazard ratios (Java edition of tsahr)

A stand-alone desktop and command-line application, **no R needed**, that runs the same analysis as
**`tsa_hr()`** in the R package [tsahr](https://github.com/tarak-dhaouadi/tsahr) (this edition mirrors
**tsahr**).

* Schoenfeld required-events formula, generalised for unequal allocation
* Diversity (D²) heterogeneity adjustment (Wetterslev et al. 2009) → DARIS
* O'Brien-Fleming-type alpha-spending and non-binding beta-spending (futility) boundaries from the
  RTSA-derived recursive-integration engine, **design** and **analysis** routes
* Random-effects τ² estimators `DL, HE, HS, HSk, SJ, ML, REML, EB, PM, PMM`; standard or **HKSJ** inference
  (`standard`, `hksj`/`knha`, `hksj_adhoc`/`knha_adhoc`)
* Historical-rate projection of the additional events/studies needed
* The TSA chart (cumulative Z-curve, alpha / futility / naive boundaries, DARIS markers)

## Run it

Needs only a Java runtime (11 or later).

1. Download `tsahr-java.jar` from the [Releases](https://github.com/tarak-dhaouadi/tsahr-java/releases) page.
2. Double-click the JAR, or run:

```
java -jar tsahr-java.jar
```

Command-line options carry the names of the `tsa_hr()` arguments:

| R argument | Option |
|---|---|
| `target_HR` | `--target-hr` |
| `alpha_two_sided`, `power` | `--alpha`, `--power` |
| `allocation_source`, `allocation_p` | `--allocation data\|manual`, `--allocation-p` |
| `method` | `--method` |
| `re_inference` | `--re-inference standard\|hksj\|hksj_adhoc` |
| `order_by` | `--order-by COLUMN` |
| `boundary_route`, `legacy_fallback` | `--route design\|analysis`, `--no-fallback` |
| `projection_stat`, `info_per_event_basis` | `--projection-stat`, `--info-per-event` |

## Data

One row per study, **in chronological order** (or choose a sorting column). Required columns, as in the R package:

`Study, log_HR, Std_Error, Events_Treatment, N_treatment, Events_controls, N_controls`

`.xlsx` (first sheet), `.csv`, `.tsv`, `.txt`. Two example datasets (20 and 40 studies) are bundled in the
application (*File → Load bundled example*).

## Chart labels and options

The four DARIS-type labels (theoretical DARIS, historical-rate projection, DARIS information reached,
analysis-route endpoint) sit in the **upper** part of the plot when the cumulative Z-curve is negative and in the
**lower** part when it is positive; the *Events accrued* label goes to the opposite side, next to the curve. A label that
would run past the right edge is drawn to the left of its line.

The **Chart options…** button (above the output tabs, next to **Save chart (PNG)…**) opens a dialog — and the matching command-line options — that control:

| Application | Command line | Default |
|---|---|---|
| DARIS label placement (automatic / upper / lower) | `--label-placement auto\|upper\|lower` | automatic |
| Legend, Methods caption, Theoretical DARIS line, Historical-rate line | `--no-legend`, `--no-caption`, `--no-theoretical-daris`, `--no-historical-daris` | shown |
| x-axis margin multiplier | `--xmax-mult` | 1.15 |
| Label font size (ggplot units, as `daris_label_size`) | `--label-font-size` | 3.2 |
| Caption font size (points, as `caption_size`) | `--caption-font-size` | 8 |

The chart is redrawn when you press OK; Cancel keeps the previous settings.

**Save chart (PNG)…** first asks for the resolution of the 11 x 7.5 inch chart — 150, 300 (default),
600 or 1200 dpi — then for the file name. The PNG is 1,650 x 1,125 px at 150 dpi, 3,300 x 2,250 px at 300 dpi,
6,600 x 4,500 px at 600 dpi and 13,200 x 9,000 px at 1200 dpi (a large file; about 10 seconds). Fonts keep their physical
size at every resolution, and the dpi is stored in the file so that Word, PowerPoint or a journal's upload system size
it correctly. On the command line: `--plot chart.png --dpi 600`.

## Build

```
./build.sh          # JDK 11+ only; produces out/tsahr-java.jar
./build.sh test     # also runs the tests
mvn package         # alternative, with Maven
```

## Verification status — please read

* The boundary engine was checked against the numbers printed by **RTSA 0.2.2 itself** (frozen in
  `inst/extdata/rtsa_0.2.2_reference.R` of tsahr): design root, alpha bounds (1e-12), design beta bounds (1e-7),
  analysis-route bounds (1e-9) and the beta spending — 48 checks, all passing.
* The statistics layer (pooled estimate, τ², I², D², DARIS, cumulative Z) was checked against an independent
  numpy/scipy implementation, not against R/metafor.
* ML, REML, EB, PM and PMM τ² are solved to tight tolerance with Brent's method, whereas metafor iterates with a
  looser threshold; agreement is expected to ~1e-5.
* The legacy R-only boundary engine of tsahr (an approximate fallback) is **not** ported. If the RTSA-derived engine
  fails, the Java edition stops with an error, which corresponds to `legacy_fallback = FALSE` for the design route.
  The analysis → design fallback is kept (`--no-fallback` disables it; in the GUI, untick "If the analysis route fails, fall back to the design route").
* Warnings that R raises with `warning()` are collected and shown in the *Warnings* tab / on stderr.

## Relationship to tsahr

The R package is the reference implementation; this repository follows it. When tsahr changes behaviour, the version in
`TsaHr.TSAHR_PARITY_VERSION` and this README are updated together.

## Licence

GPL (≥ 2), like tsahr. The boundary engine derives from the R package RTSA (Anne Lyngholm Soerensen, Markus Harboe Olsen,
Theis Lange, Christian Gluud); see [`COPYRIGHTS-tsahr.md`](COPYRIGHTS-tsahr.md) and [`LICENSE.md`](LICENSE.md). RTSA/TSA originate from the Copenhagen
Trial Unit's TSA software, https://ctu.dk/tools.
