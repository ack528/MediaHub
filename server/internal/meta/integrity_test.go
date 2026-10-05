package meta

import (
	"os"
	"path/filepath"
	"testing"
)

func writeMP4(t *testing.T, dir, name string, boxes ...[]byte) string {
	t.Helper()
	var all []byte
	for _, b := range boxes {
		all = append(all, b...)
	}
	p := filepath.Join(dir, name)
	if err := os.WriteFile(p, all, 0o644); err != nil {
		t.Fatal(err)
	}
	return p
}

func box(typ string, payload int) []byte {
	n := 8 + payload
	b := []byte{byte(n >> 24), byte(n >> 16), byte(n >> 8), byte(n)}
	b = append(b, typ...)
	return append(b, make([]byte, payload)...)
}

func TestMP4Incomplete(t *testing.T) {
	dir := t.TempDir()
	cases := []struct {
		name string
		path string
		want bool
	}{
		{"完整(moov 在前)", writeMP4(t, dir, "ok1.mp4", box("ftyp", 12), box("moov", 40), box("mdat", 1000)), false},
		{"完整(moov 在后)", writeMP4(t, dir, "ok2.mp4", box("ftyp", 12), box("mdat", 1000), box("moov", 40)), false},
		{"没有 moov(下载中断,moov 在末尾没下到)", writeMP4(t, dir, "bad1.mp4", box("ftyp", 12), box("mdat", 1000)), true},
		{"mdat 被截断", writeMP4(t, dir, "bad2.mp4", box("ftyp", 12), box("moov", 40), box("mdat", 1000)[:500]), true},
		{"只有几个字节", writeMP4(t, dir, "bad3.mp4", []byte{0, 0, 0, 8, 'f'}), true},
	}
	for _, c := range cases {
		got, err := MP4Incomplete(c.path)
		if err != nil || got != c.want {
			t.Errorf("%s: got %v err %v, want %v", c.name, got, err, c.want)
		}
	}
}
