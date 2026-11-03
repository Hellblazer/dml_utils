# chart.awk - emit an SVG line chart of the benchmark means.
#
# Reads the results CSV
# (strategy,chunk_unit,chunk_size,threads,run,chunks,range_seconds,chunk_seconds,total_seconds,pk_columns,partition_wise)
# on stdin and writes an SVG of the mean total time vs threads to stdout. Pass
# -v strategy=..., -v chunk_size=N, -v pk_columns=k1+k2 and
# -v partition_wise=yes|no to plot only that group. Pure awk: no plotting library
# needed, so the benchmark keeps working with nothing but Docker and the shell.

BEGIN {
    FS = ","
    if (strategy == "") strategy = ""
    if (chunk_size == "") chunk_size = ""
    if (pk_columns == "") pk_columns = ""
    if (partition_wise == "") partition_wise = "no"
}

NR == 1 { next }   # header

strategy != "" && $1 != strategy { next }
chunk_size != "" && $3 != chunk_size { next }
pk_columns != "" && $10 != pk_columns { next }
row_pw = ($11 == "yes") ? "yes" : "no"
row_pw != partition_wise { next }

{
    t = $4 + 0
    n[t]++
    s[t] += $9 + 0
    if (!(t in seen)) { seen[t] = 1; order[++k] = t }
}

function xpos(i) {
    if (k == 1) return ml + pw / 2
    return ml + (i - 1) * pw / (k - 1)
}

function ypos(v) {
    return mt + ph - (v / ymax) * ph
}

function ceil(x) {
    return (x == int(x)) ? x : int(x) + 1
}

END {
    if (k == 0) {
        print "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"760\" height=\"120\">" \
            "<text x=\"20\" y=\"60\" font-family=\"sans-serif\" font-size=\"14\">no results</text></svg>"
        exit
    }

    # Sort thread values ascending (k is small).
    for (i = 1; i <= k; i++)
        for (j = i + 1; j <= k; j++)
            if (order[i] > order[j]) { tmp = order[i]; order[i] = order[j]; order[j] = tmp }

    maxy = 0
    for (i = 1; i <= k; i++) {
        t = order[i]
        ms[i] = s[t] / n[t]
        if (ms[i] > maxy) maxy = ms[i]
    }
    if (maxy <= 0) maxy = 1
    # Whole-number y axis on the human-friendly 1-2-5 scale: pick a step of
    # 1, 2, 5, 10, 20, 50, ... giving about six ticks, and round the top up to a
    # multiple of the step so every label is an integer number of seconds.
    raw = maxy / 6
    mag = 1
    while (mag * 10 <= raw) mag *= 10
    if (mag >= raw) step = mag
    else if (mag * 2 >= raw) step = mag * 2
    else if (mag * 5 >= raw) step = mag * 5
    else step = mag * 10
    if (step < 1) step = 1
    ymax = ceil(maxy / step) * step

    W = 760; H = 420
    ml = 64; mright = 20; mt = 48; mb = 64
    pw = W - ml - mright
    ph = H - mt - mb

    printf "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"%d\" height=\"%d\" viewBox=\"0 0 %d %d\" font-family=\"sans-serif\">\n", W, H, W, H
    printf "  <rect width=\"%d\" height=\"%d\" fill=\"white\"/>\n", W, H
    printf "  <text x=\"%d\" y=\"26\" font-size=\"16\" font-weight=\"bold\" text-anchor=\"middle\">run_migration_chunks (%s, key %s, chunk_size %s): mean total time vs threads</text>\n", ml + pw / 2, strategy, pk_columns, chunk_size

    # Horizontal grid and whole-number y labels.
    for (v = 0; v <= ymax + 1e-9; v += step) {
        y = ypos(v)
        printf "  <line x1=\"%d\" y1=\"%.1f\" x2=\"%d\" y2=\"%.1f\" stroke=\"#e6e6e6\"/>\n", ml, y, ml + pw, y
        printf "  <text x=\"%d\" y=\"%.1f\" font-size=\"11\" text-anchor=\"end\" dominant-baseline=\"middle\">%d</text>\n", ml - 8, y, int(v)
    }

    # Axes.
    printf "  <line x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"#333\"/>\n", ml, mt, ml, mt + ph
    printf "  <line x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"#333\"/>\n", ml, mt + ph, ml + pw, mt + ph

    # x ticks and labels.
    for (i = 1; i <= k; i++) {
        x = xpos(i)
        printf "  <line x1=\"%.1f\" y1=\"%d\" x2=\"%.1f\" y2=\"%d\" stroke=\"#333\"/>\n", x, mt + ph, x, mt + ph + 4
        printf "  <text x=\"%.1f\" y=\"%d\" font-size=\"10\" text-anchor=\"middle\">%d</text>\n", x, mt + ph + 18, order[i]
    }
    printf "  <text x=\"%d\" y=\"%d\" font-size=\"12\" text-anchor=\"middle\">threads</text>\n", ml + pw / 2, H - 12
    printf "  <text x=\"16\" y=\"%d\" font-size=\"12\" text-anchor=\"middle\" transform=\"rotate(-90 16 %d)\">seconds (mean)</text>\n", mt + ph / 2, mt + ph / 2

    # One series: the mean total time. The polyline is built inline because this
    # awk cannot pass arrays to functions.
    pts = ""
    for (i = 1; i <= k; i++) pts = pts sprintf("%.1f,%.1f ", xpos(i), ypos(ms[i]))
    printf "  <polyline fill=\"none\" stroke=\"#2ca02c\" stroke-width=\"2\" points=\"%s\"/>\n", pts
    for (i = 1; i <= k; i++)
        printf "  <circle cx=\"%.1f\" cy=\"%.1f\" r=\"3\" fill=\"#2ca02c\"/>\n", xpos(i), ypos(ms[i])

    # Legend (top-left inside the plot).
    lx = ml + 12; ly = mt + 12
    printf "  <line x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"#2ca02c\" stroke-width=\"2\"/><text x=\"%d\" y=\"%d\" font-size=\"12\" dominant-baseline=\"middle\">total elapsed time</text>\n", lx, ly, lx + 20, ly, lx + 26, ly

    print "</svg>"
}
