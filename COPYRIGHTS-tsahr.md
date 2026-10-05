# tsahr — copyright and licensing

tsahr is distributed under the GNU General Public License, version 2 or (at
your option) any later version (GPL (>= 2)); see the License field of
DESCRIPTION. From 0.2.7.22 the package is licensed this way because it
contains code ported from the R package RTSA, itself licensed GPL (>= 2).
Earlier releases (up to 0.2.7.21) were labelled MIT; the maintainer, as
copyright holder of the tsahr-original code, relicensed it, and the RTSA-
derived code could not be distributed under MIT terms.

The full licence text is in [LICENSE.md](LICENSE.md).

## Copyright holders

* **Tarak Dhaouadi** — tsahr (the package, its interface, statistics layer,
  tests and documentation, and the adaptation of the code listed below).
* **Anne Lyngholm Soerensen, Markus Harboe Olsen, Theis Lange and Christian
  Gluud** — the R package RTSA 0.2.2 (GPL (>= 2)), the algorithms and code of
  which the files listed below are derived from.

## Files derived from RTSA

* `src/rtsa_core.h`, `src/rtsa_engine.cpp`, `src/RcppExports.cpp` — C++ port of
  RTSA's recursive-integration boundary engine (`first.cpp`: `init_int()`,
  `recur_int()`, `prob()`; `R/RTSA_helperfunctions.R`: `z_n_w()`, `searchfunc()`,
  `alpha_boundary()`, `beta_boundary()`, `esOF()`, `sd_inf()`).
* `R/rtsa_engine.R` — R orchestration reproducing RTSA's `boundaries()` design and
  analysis routes (information-scale root searches, `rm_bs` handling).
* `R/obf_boundaries.R` — the earlier pure-R reconstruction of the same
  engines (legacy fallback), written from RTSA's sources.
* `tools/rtsa_port.py` (development only) — Python port of the same engine.
* `inst/extdata/rtsa_0.2.2_reference.R` — numbers printed by RTSA 0.2.2, kept
  as a test reference.

## Origin of RTSA

RTSA is the R version of Trial Sequential Analysis (TSA), originally
developed as a stand-alone Java program by the Copenhagen Trial Unit. The RTSA
manual is heavily inspired by the user manual for TSA by Kristian Thorlund,
Janus Engstroem, Joern Wetterslev, Jesper Brok, Georgina Imberger and
Christian Gluud. The original TSA software: <https://ctu.dk/tools>

> Copenhagen Trial Unit  
> Centre for Clinical Intervention Research  
> Department 3344, Rigshospitalet  
> DK-2100 Copenhagen O, Denmark  
> Tel. +45 3545 7171 · Fax +45 3545 7101 · E-mail: tsa@ctu.dk

Departures from RTSA are documented in `NEWS.md` and
`inst/REVERSE_ENGINEERING_RTSA.md` (in the R package tsahr).
