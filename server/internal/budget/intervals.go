package budget

import "sort"

// Interval is a stretch of a video, [start, end) in seconds from its beginning.
type Interval [2]float64

// AddInterval merges [start, end) into sorted, non-overlapping intervals and returns the
// seconds it newly covered along with the merged list.
func AddInterval(intervals []Interval, start, end float64) (float64, []Interval) {
	if end <= start {
		return 0, intervals
	}
	merged := make([]Interval, 0, len(intervals)+1)
	lo, hi := start, end
	overlap := 0.0
	placed := false
	for _, iv := range intervals {
		a, b := iv[0], iv[1]
		switch {
		case b < lo:
			merged = append(merged, iv)
		case a > hi:
			if !placed {
				merged = append(merged, Interval{lo, hi})
				placed = true
			}
			merged = append(merged, iv)
		default:
			overlap += max(0, min(b, end)-max(a, start))
			lo, hi = min(lo, a), max(hi, b)
		}
	}
	if !placed {
		merged = append(merged, Interval{lo, hi})
	}
	return (end - start) - overlap, merged
}

// addAll merges several reported stretches, in any order, and returns the new seconds.
func addAll(intervals []Interval, reported []Interval) (float64, []Interval) {
	sorted := append([]Interval(nil), reported...)
	sort.Slice(sorted, func(i, j int) bool { return sorted[i][0] < sorted[j][0] })
	total := 0.0
	for _, iv := range sorted {
		var added float64
		added, intervals = AddInterval(intervals, iv[0], iv[1])
		total += added
	}
	return total, intervals
}
