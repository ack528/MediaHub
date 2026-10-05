package thumbhash

import "testing"

func solid(w, h int, r, g, b, a byte) []byte {
	px := make([]byte, 0, w*h*4)
	for i := 0; i < w*h; i++ {
		px = append(px, r, g, b, a)
	}
	return px
}

func TestSolidColorHasNoDetail(t *testing.T) {
	// 纯色不透明图:无 alpha 位;AC 系数全为 0 → 缩放项为 0,后面的 AC 字节取 0x88(0.5 → 8)模式
	h := Encode(32, 32, solid(32, 32, 255, 0, 0, 255))
	if len(h) < 5 {
		t.Fatalf("hash too short: %d", len(h))
	}
	if h[2]&0x80 != 0 {
		t.Fatal("opaque image must not set the alpha bit")
	}
	if scale := (h[2] >> 2) & 31; scale != 0 { // header24 的 bits 18..22 是亮度 AC 缩放;纯色应为 0
		t.Fatalf("luminance scale should be 0 for a flat image, got %d", scale)
	}
}

func TestLengthAndGradient(t *testing.T) {
	w, h := 32, 24
	px := make([]byte, 0, w*h*4)
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			px = append(px, byte(x*255/(w-1)), byte(y*255/(h-1)), 128, 255)
		}
	}
	got := Encode(w, h, px)
	if len(got) < 20 || len(got) > 26 {
		t.Fatalf("unexpected hash length %d", len(got))
	}
	if got[4]>>7 != 1 { // header16 bit15:横图
		t.Fatalf("landscape flag not set: % x", got)
	}
	// 同一输入结果稳定
	if string(got) != string(Encode(w, h, px)) {
		t.Fatal("encode is not deterministic")
	}
}

func TestAlphaImageSetsFlagAndIsLonger(t *testing.T) {
	opaque := Encode(16, 16, solid(16, 16, 10, 200, 30, 255))
	transparent := Encode(16, 16, solid(16, 16, 10, 200, 30, 100))
	if transparent[2]&0x80 == 0 {
		t.Fatal("alpha bit missing")
	}
	if len(transparent) <= len(opaque) {
		t.Fatalf("alpha hash should be longer: %d vs %d", len(transparent), len(opaque))
	}
}
