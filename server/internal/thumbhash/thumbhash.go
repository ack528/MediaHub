// Package thumbhash 实现 ThumbHash(https://github.com/evanw/thumbhash,MIT)的编码端:
// 把一张很小的 RGBA 图编码成约 25 字节的占位图,客户端据此即时渲染模糊预览。
package thumbhash

import "math"

// Encode 编码 w×h 的 RGBA 图像(行优先,每像素 4 字节)。w、h 应不超过 100。
func Encode(w, h int, rgba []byte) []byte {
	limit := w
	if h > limit {
		limit = h
	}
	n := w * h

	// 平均色(按 alpha 加权)
	var avgR, avgG, avgB, avgA float64
	for i, j := 0, 0; i < n; i, j = i+1, j+4 {
		a := float64(rgba[j+3]) / 255
		avgR += a / 255 * float64(rgba[j])
		avgG += a / 255 * float64(rgba[j+1])
		avgB += a / 255 * float64(rgba[j+2])
		avgA += a
	}
	if avgA > 0 {
		avgR /= avgA
		avgG /= avgA
		avgB /= avgA
	}
	hasAlpha := avgA < float64(n)
	lLimit := 7.0
	if hasAlpha {
		lLimit = 5
	}
	lx := imax(1, iround(lLimit*float64(w)/float64(limit)))
	ly := imax(1, iround(lLimit*float64(h)/float64(limit)))

	l := make([]float64, n)
	p := make([]float64, n)
	q := make([]float64, n)
	a := make([]float64, n)
	for i, j := 0, 0; i < n; i, j = i+1, j+4 {
		alpha := float64(rgba[j+3]) / 255
		r := avgR*(1-alpha) + alpha/255*float64(rgba[j])
		g := avgG*(1-alpha) + alpha/255*float64(rgba[j+1])
		b := avgB*(1-alpha) + alpha/255*float64(rgba[j+2])
		l[i] = (r + g + b) / 3
		p[i] = (r+g)/2 - b
		q[i] = r - g
		a[i] = alpha
	}

	lDC, lAC, lScale := encodeChannel(w, h, l, imax(3, lx), imax(3, ly))
	pDC, pAC, pScale := encodeChannel(w, h, p, 3, 3)
	qDC, qAC, qScale := encodeChannel(w, h, q, 3, 3)
	var aDC, aScale float64
	var aAC []float64
	if hasAlpha {
		aDC, aAC, aScale = encodeChannel(w, h, a, 5, 5)
	}

	isLandscape := w > h
	header24 := iround(63*lDC) | iround(31.5+31.5*pDC)<<6 | iround(31.5+31.5*qDC)<<12 | iround(31*lScale)<<18
	if hasAlpha {
		header24 |= 1 << 23
	}
	header16 := 0
	if isLandscape {
		header16 = ly
	} else {
		header16 = lx
	}
	header16 |= iround(63*pScale)<<3 | iround(63*qScale)<<9
	if isLandscape {
		header16 |= 1 << 15
	}
	hash := []byte{byte(header24 & 255), byte((header24 >> 8) & 255), byte(header24 >> 16), byte(header16 & 255), byte(header16 >> 8)}
	acStart := 5
	if hasAlpha {
		acStart = 6
		hash = append(hash, byte(iround(15*aDC)|iround(15*aScale)<<4))
	}
	groups := [][]float64{lAC, pAC, qAC}
	if hasAlpha {
		groups = append(groups, aAC)
	}
	idx := 0
	for _, g := range groups {
		for _, f := range g {
			for len(hash) <= acStart+(idx>>1) {
				hash = append(hash, 0)
			}
			hash[acStart+(idx>>1)] |= byte(iround(15*f) << ((idx & 1) << 2))
			idx++
		}
	}
	return hash
}

func encodeChannel(w, h int, channel []float64, nx, ny int) (dc float64, ac []float64, scale float64) {
	fx := make([]float64, w)
	for cy := 0; cy < ny; cy++ {
		for cx := 0; cx*ny < nx*(ny-cy); cx++ {
			f := 0.0
			for x := 0; x < w; x++ {
				fx[x] = math.Cos(math.Pi / float64(w) * float64(cx) * (float64(x) + 0.5))
			}
			for y := 0; y < h; y++ {
				fy := math.Cos(math.Pi / float64(h) * float64(cy) * (float64(y) + 0.5))
				for x := 0; x < w; x++ {
					f += channel[x+y*w] * fx[x] * fy
				}
			}
			f /= float64(w * h)
			if cx > 0 || cy > 0 {
				ac = append(ac, f)
				scale = math.Max(scale, math.Abs(f))
			} else {
				dc = f
			}
		}
	}
	if scale > 0 {
		for i := range ac {
			ac[i] = 0.5 + 0.5/scale*ac[i]
		}
	}
	return
}

// iround 与 JavaScript 的 Math.round 一致(.5 向正无穷)。
func iround(x float64) int { return int(math.Floor(x + 0.5)) }

func imax(a, b int) int {
	if a > b {
		return a
	}
	return b
}
