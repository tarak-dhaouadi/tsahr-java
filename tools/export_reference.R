## Run in R with tsahr installed, then compare with the Java edition:
##   Rscript tools/export_reference.R src/test/resources/HR_meta.xlsx 0.80
##   java -jar out/tsahr-java.jar --data src/test/resources/HR_meta.xlsx --target-hr 0.80 \
##        --cumulative-tsv java_cum.tsv --boundaries-tsv java_bnd.tsv --quiet
## then diff r_cum.tsv / r_bnd.tsv against the Java files (columns share the same names).
args <- commandArgs(trailingOnly = TRUE)
library(tsahr)
res <- tsa_hr(args[1], target_HR = as.numeric(args[2]), verbose = FALSE)
cum <- res$cumulative[, c("Study", "cum_events", "estimate", "se", "Z", "info_fraction",
                          "TSA_boundary_upper", "TSA_futility_upper")]
write.table(cum, "r_cum.tsv", sep = "\t", quote = FALSE, row.names = FALSE, na = "NA")
write.table(res$boundary_timeline, "r_bnd.tsv", sep = "\t", quote = FALSE, row.names = FALSE, na = "NA")
print(res$summary_table, row.names = FALSE)
