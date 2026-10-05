package natsort

import (
	"sort"
	"testing"
)

func TestNaturalOrder(t *testing.T) {
	in := []string{"IMG_10.jpg", "img_2.jpg", "IMG_1.jpg", "a10b", "a9b", "第10集.mp4", "第2集.mp4", "file001.txt", "file01.txt"}
	sort.Slice(in, func(i, j int) bool { return Key(in[i]) < Key(in[j]) })
	want := []string{"a9b", "a10b", "file001.txt", "file01.txt", "IMG_1.jpg", "img_2.jpg", "IMG_10.jpg", "第2集.mp4", "第10集.mp4"}
	// file001 与 file01 数值相同,键相等,相对顺序不稳定,只校验其余相对关系
	pos := map[string]int{}
	for i, s := range in {
		pos[s] = i
	}
	for _, pair := range [][2]string{{"a9b", "a10b"}, {"IMG_1.jpg", "img_2.jpg"}, {"img_2.jpg", "IMG_10.jpg"}, {"第2集.mp4", "第10集.mp4"}} {
		if pos[pair[0]] >= pos[pair[1]] {
			t.Errorf("%s should sort before %s; got %v (want like %v)", pair[0], pair[1], in, want)
		}
	}
}

func TestHugeNumber(t *testing.T) {
	a, b := Key("x"+"9999999999999999999999"), Key("x"+"10000000000000000000000")
	if !(a < b) {
		t.Fatal("longer digit run must be larger")
	}
}
