suppressPackageStartupMessages(library(circlize))
suppressPackageStartupMessages(library(svglite))

species <- "hg38"
gain_file <- "/Users/aminsuliman/Downloads/MPG_database_H2/circos/exports/cohort_6_results_20260624_105442/events_gain.tsv"
loss_file <- "/Users/aminsuliman/Downloads/MPG_database_H2/circos/exports/cohort_6_results_20260624_105442/events_loss.tsv"
connections_file <- "/Users/aminsuliman/Downloads/MPG_database_H2/circos/exports/cohort_6_results_20260624_105442/connections.tsv"
svg_output <- "/Users/aminsuliman/Downloads/MPG_database_H2/circos/output/circos_cohort_6_results_20260624_105442.svg"
plot_title <- "CNV and Translocation Circos Plot"
plot_subtitle <- "cohort_6_results_20260624_105442 | genome_build=GRCh38 | results=[1190, 1191, 1192, 1193, 1194, 1195]"

read_track <- function(path) {
  read.delim(path, stringsAsFactors = FALSE, check.names = FALSE)
}

gains <- read_track(gain_file)
losses <- read_track(loss_file)
connections <- read_track(connections_file)

dir.create(dirname(svg_output), recursive = TRUE, showWarnings = FALSE)

chromosomes <- paste0("chr", c(1:22, "X", "Y"))
chromosome_labels <- c(as.character(1:22), "X", "Y")
sector_colors <- c(
  "#ed1c24", "#a93b55", "#6a5f8f", "#2f83b7", "#3aa0a0", "#43a765",
  "#4caf45", "#6d846f", "#98659a", "#b24e8e", "#cf5f4d", "#f07818",
  "#ff8a00", "#ffaa00", "#ffe100", "#f4de1f", "#d9aa22", "#b96b26",
  "#c75c46", "#df6b87", "#ee74b0", "#bd86a8", "#8f8f8f", "#ed1c24"
)

background_track <- function() {
  circos.trackPlotRegion(
    ylim = c(0, 1),
    track.height = 0.085,
    bg.border = "#ffffff",
    bg.col = "#eeeeee",
    panel.fun = function(x, y) {
      circos.lines(CELL_META$xlim, c(0.5, 0.5), col = "#ffffff", lwd = 1.8)
    }
  )
}

segment_midpoint <- function(df) {
  if (nrow(df) == 0) {
    return(df)
  }
  df$mid <- (df$start + df$stop) / 2
  df
}

svglite::svglite(svg_output, width = 9.5, height = 9.5, pointsize = 10)
circos.clear()
circos.par(
  start.degree = 90,
  gap.after = c(rep(1, 23), 5),
  track.margin = c(0.002, 0.002),
  cell.padding = c(0, 0, 0, 0),
  points.overflow.warning = FALSE
)

circos.initializeWithIdeogram(species = species, chromosome.index = chromosomes, plotType = NULL)

circos.trackPlotRegion(
  ylim = c(0, 1),
  track.height = 0.055,
  bg.border = NA,
  panel.fun = function(x, y) {
    sector_index <- CELL_META$sector.index
    sector_number <- match(sector_index, chromosomes)
    circos.rect(
      CELL_META$xlim[1],
      0,
      CELL_META$xlim[2],
      1,
      col = sector_colors[sector_number],
      border = "#ffffff",
      lwd = 2
    )
    circos.text(
      CELL_META$xcenter,
      1.55,
      chromosome_labels[sector_number],
      facing = "bending.inside",
      niceFacing = TRUE,
      cex = 1.0,
      col = "#000000"
    )
  }
)

background_track()
background_track()
background_track()

if (nrow(gains) > 0) {
  gains <- segment_midpoint(gains)
  circos.genomicTrackPlotRegion(
    gains,
    ylim = c(0, 6),
    track.height = 0.095,
    bg.border = "#ffffff",
    bg.col = "#eeeeee",
    panel.fun = function(region, value, ...) {
      circos.genomicRect(
        region,
        ybottom = 3.2,
        ytop = pmin(5.8, value$value),
        col = "#d7191c",
        border = NA,
        ...
      )
      circos.genomicPoints(
        region,
        value,
        y = pmin(5.7, value$value + 1.2),
        col = "#d7191c",
        pch = 16,
        cex = 0.35,
        ...
      )
    }
  )
}

if (nrow(losses) > 0) {
  losses <- segment_midpoint(losses)
  circos.genomicTrackPlotRegion(
    losses,
    ylim = c(0, 3),
    track.height = 0.095,
    bg.border = "#ffffff",
    bg.col = "#eeeeee",
    panel.fun = function(region, value, ...) {
      circos.genomicRect(
        region,
        ybottom = 0.15,
        ytop = 1.05,
        col = "#2c7bb6",
        border = NA,
        ...
      )
      circos.genomicPoints(
        region,
        value,
        y = rep(1.75, nrow(region)),
        col = "#2c7bb6",
        pch = 16,
        cex = 0.35,
        ...
      )
    }
  )
}

if (nrow(connections) > 0) {
  link_region <- function(pos, event_count) {
    width <- 900000 + (min(max(event_count, 1), 10) * 250000)
    c(max(0, pos - width), pos + width)
  }
  for (i in seq_len(nrow(connections))) {
    event_count <- if ("event_count" %in% names(connections)) connections$event_count[i] else 1
    alpha <- min(0.95, 0.55 + (0.04 * min(max(event_count, 1), 10)))
    circos.link(
      connections$chr1[i],
      link_region(connections$pos1[i], event_count),
      connections$chr2[i],
      link_region(connections$pos2[i], event_count),
      col = adjustcolor("#71dfc0", alpha.f = alpha),
      border = adjustcolor("#71dfc0", alpha.f = min(0.98, alpha + 0.08)),
      h.ratio = 0.65
    )
  }
}

legend(
  "bottomleft",
  legend = c("Gain / Duplication / Amplification", "Loss / Deletion", "Translocation"),
  fill = c("#d7191c", "#2c7bb6", adjustcolor("#71dfc0", alpha.f = 0.88)),
  border = NA,
  bty = "n",
  cex = 0.8
)

circos.clear()
dev.off()
